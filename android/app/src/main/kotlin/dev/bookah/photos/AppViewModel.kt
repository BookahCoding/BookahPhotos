package dev.bookah.photos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.bookah.photos.data.Http
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.Originals
import dev.bookah.photos.data.ShareItem
import dev.bookah.photos.data.SnapshotReader as Reader
import dev.bookah.photos.data.KeystoreSessionStore
import dev.bookah.photos.data.LoginFlowV2
import dev.bookah.photos.data.Session
import dev.bookah.photos.data.SnapshotInfo
import dev.bookah.photos.data.SnapshotReader
import dev.bookah.photos.data.SnapshotStore
import dev.bookah.photos.data.SnapshotSync
import dev.bookah.photos.data.SyncResult
import dev.bookah.photos.data.normalizeServerAddress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File

sealed interface SignInUi {
    data object Idle : SignInUi
    data object Starting : SignInUi
    /** The browser page is open; [loginUrl] is set until the screen has opened it once. */
    data class Waiting(val loginUrl: String?) : SignInUi
    data class Error(val message: ErrorKind, val detail: String? = null) : SignInUi
    enum class ErrorKind { BadAddress, Failed, TimedOut }
}

sealed interface SyncUi {
    data object Idle : SyncUi
    data object Checking : SyncUi
    data class Downloading(val percent: Int) : SyncUi
    data class Done(val result: SyncResult) : SyncUi
}

data class UiState(
    val loaded: Boolean = false,
    val session: Session? = null,
    val signIn: SignInUi = SignInUi.Idle,
    val sync: SyncUi = SyncUi.Idle,
    val info: SnapshotInfo? = null,
    val library: LibraryIndex? = null,
    /** The reader allows big downloads (the index, the search model) on mobile data. Off by default. */
    val allowMobileData: Boolean = false,
    /** Photos that are the same picture saved more than once: asset id -> group. */
    val duplicates: Map<Long, Long> = emptyMap(),
    /** The photos the reader hid (shown only behind the unlock prompt). */
    val hiddenLibrary: LibraryIndex = LibraryIndex.build(emptyList()),
    /** Memories and places of the snapshot (read only). */
    val memories: dev.bookah.photos.data.MemoriesIndex = dev.bookah.photos.data.MemoriesIndex.EMPTY,
    val places: dev.bookah.photos.data.PlacesIndex = dev.bookah.photos.data.PlacesIndex.EMPTY,
    val share: ShareUi = ShareUi.Idle,
    val lists: dev.bookah.photos.data.NextcloudLists? = null,
    val prefs: dev.bookah.photos.data.SectionPrefs = dev.bookah.photos.data.SectionPrefs(),
    val localAccess: LocalAccess = LocalAccess.None,
    val localStats: dev.bookah.photos.data.MergeStats? = null,
    /** Files the reader asked to delete; the confirmation dialog is open while this is set. */
    val deleteIds: LongArray? = null,
    /** Counts up each time a delete finished (the screens end select mode and close the viewer). */
    val deleteDoneTick: Int = 0,
    /** While a delete runs: how many of the Nextcloud files are done, so the screen can show progress. */
    val deleteProgress: DeleteProgress? = null,
    val timings: Timings? = null,
    val update: UpdateUi = UpdateUi.Idle,
)

/** Measured on the phone itself, shown in Settings so the speed targets can be checked on the real device. */
class Timings(val startToLibraryMs: Long?, val mergeMs: Long, val items: Int, val beforeAppMs: Long = 0, val readMs: Long = 0)

sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val info: dev.bookah.photos.data.UpdateInfo) : UpdateUi
    data object Failed : UpdateUi
}

class DeleteProgress(val done: Int, val total: Int, val phone: Boolean)

enum class DeleteScope { Phone, Nextcloud, Everywhere }

/** What happened in a delete, for the message: [trashedOnPhone] is asked from Android's own dialog, so it is a count of the request. */
class DeleteResult(val nextcloudMoved: Int, val nextcloudFailed: Int, val phoneRequested: Int, val phoneCancelled: Boolean)

