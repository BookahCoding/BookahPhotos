package dev.bookah.photos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLDecoder

/** A Nextcloud photo album: its name, how many items it holds, the file id of its newest photo (used as the cover) and its items. */
@Serializable
data class Album(val name: String, val count: Int, val coverId: Long?, val itemIds: List<Long>)

/** What Collections needs from Nextcloud: favourite files and albums. Read only, and cached on the phone so it also shows offline. */
@Serializable
data class NextcloudLists(val favouriteIds: List<Long> = emptyList(), val albums: List<Album> = emptyList(), val fetchedAtMs: Long = 0)

class NextcloudListsApi(private val http: OkHttpClient, private val session: Session) {

    private val xml = "application/xml; charset=utf-8".toMediaType()

    /** Favourite files: a REPORT with the favourite filter on the user's files (as recorded in Phase 0). Only read, nothing is changed. */
    suspend fun favouriteIds(): List<Long> = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0"?><oc:filter-files xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><oc:fileid/></d:prop><oc:filter-rules><oc:favorite>1</oc:favorite></oc:filter-rules></oc:filter-files>"""
        val url = session.davRoot().newBuilder().build()
        parseFileIds(send("REPORT", url, body, depth = null))
    }

    /** Albums of the Nextcloud Photos app: the list, then the members of each one. */
    suspend fun albums(): List<Album> = withContext(Dispatchers.IO) {
        val base = photosDav("albums")
        val listBody = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:nc="http://nextcloud.org/ns"><d:prop><nc:nbItems/><nc:last-photo/></d:prop></d:propfind>"""
        val list = parseAlbumList(send("PROPFIND", base, listBody, depth = "1"))
        list.map { entry ->
            val url = base.newBuilder().addPathSegment(entry.name).build()
            val itemsBody = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><oc:fileid/></d:prop></d:propfind>"""
            val ids = parseMemberIds(send("PROPFIND", url, itemsBody, depth = "1"))
            Album(entry.name, if (entry.count > 0) entry.count else ids.size, entry.coverId, ids)
        }
    }

    private fun photosDav(kind: String): HttpUrl = session.server.toHttpUrl().newBuilder()
        .addPathSegments("remote.php/dav/photos").addPathSegment(session.user).addPathSegment(kind).build()

    private fun send(method: String, url: HttpUrl, body: String, depth: String?): String {
        val req = Request.Builder().url(url).header("Authorization", session.authHeader).apply { depth?.let { header("Depth", it) } }
            .method(method, body.toRequestBody(xml)).build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw SnapshotSync.UnauthorizedException()
            if (resp.code != 207 && !resp.isSuccessful) throw IOException("Nextcloud answered ${resp.code}")
            return resp.body.string()
        }
    }

    internal data class AlbumEntry(val name: String, val count: Int, val coverId: Long?)

    companion object {
        private val FILEID = Regex("<oc:fileid>\\s*(\\d+)\\s*</oc:fileid>")
        private val RESPONSE = Regex("<d:response>(.*?)</d:response>", RegexOption.DOT_MATCHES_ALL)

        /** File ids of the members of a folder answer; the folder itself (its address ends with a slash) is skipped. */
        fun parseMemberIds(xml: String): List<Long> = RESPONSE.findAll(xml).mapNotNull { m ->
            val block = m.groupValues[1]
            val href = Regex("<d:href>([^<]*)</d:href>").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            if (href.endsWith("/")) null else FILEID.find(block)?.groupValues?.get(1)?.toLongOrNull()
        }.toList()

        fun parseFileIds(xml: String): List<Long> = FILEID.findAll(xml).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()

        /** The album collections of a PROPFIND answer (the folder itself is skipped). */
        internal fun parseAlbumList(xml: String): List<AlbumEntry> = RESPONSE.findAll(xml).mapNotNull { m ->
            val block = m.groupValues[1]
            val href = Regex("<d:href>([^<]*)</d:href>").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val trimmed = href.trimEnd('/')
            if (!trimmed.contains("/albums/")) return@mapNotNull null        // the "albums" folder itself
            val name = runCatching { URLDecoder.decode(trimmed.substringAfterLast('/').replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return@mapNotNull null
            val count = Regex("<nc:nbItems>\\s*(\\d+)\\s*</nc:nbItems>").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val cover = Regex("<nc:last-photo>\\s*(\\d+)\\s*</nc:last-photo>").find(block)?.groupValues?.get(1)?.toLongOrNull()
            AlbumEntry(name, count, cover)
        }.toList()
    }
}

/** The last answer from Nextcloud, kept on the phone as a small JSON file so favourites and albums also show without a connection. */
class ListsCache(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    fun read(): NextcloudLists? = runCatching { json.decodeFromString<NextcloudLists>(file.readText()) }.getOrNull()
    fun write(lists: NextcloudLists) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(NextcloudLists.serializer(), lists))
        tmp.renameTo(file)
    }
    fun clear() { file.delete() }
}
