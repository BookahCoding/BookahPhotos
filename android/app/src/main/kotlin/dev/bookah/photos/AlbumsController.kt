package dev.bookah.photos

import dev.bookah.photos.data.Album
import dev.bookah.photos.data.AlbumsApi
import dev.bookah.photos.data.NextcloudLists
import dev.bookah.photos.data.Session
import dev.bookah.photos.data.SnapshotSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** What the reader is told after an album action. */
sealed interface AlbumsMessage {
    data class Added(val count: Int, val album: String, val skipped: Int) : AlbumsMessage
    data class Removed(val count: Int) : AlbumsMessage
    data class Created(val name: String) : AlbumsMessage
    data class Renamed(val name: String) : AlbumsMessage
    data class Deleted(val name: String) : AlbumsMessage
    data class NameTaken(val name: String) : AlbumsMessage
    /** Nextcloud could not be reached (or said no); [done] of the wanted changes were made before it stopped. */
    data class Failed(val done: Int) : AlbumsMessage
}

/** An album action that is running: how far it is. */
data class AlbumsBusy(val done: Int, val total: Int)

/**
 * Makes and changes Nextcloud Photos albums: create, rename, delete, add photos, take photos out. Each action talks to Nextcloud directly (there is no queue:
 * the reader sees "working" and then the result), then the list of albums is patched at once and read again from Nextcloud. Photos only on this phone (negative
 * ids) cannot be added: they are not in Nextcloud.
 */
class AlbumsController(
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val session: () -> Session?,
    private val pathsOf: suspend (LongArray) -> Map<Long, String>,
    private val roots: suspend () -> List<String>,
    private val patchLists: ((NextcloudLists) -> NextcloudLists) -> Unit,
    private val refresh: () -> Unit,
) {
    private val lock = Mutex()
    private val _busy = MutableStateFlow<AlbumsBusy?>(null)
    val busy: StateFlow<AlbumsBusy?> = _busy.asStateFlow()
    private val _message = MutableStateFlow<AlbumsMessage?>(null)
    val message: StateFlow<AlbumsMessage?> = _message.asStateFlow()

    fun clearMessage() { _message.value = null }

    /** Makes the album and, if photos are given, puts them in it. */
    fun create(name: String, thenAdd: LongArray? = null) = launchAction {
        val api = api() ?: return@launchAction
        when (api.create(name)) {
            AlbumsApi.Outcome.Done -> {
                val n = AlbumsApi.validName(name) ?: name
                patchLists { l -> l.copy(albums = l.albums + Album(n, 0, null, emptyList())) }
                _message.value = AlbumsMessage.Created(n)
                if (thenAdd != null && thenAdd.isNotEmpty()) addNow(api, n, thenAdd)
            }
            AlbumsApi.Outcome.NameTaken -> _message.value = AlbumsMessage.NameTaken(name.trim())
            is AlbumsApi.Outcome.Failed, is AlbumsApi.Outcome.Refused, AlbumsApi.Outcome.Missing, AlbumsApi.Outcome.AlreadyThere -> _message.value = AlbumsMessage.Failed(0)
        }
    }

    fun rename(old: String, new: String) = launchAction {
        val api = api() ?: return@launchAction
        when (api.rename(old, new)) {
            AlbumsApi.Outcome.Done -> { val n = AlbumsApi.validName(new) ?: new; patchLists { l -> l.copy(albums = l.albums.map { if (it.name == old) it.copy(name = n) else it }) }; _message.value = AlbumsMessage.Renamed(n) }
            AlbumsApi.Outcome.NameTaken -> _message.value = AlbumsMessage.NameTaken(new.trim())
            else -> _message.value = AlbumsMessage.Failed(0)
        }
    }

    fun delete(name: String) = launchAction {
        val api = api() ?: return@launchAction
        when (api.delete(name)) {
            AlbumsApi.Outcome.Done, AlbumsApi.Outcome.Missing -> { patchLists { l -> l.copy(albums = l.albums.filter { it.name != name }) }; _message.value = AlbumsMessage.Deleted(name) }
            else -> _message.value = AlbumsMessage.Failed(0)
        }
    }

    fun add(album: String, ids: LongArray) = launchAction {
        val api = api() ?: return@launchAction
        addNow(api, album, ids)
    }

    fun remove(album: String, ids: LongArray) = launchAction {
        val api = api() ?: return@launchAction
        val real = ids.filter { it > 0 }
        var done = 0
        val removed = ArrayList<Long>()
        _busy.value = AlbumsBusy(0, real.size)
        for (id in real) {
            when (api.remove(album, id)) {
                AlbumsApi.Outcome.Done -> { done++; removed.add(id) }
                else -> break
            }
            _busy.value = AlbumsBusy(done, real.size)
        }
        patchLists { l -> l.copy(albums = l.albums.map { a -> if (a.name == album) a.copy(itemIds = a.itemIds - removed.toSet(), count = (a.count - removed.size).coerceAtLeast(0)) else a }) }
        _message.value = if (done == real.size) AlbumsMessage.Removed(done) else AlbumsMessage.Failed(done)
    }

    private suspend fun addNow(api: AlbumsApi, album: String, ids: LongArray) {
        val real = ids.filter { it > 0 }
        val skipped = ids.size - real.size
        val paths = pathsOf(real.toLongArray())
        var done = 0
        val added = ArrayList<Long>()
        _busy.value = AlbumsBusy(0, real.size)
        for (id in real) {
            val path = paths[id] ?: continue
            when (api.add(album, path)) {
                AlbumsApi.Outcome.Done, AlbumsApi.Outcome.AlreadyThere -> { done++; added.add(id) }
                else -> break
            }
            _busy.value = AlbumsBusy(done, real.size)
        }
        patchLists { l ->
            l.copy(albums = l.albums.map { a ->
                if (a.name == album) { val items = (a.itemIds + added).distinct(); a.copy(itemIds = items, count = items.size, coverId = added.lastOrNull() ?: a.coverId) } else a
            })
        }
        _message.value = if (done == real.size) AlbumsMessage.Added(done, album, skipped) else AlbumsMessage.Failed(done)
    }

    private suspend fun api(): AlbumsApi? {
        val s = session() ?: return null
        return AlbumsApi(http, s, roots())
    }

    private fun launchAction(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                _busy.value = AlbumsBusy(0, 0)
                try {
                    block()
                } catch (e: SnapshotSync.UnauthorizedException) {
                    _message.value = AlbumsMessage.Failed(0)
                } catch (e: Exception) {
                    _message.value = AlbumsMessage.Failed(0)
                } finally {
                    _busy.value = null
                    refresh()                                                      // read the albums from Nextcloud again, so the phone shows what the server has
                }
            }
        }
    }
}