sealed interface ShareUi {
    data object Idle : ShareUi
    data class Preparing(val done: Int, val total: Int) : ShareUi
    data object Failed : ShareUi
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val http = Http.newClient()
    private val sessions = KeystoreSessionStore(app)
    private val store = SnapshotStore(File(app.filesDir, "snapshots"))
    private val listsCache = dev.bookah.photos.data.ListsCache(File(app.filesDir, "lists.json"))
    private val prefsStore = dev.bookah.photos.data.SectionPrefsStore(app)
    private val extraCache = dev.bookah.photos.data.ExtraFilesCache(File(app.filesDir, "newfiles.json"))

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Search by content on the phone: the picture vectors, the tags and the search model. */
    val search = SearchController(app, viewModelScope, http) { _state.value.session }.also { it.bigDownloadAllowed = { bigDownloadsAllowed() } }

    /** People and the reader's corrections of them (names, merges, hiding), sent to BookahPC as op files. */
    val people = PeopleController(app, viewModelScope, http) { _state.value.session }

    /** Albums of Nextcloud Photos: create, rename, delete, add and remove photos. */
    val albums = AlbumsController(
        viewModelScope, http, { _state.value.session },
        pathsOf = { ids ->
            val snapshot = store.currentFile()
            ((if (snapshot == null) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Reader.shareItems(snapshot, ids) }.map { it.id to it.path }) +
                extraFiles.filter { it.id in ids }.map { it.id to it.path }).toMap()
        },
        roots = { store.currentFile()?.let { f -> dev.bookah.photos.data.SnapshotMetaReader.read(f).roots } ?: listOf("Photos", "InstantUpload") },
        patchLists = { change -> patchLists(change) },
        refresh = { refreshLists() },
    )

    /** Changes the lists (favourites, albums) in memory and in the cache at once; the next read from Nextcloud replaces them with what the server has. */
    private fun patchLists(change: (dev.bookah.photos.data.NextcloudLists) -> dev.bookah.photos.data.NextcloudLists) {
        val now = _state.value.lists ?: dev.bookah.photos.data.NextcloudLists()
        val next = change(now)
        _state.update { it.copy(lists = next) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { listsCache.write(next) }
    }

    /** Favourites the reader sets or clears (sent to Nextcloud as the file's favourite flag; shown at once). */
    val favourites = FavouritesController(
        app, viewModelScope, http, { _state.value.session },
        pathsOf = { ids ->
            val snapshot = store.currentFile()
            ((if (snapshot == null) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Reader.shareItems(snapshot, ids) }.map { it.id to it.path }) +
                extraFiles.filter { it.id in ids }.map { it.id to it.path }).toMap()
        },
        roots = { store.currentFile()?.let { f -> dev.bookah.photos.data.SnapshotMetaReader.read(f).roots } ?: listOf("Photos", "InstantUpload") },
        onConfirmed = { refreshLists() },
    )

    /** Files ready to be handed to the share sheet (one-shot). */
    private val _shareReady = kotlinx.coroutines.flow.MutableSharedFlow<List<ShareFile>>(extraBufferCapacity = 1)
    val shareReady = _shareReady
    /** A file to share: a downloaded original in the cache (file) or a photo that is on this phone (uri, shared as it is, no copy made). */
    class ShareFile(val file: File?, val uri: String?, val mime: String?)

    private var signInJob: Job? = null
    private var syncJob: Job? = null

    init {
        viewModelScope.launch {
            val session = sessions.load()
            _state.update { it.copy(loaded = true, session = session, info = currentInfo(), lists = listsCache.read(), allowMobileData = settings.getBoolean("mobile_data", false)) }
            launch { prefsStore.flow.collect { p -> _state.update { it.copy(prefs = p) } } }
            // hiding and showing photos is applied at once, before BookahPC has seen the correction
            launch { people.state.map { it.hiddenPhotoDelta }.distinctUntilChanged().collect { d -> hiddenDelta = d; if (snapshotRows.isNotEmpty() || hiddenSnapshotRows.isNotEmpty()) rebuild() } }
            loadLibrary()
            if (session != null) { search.refreshModel(); sync() }
        }
    }

    private fun currentInfo(): SnapshotInfo? = store.currentFile()?.let(SnapshotReader::info)

    /** Phone copies to move to Android's trash: the activity asks the system (which shows its own consent dialog). */
    private val _trashRequests = kotlinx.coroutines.flow.MutableSharedFlow<List<android.net.Uri>>(extraBufferCapacity = 1)
    val trashRequests = _trashRequests
    private val _deleteResults = kotlinx.coroutines.flow.MutableSharedFlow<DeleteResult>(extraBufferCapacity = 4)
    val deleteResults = _deleteResults
    private var pendingNextcloud: List<Long> = emptyList()
    private var pendingPhoneCount = 0

    fun askDelete(ids: LongArray) { if (ids.isNotEmpty()) _state.update { it.copy(deleteIds = ids) } }
    fun cancelDelete() = _state.update { it.copy(deleteIds = null) }

    /**
     * Moves the chosen photos to the trash, on this phone (Android asks for consent), in Nextcloud (its trash bin) or both. Nothing is deleted
     * for good by the app. For "everywhere" the phone part goes first; if the reader says no in Android's dialog, Nextcloud is left alone.
     */
    fun delete(scope: DeleteScope) {
        val ids = _state.value.deleteIds ?: return
        val lib = _state.value.library ?: return
        _state.update { it.copy(deleteIds = null) }
        val positions = ids.asList().mapNotNull { lib.indexOfId(it) }
        val phoneUris = if (scope == DeleteScope.Nextcloud) emptyList() else positions.mapNotNull { lib.localUri[it] }.map { android.net.Uri.parse(it) }
        val ncIds = if (scope == DeleteScope.Phone) emptyList() else positions.map { lib.ids[it] }.filter { it > 0 }
        pendingNextcloud = ncIds
        pendingPhoneCount = phoneUris.size
        _state.update { it.copy(deleteProgress = DeleteProgress(0, ncIds.size, phoneUris.isNotEmpty())) }
        viewModelScope.launch {
            if (phoneUris.isNotEmpty()) _trashRequests.emit(phoneUris) else continueNextcloudDelete(phoneCancelled = false)
        }
    }

    /** The activity reports what the reader answered in Android's trash dialog. */
    fun phoneTrashResult(ok: Boolean) {
        viewModelScope.launch {
            if (!ok) {
                pendingNextcloud = emptyList()
                _state.update { it.copy(deleteProgress = null) }
                _deleteResults.emit(DeleteResult(0, 0, pendingPhoneCount, phoneCancelled = true))
                return@launch
            }
            reloadLocals()
            continueNextcloudDelete(phoneCancelled = false)
        }
    }

    private suspend fun continueNextcloudDelete(phoneCancelled: Boolean) {
        val ids = pendingNextcloud
        pendingNextcloud = emptyList()
        var moved = 0
        var failed = 0
        val session = _state.value.session
        val snapshot = store.currentFile()
        if (ids.isNotEmpty() && session != null) {
            val paths: Map<Long, String> =
                ((if (snapshot == null) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Reader.shareItems(snapshot, ids.toLongArray()) }.map { it.id to it.path }) +
                    extraFiles.filter { it.id in ids }.map { it.id to it.path }).toMap()
            val roots = snapshot?.let { dev.bookah.photos.data.SnapshotMetaReader.read(it).roots } ?: listOf("Photos", "InstantUpload")
            val trash = dev.bookah.photos.data.NextcloudTrash(http, session, roots)
            val gone = HashSet<Long>()
            try {
                for (id in ids) {
                    val path = paths[id]
                    if (path == null) { failed++; continue }
                    when (trash.moveToTrash(path)) {
                        is dev.bookah.photos.data.NextcloudTrash.Outcome.Deleted, is dev.bookah.photos.data.NextcloudTrash.Outcome.AlreadyGone -> { moved++; gone.add(id) }
                        else -> failed++
                    }
                    _state.update { it.copy(deleteProgress = DeleteProgress(moved + failed, ids.size, false)) }
                }
            } catch (e: dev.bookah.photos.data.SnapshotSync.UnauthorizedException) {
                _state.update { it.copy(deleteProgress = null) }
                signOutInternal()
                return
            }
            if (gone.isNotEmpty()) {
                snapshotRows = snapshotRows.filter { it.id !in gone }
                extraFiles = extraFiles.filter { it.id !in gone }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { extraCache.write(extraFiles) }
                rebuild()
            }
        }
        _state.update { it.copy(deleteDoneTick = it.deleteDoneTick + 1, deleteProgress = null) }
        _deleteResults.emit(DeleteResult(moved, failed, pendingPhoneCount, phoneCancelled))
    }

    private var snapshotRows: List<dev.bookah.photos.data.AssetRow> = emptyList()
    private var hiddenSnapshotRows: List<dev.bookah.photos.data.AssetRow> = emptyList()
    private var hiddenDelta: Map<Long, Boolean> = emptyMap()
    private var localRows: List<dev.bookah.photos.data.LocalRow> = emptyList()
    private var localById: Map<Long, dev.bookah.photos.data.LocalRow> = emptyMap()
    /** Files uploaded to Nextcloud after the index was made (asked from Nextcloud, cached). */
    private var extraFiles: List<dev.bookah.photos.data.ExtraFile> = emptyList()
    private val rebuildLock = kotlinx.coroutines.sync.Mutex()

    /** Reads the grid data of the current snapshot into memory (off the main thread) and merges it with the phone's photos. */
    /** When this screen's state was created, and how long reading the index file took: shown in Settings > Speed so a slow start can be told apart. */
    private val createdAt = android.os.SystemClock.elapsedRealtime()
    private var readMs = 0L

    private suspend fun loadLibrary() {
        val file = store.currentFile()
        val readStart = android.os.SystemClock.elapsedRealtime()
        if (file != null) {
            val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Reader.loadRows(file) }
            if (rows != null) snapshotRows = rows
            hiddenSnapshotRows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Reader.loadHiddenRows(file) }
            readMs = android.os.SystemClock.elapsedRealtime() - readStart
            search.onSnapshot(file)                                   // the vectors and tags of this snapshot (in the background)
            people.onSnapshot(file)                                   // people and the reader's corrections (in the background)
            // duplicates, memories and places are not needed to show the library: they are read in the background while the grid is already being built
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val dups = Reader.loadDuplicateGroups(file)
                val mem = dev.bookah.photos.data.MemoriesReader.load(file)
                val pl = dev.bookah.photos.data.PlacesReader.load(file)
                _state.update { it.copy(duplicates = dups, memories = mem, places = pl) }
            }
        }
        if (extraFiles.isEmpty()) extraFiles = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { extraCache.read() }
        rebuild()
    }

    /** Called by the screen when the media permission changes (or the app comes back to the front). */
    fun setLocalAccess(access: LocalAccess) {
        if (access == _state.value.localAccess && localRows.isNotEmpty() == (access != LocalAccess.None)) return
        _state.update { it.copy(localAccess = access) }
        reloadLocals()
    }

    /** Reads the phone's photos again (new screenshot, deleted photo...) and rebuilds the library. */
    fun reloadLocals() {
        viewModelScope.launch {
            val access = _state.value.localAccess
            val rows = if (access == LocalAccess.None) emptyList()
            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.bookah.photos.data.LocalPhotosReader.read(getApplication()) }
            localRows = rows
            localById = rows.associateBy { it.mediaId }
            rebuild()
        }
    }

    private suspend fun rebuild() = rebuildLock.withLock {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val (lib, stats, hiddenLib) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            // files uploaded after the index was made, unless the index already holds them
            val known = (snapshotRows.asSequence() + hiddenSnapshotRows.asSequence()).mapTo(HashSet()) { it.id }
            val extra = extraFiles.filter { it.id !in known }.map { it.toRow() }
            // hidden photos take part in the merge (so their phone copies are hidden with them) and are then taken out of the library
            val hidden = dev.bookah.photos.data.HiddenPhotos.effective(hiddenSnapshotRows.mapTo(HashSet()) { it.id }, hiddenDelta)
            val merged = dev.bookah.photos.data.LocalMerge.merge(snapshotRows + hiddenSnapshotRows + extra, localRows, hidden = hidden)
            Triple(LibraryIndex.build(merged.rows), merged.stats, LibraryIndex.build(merged.hiddenRows))
        }
        val mergeMs = android.os.SystemClock.elapsedRealtime() - t0
        _state.update {
            val first = it.timings?.startToLibraryMs ?: (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())
            it.copy(library = if (lib.size > 0 || store.currentFile() != null) lib else null, hiddenLibrary = hiddenLib, localStats = stats, timings = Timings(first, mergeMs, lib.size, beforeAppMs = createdAt - android.os.Process.getStartElapsedRealtime(), readMs = readMs))
        }
    }

    /** Asks GitHub for a newer version. Switched on only when this build knows a public repository; a private one is never asked. */
    fun checkUpdates() {
        val repo = BuildConfig.RELEASES_REPO
        if (repo.isBlank() || _state.value.update == UpdateUi.Checking) return
        _state.update { it.copy(update = UpdateUi.Checking) }
        viewModelScope.launch {
            val result = try {
                val newer = dev.bookah.photos.data.UpdateChecker(http, repo).newerThan(BuildConfig.VERSION_NAME)
                if (newer == null) UpdateUi.UpToDate else UpdateUi.Available(newer)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateUi.Failed
            }
            _state.update { it.copy(update = result) }
        }
    }

    /**
     * Shares photos. A photo that is on this phone is shared as it is (no download); the others are downloaded from Nextcloud first.
     * A negative id is a phone-only photo and is never looked up in Nextcloud.
     */
    fun share(ids: LongArray) {
        val lib = _state.value.library ?: return
        if (_state.value.share is ShareUi.Preparing) return
        viewModelScope.launch {
            val slots: List<Slot> = ids.map { id ->
                val local = lib.indexOfId(id)?.let { i -> lib.localUri[i] }
                if (local != null) Slot.Local(local) else Slot.Remote(id)
            }
            val remoteIds = slots.filterIsInstance<Slot.Remote>().map { it.id }.filter { it > 0 }
            val session = _state.value.session
            val snapshot = store.currentFile()
            val downloads: Map<Long, ShareItem> =
                if (remoteIds.isEmpty() || snapshot == null) emptyMap()
                else (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Reader.shareItems(snapshot, remoteIds.toLongArray()) } +
                    extraFiles.filter { it.id in remoteIds }.map { ShareItem(it.id, it.path, it.mime) }).associateBy { it.id }
            val nothingToShare = slots.none { it is Slot.Local } && downloads.isEmpty()
            if (nothingToShare || (downloads.isNotEmpty() && session == null)) {
                _state.update { it.copy(share = ShareUi.Failed) }
                return@launch
            }
            val dir = File(getApplication<Application>().cacheDir, "share")
            dir.deleteRecursively()      // only the latest share is kept on the phone
            val originals = session?.let { Originals(http, it) }
            try {
                val files = ArrayList<ShareFile>()
                val total = slots.size
                slots.forEachIndexed { n, slot ->
                    when (slot) {
                        is Slot.Local -> files.add(ShareFile(null, slot.uri, null))
                        is Slot.Remote -> {
                            val item = downloads[slot.id]
                            if (item != null && originals != null) {
                                _state.update { it.copy(share = ShareUi.Preparing(n + 1, total)) }
                                files.add(ShareFile(originals.download(item, File(dir, n.toString())), null, item.mime))
                            }
                        }
                    }
                }
                _state.update { it.copy(share = ShareUi.Idle) }
                if (files.isEmpty()) _state.update { it.copy(share = ShareUi.Failed) } else _shareReady.emit(files)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(share = ShareUi.Failed) }
            }
        }
    }

    private sealed interface Slot {
        class Local(val uri: String) : Slot
        class Remote(val id: Long) : Slot
    }

    /** Details of one photo: from the index for Nextcloud photos, from MediaStore for phone-only photos; says where copies are. */
    suspend fun details(id: Long): dev.bookah.photos.data.AssetDetails? {
        if (id < 0) return localById[-id]?.let { dev.bookah.photos.data.LocalDetails.from(it) }
        val file = store.currentFile() ?: return null
        val d = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.bookah.photos.data.DetailsReader.read(file, id) }
            ?: return extraFiles.firstOrNull { it.id == id }?.let { x ->
                val row = x.toRow()
                val taken = row.takenUtc.takeIf { row.day != 0 }
                val offset = taken?.let { java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochSecond(it)).totalSeconds / 60 }
                dev.bookah.photos.data.AssetDetails(
                    id, x.path, x.mime, x.size, null, null, null, taken, offset, false, null, null, null, null, null, null, null,
                    row.flags, notIndexed = true,
                )
            }
        val lib = _state.value.library
        val i = lib?.indexOfId(id)
        return if (lib != null && i != null && lib.hasLocalCopy(i)) d.copy(onPhone = true, folder = lib.folder[i]) else d
    }

    fun updatePrefs(p: dev.bookah.photos.data.SectionPrefs) {
        _state.update { it.copy(prefs = p) }
        viewModelScope.launch { prefsStore.save(p) }
    }

    private var listsJob: Job? = null

    /** Fetches favourites and albums from Nextcloud (read only) and keeps the answer in the cache; on failure the cached answer stays. */
    fun refreshLists() {
        val session = _state.value.session ?: return
        if (listsJob?.isActive == true) return
        listsJob = viewModelScope.launch {
            val api = dev.bookah.photos.data.NextcloudListsApi(http, session)
            try {
                val lists = dev.bookah.photos.data.NextcloudLists(api.favouriteIds(), api.albums(), System.currentTimeMillis())
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { listsCache.write(lists) }
                _state.update { it.copy(lists = lists) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: dev.bookah.photos.data.SnapshotSync.UnauthorizedException) {
                signOutInternal()
            } catch (e: Exception) {
                // offline or a hiccup: keep showing the cached lists
            }
        }
    }

    /** The WebDAV address of the original of a file (from the index or from the not-yet-indexed list), for thumbnails made from originals. */
    suspend fun originalUrlOf(fileId: Long): String? {
        val session = _state.value.session ?: return null
        val snapshot = store.currentFile()
        val path = snapshot?.let { f -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Reader.shareItems(f, longArrayOf(fileId)).firstOrNull()?.path } }
            ?: extraFiles.firstOrNull { it.id == fileId }?.path
        return path?.let { session.originalUrl(it) }
    }

    private var newFilesJob: Job? = null

    /** Asks Nextcloud which photos were uploaded after the index was made, so they show up (marked "not yet indexed") and are never "not backed up". */
    fun refreshNewFiles() {
        val session = _state.value.session ?: return
        val snapshot = store.currentFile() ?: return
        if (newFilesJob?.isActive == true) return
        newFilesJob = viewModelScope.launch {
            try {
                val meta = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.bookah.photos.data.SnapshotMetaReader.read(snapshot) }
                val created = meta.createdUtc ?: return@launch
                val known = (snapshotRows.asSequence() + hiddenSnapshotRows.asSequence()).mapTo(HashSet()) { it.id }          // a hidden photo is not a new one
                val found = dev.bookah.photos.data.NewFilesApi(http, session).since(created, meta.roots) { it in known }
                if (found != extraFiles) {
                    extraFiles = found
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { extraCache.write(found) }
                    rebuild()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // offline or a hiccup: the cached answer stays
            }
        }
    }

    fun shareMessageShown() = _state.update { if (it.share is ShareUi.Failed) it.copy(share = ShareUi.Idle) else it }

    fun startSignIn(address: String) {
        val server = normalizeServerAddress(address)
        if (server == null) {
            _state.update { it.copy(signIn = SignInUi.Error(SignInUi.ErrorKind.BadAddress)) }
            return
        }
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            _state.update { it.copy(signIn = SignInUi.Starting) }
            val flow = LoginFlowV2(http)
            val started = try {
                flow.start(server)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(signIn = SignInUi.Error(SignInUi.ErrorKind.Failed, e.message)) }
                return@launch
            }
            _state.update { it.copy(signIn = SignInUi.Waiting(started.loginUrl)) }
            when (val r = flow.awaitApproval(started)) {
                is LoginFlowV2.Result.Approved -> {
                    sessions.save(r.session)
                    _state.update { it.copy(session = r.session, signIn = SignInUi.Idle) }
                    sync()
                }
                LoginFlowV2.Result.TimedOut -> _state.update { it.copy(signIn = SignInUi.Error(SignInUi.ErrorKind.TimedOut)) }
                is LoginFlowV2.Result.Failed -> _state.update { it.copy(signIn = SignInUi.Error(SignInUi.ErrorKind.Failed, r.message)) }
            }
        }
    }

    /** The screen opened the browser page; do not open it again after a rotation. */
    fun loginPageOpened() = _state.update { s -> if (s.signIn is SignInUi.Waiting) s.copy(signIn = SignInUi.Waiting(null)) else s }

    fun cancelSignIn() {
        signInJob?.cancel()
        _state.update { it.copy(signIn = SignInUi.Idle) }
    }

    /** [overrideMobile]: the reader pressed "Download now" while on mobile data: this one time the big download is allowed. */
    fun sync(overrideMobile: Boolean = false) {
        val session = _state.value.session ?: return
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            _state.update { it.copy(sync = SyncUi.Checking) }
            val result = SnapshotSync(http, session, store).sync(allowBigDownload = overrideMobile || bigDownloadsAllowed()) { done, total ->
                if (total > 0) _state.update { it.copy(sync = SyncUi.Downloading((done * 100 / total).toInt())) }
            }
            if (result is SyncResult.SignedOut) {
                // the app password was revoked: forget it, keep nothing that needs it
                signOutInternal()
                _state.update { it.copy(sync = SyncUi.Done(result)) }
                return@launch
            }
            _state.update { it.copy(sync = SyncUi.Done(result), info = currentInfo() ?: it.info) }
            if (result is SyncResult.Updated) loadLibrary()
            if (result is SyncResult.Updated || result is SyncResult.UpToDate) { refreshLists(); refreshNewFiles(); search.ensureModelChecked(); favourites.flush() }
        }
    }

    private val settings = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    /** Wi-Fi (or any network that is not metered), or the reader said mobile data is fine. */
    fun bigDownloadsAllowed(): Boolean = _state.value.allowMobileData || !NetworkCheck.isMetered(getApplication())

    fun setAllowMobileData(on: Boolean) {
        settings.edit().putBoolean("mobile_data", on).apply()
        _state.update { it.copy(allowMobileData = on) }
        if (on) sync()                                                             // a download that was waiting for Wi-Fi may start now
    }

    fun signOut() {
        viewModelScope.launch {
            syncJob?.cancel()
            people.flushNow()                          // send the corrections that are still on the phone, while the sign-in still works
            signOutInternal(forgetPeople = true)
        }
    }

    /** Removes the sign-in and the snapshot (it holds places and dates of personal photos) from the phone. */
    private suspend fun signOutInternal(forgetPeople: Boolean = false) {
        sessions.clear()
        File(getApplication<Application>().filesDir, "snapshots").deleteRecursively()
        search.clearLibraryData()
        if (forgetPeople) { people.clear(); favourites.clear() } else people.reset()      // an expired sign-in keeps the unsent corrections for the next sign-in
        File(getApplication<Application>().cacheDir, "share").deleteRecursively()
        listsCache.clear()
        extraCache.clear()
        extraFiles = emptyList()
        File(getApplication<Application>().cacheDir, "thumbs").deleteRecursively()
        File(getApplication<Application>().cacheDir, "fallback-thumbs").deleteRecursively()
        _state.update { it.copy(session = null, info = null, library = null, duplicates = emptyMap(), hiddenLibrary = LibraryIndex.build(emptyList()), memories = dev.bookah.photos.data.MemoriesIndex.EMPTY, places = dev.bookah.photos.data.PlacesIndex.EMPTY, lists = null, signIn = SignInUi.Idle, sync = SyncUi.Idle) }
    }
}
