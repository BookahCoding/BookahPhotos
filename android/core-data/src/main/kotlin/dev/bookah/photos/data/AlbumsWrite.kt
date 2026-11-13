package dev.bookah.photos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLDecoder

/**
 * Makes and changes the albums of the Nextcloud Photos app (the same albums Nextcloud's web Photos and mobile app show), through its WebDAV interface:
 * an album is a folder under `remote.php/dav/photos/<user>/albums`, a photo is added to it by copying it in (the original is never moved or changed: the
 * album holds a reference), a photo is taken out by deleting its entry in the album, an album is renamed by moving it. Nothing here can delete a photo:
 * the only deletes are of an album's own entries and of an album.
 */
class AlbumsApi(private val http: OkHttpClient, private val session: Session, private val allowedRoots: List<String>) {
    sealed interface Outcome {
        data object Done : Outcome
        /** The photo is in the album already (nothing to do). */
        data object AlreadyThere : Outcome
        /** An album with that name exists already. */
        data object NameTaken : Outcome
        data object Missing : Outcome
        data class Refused(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private fun albumsUrl(): HttpUrl = session.server.toHttpUrl().newBuilder().addPathSegments("remote.php/dav/photos").addPathSegment(session.user).addPathSegment("albums").build()
    private fun albumUrl(name: String): HttpUrl = albumsUrl().newBuilder().addPathSegment(name).build()

    suspend fun create(name: String): Outcome = run {
        val n = validName(name) ?: return@run Outcome.Refused("that is not a usable album name")
        call("MKCOL", albumUrl(n)) { code, _ -> when { code == 201 -> Outcome.Done; code == 405 || code == 409 -> Outcome.NameTaken; else -> Outcome.Failed("Nextcloud answered $code") } }
    }

    suspend fun rename(old: String, new: String): Outcome = run {
        val n = validName(new) ?: return@run Outcome.Refused("that is not a usable album name")
        val o = validName(old) ?: return@run Outcome.Refused("that is not a usable album name")
        if (n == o) return@run Outcome.Done
        call("MOVE", albumUrl(o), headers = mapOf("Destination" to albumUrl(n).toString(), "Overwrite" to "F")) { code, _ ->
            when { code == 201 || code == 204 -> Outcome.Done; code == 412 || code == 409 -> Outcome.NameTaken; code == 404 -> Outcome.Missing; else -> Outcome.Failed("Nextcloud answered $code") }
        }
    }

    /** Deletes the album (its entries and the album itself). The photos stay where they are. */
    suspend fun delete(name: String): Outcome = run {
        val n = validName(name) ?: return@run Outcome.Refused("that is not a usable album name")
        call("DELETE", albumUrl(n)) { code, _ -> when { code == 204 || code == 200 -> Outcome.Done; code == 404 -> Outcome.Missing; else -> Outcome.Failed("Nextcloud answered $code") } }
    }

    /** Adds the photo at [path] (inside the photo folders) to the album. */
    suspend fun add(album: String, path: String): Outcome = run {
        val n = validName(album) ?: return@run Outcome.Refused("that is not a usable album name")
        if (!pathAllowed(path)) return@run Outcome.Refused("not inside the photo folders")
        val target = albumUrl(n).newBuilder().addPathSegment(path.substringAfterLast('/')).build()
        call("COPY", session.originalUrl(path).toHttpUrl(), headers = mapOf("Destination" to target.toString())) { code, _ ->
            when { code == 201 || code == 204 -> Outcome.Done; code == 409 || code == 412 -> Outcome.AlreadyThere; code == 404 -> Outcome.Missing; else -> Outcome.Failed("Nextcloud answered $code") }
        }
    }

    /** Takes the photo with this Nextcloud file id out of the album (only its entry in the album is deleted). */
    suspend fun remove(album: String, fileId: Long): Outcome = run {
        val n = validName(album) ?: return@run Outcome.Refused("that is not a usable album name")
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><oc:fileid/></d:prop></d:propfind>"""
        var entry: String? = null
        val listed = call("PROPFIND", albumUrl(n), headers = mapOf("Depth" to "1"), body = body) { code, text ->
            if (code == 404) return@call Outcome.Missing
            if (code != 207) return@call Outcome.Failed("Nextcloud answered $code")
            entry = memberHref(text, fileId)
            Outcome.Done
        }
        if (listed != Outcome.Done) return@run listed
        val href = entry ?: return@run Outcome.Done                                       // not in the album any more
        val url = session.server.toHttpUrl().newBuilder().encodedPath(href).build()
        call("DELETE", url) { code, _ -> when { code == 204 || code == 200 -> Outcome.Done; code == 404 -> Outcome.Done; else -> Outcome.Failed("Nextcloud answered $code") } }
    }

    private suspend fun run(block: suspend () -> Outcome): Outcome = try {
        block()
    } catch (e: SnapshotSync.UnauthorizedException) {
        throw e
    } catch (e: IOException) {
        Outcome.Failed(e.message ?: "network error")
    }

    private suspend fun call(method: String, url: HttpUrl, headers: Map<String, String> = emptyMap(), body: String? = null, handle: (Int, String) -> Outcome): Outcome = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Authorization", session.authHeader).apply { headers.forEach { (k, v) -> header(k, v) } }
            .method(method, body?.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw SnapshotSync.UnauthorizedException()
            handle(resp.code, if (method == "PROPFIND") resp.body.string() else "")
        }
    }

    private fun pathAllowed(path: String): Boolean {
        val parts = path.split('/')
        if (parts.any { it == ".." || it == "." } || path.startsWith("/") || path.contains("//") || parts.last().isEmpty()) return false
        return allowedRoots.any { root -> path.startsWith(root.trim('/') + "/") }
    }

    companion object {
        /** The name as it will be used, or null: not empty, at most 100 characters, no slash, nothing that is only dots. Spaces at the ends are removed. */
        fun validName(name: String): String? {
            val n = name.trim()
            if (n.isEmpty() || n.length > 100 || n.contains('/') || n.contains('\\') || n.all { it == '.' } || n.any { it.isISOControl() }) return null
            return n
        }

        /** The address (path) of the album entry that holds the photo with [fileId], from a PROPFIND answer; null if it is not in the album. */
        fun memberHref(xml: String, fileId: Long): String? = Regex("<d:response>(.*?)</d:response>", RegexOption.DOT_MATCHES_ALL).findAll(xml).firstNotNullOfOrNull { m ->
            val block = m.groupValues[1]
            val href = Regex("<d:href>([^<]*)</d:href>").find(block)?.groupValues?.get(1) ?: return@firstNotNullOfOrNull null
            val id = Regex("<oc:fileid>\\s*(\\d+)\\s*</oc:fileid>").find(block)?.groupValues?.get(1)?.toLongOrNull()
            if (!href.endsWith("/") && id == fileId) href else null
        }

        /** Decoded last part of an address (for tests and messages). */
        fun lastPart(href: String): String = URLDecoder.decode(href.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
    }
}
