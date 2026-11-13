package dev.bookah.photos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Sends the reader's corrections to Nextcloud as one new file per batch: `BookahPhotos/ops/<device>/<UTC time>-<random>.jsonl`. The indexer on BookahPC reads
 * and applies them (docs/ops-format.md). A file is never changed after it is written: the upload says "only if it does not exist yet" (a clash is answered
 * with 412 and a new name is tried). The app writes ONLY below `BookahPhotos/ops/<device>/` (never `applied/`, which belongs to the indexer).
 * Uploading the same op twice does no harm: its id is remembered by the indexer.
 */
class OpsUploader(
    private val http: OkHttpClient, private val session: Session, device: String,
    private val now: () -> Instant = Instant::now, private val random: () -> String = { java.util.UUID.randomUUID().toString().take(6) },
) {
    val device: String = sanitizeDevice(device)

    sealed interface Outcome {
        data object Nothing : Outcome
        data class Sent(val ids: Set<String>, val file: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun isAllowed(path: String): Boolean {
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        return path.startsWith("$OPS_DIR/$device/") && parts.size == 4 && parts[3].endsWith(".jsonl") && device != "applied"
    }

    suspend fun upload(ops: List<PendingOp>): Outcome = withContext(Dispatchers.IO) {
        val todo = ops.filter { !it.uploaded }
        if (todo.isEmpty()) return@withContext Outcome.Nothing
        try {
            mkcol(OPS_DIR)
            mkcol("$OPS_DIR/$device")
            val body = (todo.joinToString("\n") { it.line } + "\n").toRequestBody("application/x-ndjson".toMediaType())
            repeat(3) {
                val path = "$OPS_DIR/$device/${fileName()}"
                check(isAllowed(path)) { "refused path $path" }
                val req = Request.Builder().url(session.originalUrl(path)).header("Authorization", session.authHeader).header("If-None-Match", "*").put(body).build()
                http.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 401 -> throw SnapshotSync.UnauthorizedException()
                        resp.isSuccessful -> return@withContext Outcome.Sent(todo.map { it.opId }.toSet(), path)
                        resp.code == 412 -> Unit                       // the name is taken: try another
                        else -> return@withContext Outcome.Failed("Nextcloud answered ${resp.code}")
                    }
                }
            }
            Outcome.Failed("no free file name")
        } catch (e: SnapshotSync.UnauthorizedException) {
            throw e
        } catch (e: IOException) {
            Outcome.Failed(e.message ?: "network error")
        }
    }

    /** Creates a folder; "already there" (405) is fine. */
    private fun mkcol(path: String) {
        val req = Request.Builder().url(session.originalUrl(path)).header("Authorization", session.authHeader).method("MKCOL", null).build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw SnapshotSync.UnauthorizedException()
            if (!(resp.isSuccessful || resp.code == 405)) throw IOException("Nextcloud answered ${resp.code} for the folder")
        }
    }

    private fun fileName() = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC).format(now()) + "-" + random() + ".jsonl"

    companion object {
        const val OPS_DIR = "BookahPhotos/ops"

        /** A folder name from the phone model: "Xiaomi 15T" becomes "xiaomi-15t". */
        fun sanitizeDevice(model: String): String {
            val s = model.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
            return if (s.isEmpty() || s == "applied") "phone" else s
        }
    }
}
