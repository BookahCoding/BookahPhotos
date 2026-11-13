package dev.bookah.photos.data

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * A file that was uploaded to Nextcloud after the index was made. It is shown in the library (marked "not yet indexed": no people,
 * places or search yet) and it makes sure a photo that is already in Nextcloud is never called "not backed up".
 */
@Serializable
data class ExtraFile(val id: Long, val path: String, val size: Long, val mime: String, val uploadedAtSeconds: Long) {
    val name: String get() = path.substringAfterLast('/')

    /** The library row for it. The date is the one in the file name, or unknown: never a guess from file times. */
    fun toRow(zone: ZoneId = ZoneId.systemDefault()): AssetRow {
        val taken = DateFromName.parse(name)?.atZone(zone)
        val flags = (if (mime.startsWith("video/")) LibraryIndex.FLAG_VIDEO else 0) or
            (if (Regex("screenshot|screen[ _-]?shot|στιγμιότυπο", RegexOption.IGNORE_CASE).containsMatchIn(name)) LibraryIndex.FLAG_SCREENSHOT else 0)
        val day = taken?.let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth } ?: 0
        return AssetRow(id, day, taken?.toEpochSecond() ?: 0L, flags, null, uploadedAtSeconds.hashCode(), false, 0, null, null, true, name, size)
    }
}

/** What the index says about itself: when it was made and which Nextcloud folders it covers. */
class SnapshotMeta(val createdUtc: Instant?, val roots: List<String>)

object SnapshotMetaReader {
    fun read(file: File): SnapshotMeta = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            fun value(key: String): String? = db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
            SnapshotMeta(
                createdUtc = value("created_utc")?.let { runCatching { Instant.parse(it.replace("+00:00", "Z")) }.getOrNull() },
                roots = value("roots")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty().ifEmpty { listOf("Photos", "InstantUpload") },
            )
        }
    }.getOrDefault(SnapshotMeta(null, listOf("Photos", "InstantUpload")))
}

/**
 * Asks Nextcloud (a DAV SEARCH, read only) for the photos and videos that were uploaded after the index was made. Checked on the real
 * server: `nc:upload_time` greater than an ISO time returns exactly the new uploads, even when the photo's own file time is old.
 */
class NewFilesApi(private val http: OkHttpClient, private val session: Session) {
    private val xml = "text/xml; charset=utf-8".toMediaType()

    suspend fun since(createdUtc: Instant, roots: List<String>, knownIds: (Long) -> Boolean): List<ExtraFile> = withContext(Dispatchers.IO) {
        // a margin, because the index is written at the end of a run and files can arrive while it runs; known ids are dropped below
        val from = createdUtc.minus(Duration.ofHours(2))
        roots.flatMap { root -> parse(search(root, from), root) }.filter { !knownIds(it.id) }.distinctBy { it.id }
    }

    private fun search(root: String, from: Instant): String {
        val body = """<?xml version="1.0" encoding="UTF-8"?>
<d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"><d:basicsearch>
<d:select><d:prop><oc:fileid/><d:getcontenttype/><d:getcontentlength/><nc:upload_time/></d:prop></d:select>
<d:from><d:scope><d:href>/files/${session.user}/${root.trim('/')}</d:href><d:depth>infinity</d:depth></d:scope></d:from>
<d:where><d:gt><d:prop><nc:upload_time/></d:prop><d:literal>$from</d:literal></d:gt></d:where>
<d:orderby/><d:limit><d:nresults>500</d:nresults></d:limit></d:basicsearch></d:searchrequest>"""
        val url = session.server.toHttpUrl().newBuilder().addPathSegments("remote.php/dav/").build()
        val req = Request.Builder().url(url).header("Authorization", session.authHeader).method("SEARCH", body.toRequestBody(xml)).build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw SnapshotSync.UnauthorizedException()
            if (resp.code != 207 && !resp.isSuccessful) throw IOException("Nextcloud answered ${resp.code}")
            return resp.body.string()
        }
    }

    companion object {
        private val RESPONSE = Regex("<d:response>(.*?)</d:response>", RegexOption.DOT_MATCHES_ALL)

        /** Photos and videos of a SEARCH answer. [root] is only used to tell where the files path starts. */
        fun parse(xml: String, root: String): List<ExtraFile> = RESPONSE.findAll(xml).mapNotNull { m ->
            val block = m.groupValues[1]
            fun tag(t: String) = Regex("<$t>\\s*([^<]*?)\\s*</$t>").find(block)?.groupValues?.get(1)
            val href = tag("d:href") ?: return@mapNotNull null
            val mime = tag("d:getcontenttype") ?: return@mapNotNull null
            if (!(mime.startsWith("image/") || mime.startsWith("video/"))) return@mapNotNull null
            val id = tag("oc:fileid")?.toLongOrNull() ?: return@mapNotNull null
            val decoded = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return@mapNotNull null
            val at = decoded.indexOf("/" + root.trim('/') + "/")
            if (at < 0) return@mapNotNull null
            ExtraFile(id, decoded.substring(at + 1), tag("d:getcontentlength")?.toLongOrNull() ?: -1L, mime, tag("nc:upload_time")?.toLongOrNull() ?: 0L)
        }.toList()
    }
}

/** The last answer, kept as a small JSON file so the library stays right without a connection. */
class ExtraFilesCache(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    fun read(): List<ExtraFile> = runCatching { json.decodeFromString<List<ExtraFile>>(file.readText()) }.getOrDefault(emptyList())
    fun write(list: List<ExtraFile>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ExtraFile.serializer()), list))
        tmp.renameTo(file)
    }
    fun clear() { file.delete() }
}
