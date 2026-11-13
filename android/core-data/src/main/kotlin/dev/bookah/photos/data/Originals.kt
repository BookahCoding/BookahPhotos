package dev.bookah.photos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Downloads originals over WebDAV into a folder (used for sharing). The user's own app password reads files; nothing is written to Nextcloud. */
class Originals(private val http: OkHttpClient, private val session: Session) {

    private fun urlFor(path: String) = session.davRoot().newBuilder().apply { path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }.build()

    /** Downloads [item] into [dir] and returns the file. The name is the file's own name (made safe), so the receiving app shows something sensible. */
    suspend fun download(item: ShareItem, dir: File, onBytes: (Long) -> Unit = {}): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val name = safeName(item.path.substringAfterLast('/'), item.id)
        val target = File(dir, name)
        val tmp = File(dir, "$name.part")
        val req = Request.Builder().url(urlFor(item.path)).header("Authorization", session.authHeader).build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Nextcloud answered ${resp.code}")
                var total = 0L
                tmp.outputStream().use { out ->
                    resp.body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); total += n; onBytes(total) }
                    }
                }
            }
            if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) throw IOException("could not store the file") }
            target
        } finally {
            tmp.delete()
        }
    }

    companion object {
        /** A file name without path parts or characters Android file systems dislike; falls back to the file id. */
        fun safeName(raw: String, id: Long): String {
            val cleaned = raw.replace(Regex("[\\/:*?\"<>|\u0000-\u001f]"), "_").trim().trim('.')
            return if (cleaned.isEmpty() || cleaned.length > 150) "photo-$id" + (raw.substringAfterLast('.', "").takeIf { it.length in 1..5 }?.let { ".$it" } ?: "") else cleaned
        }
    }
}
