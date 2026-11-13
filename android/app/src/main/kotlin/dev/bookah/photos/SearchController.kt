package dev.bookah.photos

import android.app.Application
import dev.bookah.photos.data.Session
import dev.bookah.photos.search.ModelStore
import dev.bookah.photos.search.ModelSync
import dev.bookah.photos.search.ModelUi
import dev.bookah.photos.search.OnnxQueryEncoder
import dev.bookah.photos.search.PhoneTokenizer
import dev.bookah.photos.search.SearchActions
import dev.bookah.photos.search.SearchBlock
import dev.bookah.photos.search.SearchEngine
import dev.bookah.photos.search.SearchResultsUi
import dev.bookah.photos.search.SearchUi
import dev.bookah.photos.search.TagIndex
import dev.bookah.photos.search.TagLabel
import dev.bookah.photos.search.VectorIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Search on the phone: keeps the picture vectors and tags of the current snapshot in memory, the search model (downloaded once from Nextcloud when the
 * reader asks for it), and answers a typed question or a category. Everything runs off the main thread and with no network once the model is on the phone.
 */
class SearchController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val session: () -> Session?,
) : SearchActions {
    private val modelStore = ModelStore(File(app.filesDir, "models"))
    private val prefs = app.getSharedPreferences("search", android.content.Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow(SearchUi(recents = loadRecents()))
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()

    private var vectors: VectorIndex? = null
    private var tags: TagIndex = TagIndex.EMPTY
    private var engine: SearchEngine? = null
    private var encoder: OnnxQueryEncoder? = null
    private var modelId: String? = null
    private val engineLock = Mutex()
    private var downloadJob: Job? = null
    private var searchJob: Job? = null

    /** The size of the model on this phone (for Settings); zero when none. */
    fun modelBytesOnPhone(): Long = modelStore.sizeOnDisk()

    /** A new snapshot is current: read its vectors and tags (about 50 MB of numbers, off the main thread). */
    fun onSnapshot(file: File?) {
        scope.launch(Dispatchers.Default) {
            val v = file?.let(VectorIndex::load)
            val t = file?.let(TagIndex::load) ?: TagIndex.EMPTY
            engineLock.withLock { vectors = v; tags = t; engine = null }
            _ui.update { it.copy(hasVectors = v != null, labels = t.labels, blocked = blockedNow(), results = null) }
        }
    }

    private fun blockedNow(): SearchBlock? = when {
        vectors == null -> SearchBlock.NoVectors
        modelId != null && vectors!!.modelId != modelId -> SearchBlock.WrongModel
        else -> null
    }

    /** Looks at what is on the phone; when there is no model asks Nextcloud whether one exists (a tiny request) so the screen can offer the download. */
    override fun retryModel() { refreshModel() }

    /** Asks again only when the state is not settled (not on the phone yet, or a failed check): used after every index check and after sign-in. */
    fun ensureModelChecked() {
        when (_ui.value.model) {
            is ModelUi.Ready, is ModelUi.Downloading, is ModelUi.Loading, is ModelUi.Available -> Unit
            else -> refreshModel()
        }
    }

    fun refreshModel() {
        scope.launch {
            val installed = withContext(Dispatchers.IO) { modelStore.installed() }
            if (installed != null) {
                modelId = installed.manifest.modelId
                _ui.update { it.copy(model = ModelUi.Loading, blocked = blockedNow()) }
                ensureEngine()
                return@launch
            }
            val s = session() ?: return@launch _ui.update { it.copy(model = ModelUi.NotPublished) }
            _ui.update { it.copy(model = ModelUi.Checking) }
            val manifest = runCatching { ModelSync(http, s, modelStore).fetchManifest() }
            _ui.update {
                it.copy(model = manifest.fold(
                    { m -> if (m == null) ModelUi.NotPublished else ModelUi.Available(m.totalSize) },
                    { e -> ModelUi.Failed(e.message ?: e.javaClass.simpleName) },
                ))
            }
        }
    }

    override fun downloadModel() {
        val s = session() ?: return
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _ui.update { it.copy(model = ModelUi.Checking) }
            try {
                val sync = ModelSync(http, s, modelStore)
                val manifest = sync.fetchManifest() ?: return@launch _ui.update { it.copy(model = ModelUi.NotPublished) }
                _ui.update { it.copy(model = ModelUi.Downloading(0, manifest.totalSize)) }
                sync.download(manifest) { done, total -> _ui.update { u -> if (u.model is ModelUi.Downloading) u.copy(model = ModelUi.Downloading(done, total)) else u } }
                refreshModel()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(model = ModelUi.Failed(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    /** Loads the tokenizer and the model into memory once; later searches reuse them. */
    private suspend fun ensureEngine(): SearchEngine? = withContext(Dispatchers.Default) {
        engineLock.withLock {
            engine?.let { return@withLock it }
            val installed = modelStore.installed()
            if (installed == null) { _ui.update { it.copy(model = ModelUi.NotPublished) }; return@withLock null }
            try {
                val tokenizer = PhoneTokenizer.load(installed.tokenizer)
                val enc = OnnxQueryEncoder(installed, tokenizer)
                encoder?.close(); encoder = enc
                modelId = installed.manifest.modelId
                val e = SearchEngine(vectors, modelId, enc)
                engine = e
                _ui.update { it.copy(model = ModelUi.Ready, blocked = blockedNow()) }
                e
            } catch (ex: Throwable) {
                _ui.update { it.copy(model = ModelUi.Failed(ex.message ?: ex.javaClass.simpleName)) }
                null
            }
        }
    }

    override fun submit(question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        searchJob = scope.launch {
            _ui.update { it.copy(searching = true) }
            val t0 = android.os.SystemClock.elapsedRealtime()
            val e = engine ?: ensureEngine()
            val hits = withContext(Dispatchers.Default) {
                val v = vectors
                if (e == null || v == null) emptyList() else {
                    val engineNow = SearchEngine(v, modelId, encoder)
                    engineNow.search(q, 200).let { all ->
                        // keep what is close to the best match (at least a screenful), so a nonsense tail is not shown
                        if (all.isEmpty()) all else { val cut = all.first().score - 0.08f; all.filterIndexed { i, h -> i < 24 || h.score >= cut } }
                    }
                }
            }
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            remember(q)
            _ui.update { it.copy(searching = false, results = SearchResultsUi(q, LongArray(hits.size) { i -> hits[i].id }, false, ms), recents = loadRecents(), blocked = blockedNow()) }
        }
    }

    override fun pickCategory(label: TagLabel, greek: Boolean) {
        val ids = tags.filesFor(label.key)
        _ui.update { it.copy(results = SearchResultsUi(label.name(greek), ids, true, 0)) }
    }

    override fun clearResults() { searchJob?.cancel(); _ui.update { it.copy(results = null, searching = false) } }

    override fun clearRecents() { prefs.edit().remove("recent").apply(); _ui.update { it.copy(recents = emptyList()) } }

    fun deleteModel() {
        scope.launch {
            engineLock.withLock { encoder?.close(); encoder = null; engine = null; modelId = null }
            withContext(Dispatchers.IO) { modelStore.delete() }
            _ui.update { it.copy(model = ModelUi.Checking, blocked = blockedNow()) }
            refreshModel()
        }
    }

    /** Signing out: drop everything that came from the library (the model is not personal and stays). */
    fun clearLibraryData() {
        scope.launch {
            engineLock.withLock { vectors = null; tags = TagIndex.EMPTY; engine = null }
            prefs.edit().clear().apply()
            _ui.update { SearchUi(model = it.model) }
        }
    }

    private fun loadRecents(): List<String> = prefs.getString("recent", "")!!.split('\u001F').filter { it.isNotBlank() }

    private fun remember(q: String) {
        val list = (listOf(q) + loadRecents().filter { !it.equals(q, ignoreCase = true) }).take(8)
        prefs.edit().putString("recent", list.joinToString("\u001F")).apply()
    }
}
