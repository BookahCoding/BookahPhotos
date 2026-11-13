package dev.bookah.photos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * Sets or clears the favourite flag of a photo in Nextcloud (the `oc:favorite` property of the file, a WebDAV PROPPATCH): the same flag Nextcloud's own apps
 * and Memories use. It changes nothing else: no photo is moved, renamed or deleted. Only paths inside the photo folders are accepted.
 */
class FavouritesApi(private val http: OkHttpClient, private val session: Session, private val allowedRoots: List<String>) {
    sealed interface Outcome {
        data object Done : Outcome
        /** The file is gone from Nextcloud: nothing to do any more. */
        data object Gone : Outcome
        data class Refused(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun isAllowed(path: String): Boolean {
        val parts = path.split('/')
        if (parts.any { it == ".." || it == "." } || path.startsWith("/") || path.contains("//") || parts.last().isEmpty()) return false
        return allowedRoots.any { root -> path.startsWith(root.trim('/') + "/") }
    }

    suspend fun set(path: String, favourite: Boolean): Outcome = withContext(Dispatchers.IO) {
        if (!isAllowed(path)) return@withContext Outcome.Refused("not inside the photo folders")
        val body = """<?xml version="1.0"?><d:propertyupdate xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:set><d:prop><oc:favorite>${if (favourite) 1 else 0}</oc:favorite></d:prop></d:set></d:propertyupdate>"""
        val req = Request.Builder().url(session.originalUrl(path)).header("Authorization", session.authHeader)
            .method("PROPPATCH", body.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
        try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 -> throw SnapshotSync.UnauthorizedException()
                    resp.code == 404 -> Outcome.Gone
                    resp.code == 207 && resp.body.string().contains("200 OK") -> Outcome.Done
                    else -> Outcome.Failed("Nextcloud answered ${resp.code}")
                }
            }
        } catch (e: IOException) {
            if (e is SnapshotSync.UnauthorizedException) throw e
            Outcome.Failed(e.message ?: "network error")
        }
    }
}

/** A favourite change that is not confirmed by Nextcloud yet. */
data class PendingFavourite(val id: Long, val path: String, val on: Boolean)

/**
 * The reader's favourite changes kept on the phone until Nextcloud has them, so they show at once and survive being offline. One line per photo, the latest
 * wish wins: `id|1 or 0|path`.
 */
class PendingFavouritesStore(private val file: File) {
    private val lock = Mutex()

    suspend fun all(): List<PendingFavourite> = lock.withLock { read() }

    suspend fun set(items: List<PendingFavourite>) = lock.withLock {
        val byId = LinkedHashMap<Long, PendingFavourite>()
        read().forEach { byId[it.id] = it }
        items.forEach { byId[it.id] = it }
        write(byId.values.toList())
    }

    /** Forgets what was sent, but only when nothing newer was asked for the same photo meanwhile. */
    suspend fun remove(done: List<PendingFavourite>) = lock.withLock {
        val now = read().associateBy { it.id }
        write(now.values.filterNot { cur -> done.any { it.id == cur.id && it.on == cur.on } })
    }

    suspend fun clear() = lock.withLock { file.delete(); Unit }

    private fun write(items: List<PendingFavourite>) {
        if (items.isEmpty()) { file.delete(); return }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(items.joinToString("\n") { "${it.id}|${if (it.on) 1 else 0}|${it.path}" } + "\n")
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun read(): List<PendingFavourite> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { l ->
            val p = l.split('|', limit = 3)
            val id = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val on = when (p.getOrNull(1)) { "1" -> true; "0" -> false; else -> return@mapNotNull null }
            val path = p.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PendingFavourite(id, path, on)
        }
    }
}

object FavouritesOverlay {
    /** Nextcloud's favourites with the reader's unconfirmed changes on top. */
    fun effective(server: Collection<Long>, pending: Collection<PendingFavourite>): Set<Long> {
        val out = HashSet(server)
        for (p in pending) if (p.on) out.add(p.id) else out.remove(p.id)
        return out
    }

    /** What the Select bar's heart should do for these photos: favourite them all, unless they all are already (then clear). Phone-only photos (negative ids) are left out. */
    fun wantedState(ids: LongArray, favourites: Set<Long>): Boolean {
        val real = ids.filter { it > 0 }
        return real.isNotEmpty() && !real.all { it in favourites }
    }
}
