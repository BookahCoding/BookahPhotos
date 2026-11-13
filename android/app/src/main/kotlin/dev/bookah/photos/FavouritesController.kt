package dev.bookah.photos

import android.app.Application
import dev.bookah.photos.data.FavouritesApi
import dev.bookah.photos.data.PendingFavourite
import dev.bookah.photos.data.PendingFavouritesStore
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
import java.io.File

/**
 * Favourites the reader sets or clears. Each change is saved on the phone first and shown at once, then sent to Nextcloud (the file's favourite flag);
 * if there is no connection it waits and is sent later (when the app starts, after a refresh, or on the next change). Photos that exist only on this phone
 * (negative ids) are not in Nextcloud and cannot be favourites there.
 */
class FavouritesController(
    app: Application,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val session: () -> Session?,
    private val pathsOf: suspend (LongArray) -> Map<Long, String>,
    private val roots: suspend () -> List<String>,
    private val onConfirmed: () -> Unit,
) {
    private val store = PendingFavouritesStore(File(app.filesDir, "favourites-pending.txt"))
    private val flushLock = Mutex()
    private val _pending = MutableStateFlow<List<PendingFavourite>>(emptyList())
    /** Changes Nextcloud has not confirmed yet; shown on top of Nextcloud's own list. */
    val pending: StateFlow<List<PendingFavourite>> = _pending.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            _pending.value = store.all()
            flushNow()
        }
    }

    fun set(ids: LongArray, on: Boolean) {
        scope.launch(Dispatchers.IO) {
            val real = ids.filter { it > 0 }
            val paths = pathsOf(real.toLongArray())
            store.set(real.mapNotNull { id -> paths[id]?.let { PendingFavourite(id, it, on) } })
            _pending.value = store.all()
            flushNow()
        }
    }

    fun flush() {
        scope.launch(Dispatchers.IO) { flushNow() }
    }

    private suspend fun flushNow() = flushLock.withLock {
        val s = session() ?: return@withLock
        val items = store.all()
        if (items.isEmpty()) return@withLock
        val api = FavouritesApi(http, s, roots())
        val finished = ArrayList<PendingFavourite>()
        try {
            for (item in items) {
                when (api.set(item.path, item.on)) {
                    is FavouritesApi.Outcome.Done, FavouritesApi.Outcome.Gone, is FavouritesApi.Outcome.Refused -> finished.add(item)
                    is FavouritesApi.Outcome.Failed -> break                      // no connection or a server hiccup: keep the rest for later
                }
            }
        } catch (e: SnapshotSync.UnauthorizedException) {
            // the sign-in ended: the changes stay on the phone for the next sign-in
        }
        if (finished.isNotEmpty()) {
            store.remove(finished)
            _pending.value = store.all()
            onConfirmed()                                                          // read Nextcloud's list again so the phone shows what the server has
        }
    }

    /** Sign-out: forget unsent changes (they belong to this account). */
    suspend fun clear() {
        store.clear()
        _pending.value = emptyList()
    }
}
