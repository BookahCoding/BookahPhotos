package dev.bookah.photos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Moves a file of the Nextcloud photo folders to the Nextcloud trash (a WebDAV DELETE: Nextcloud keeps deleted files in its trash bin, where
 * they can be restored; this app never empties it). Only used after the reader has confirmed in a dialog that names how many files go.
 *
 * Safety: only paths inside the photo roots of the index are accepted, never anything with ".." or an empty name, so a damaged index
 * can not make the app delete something elsewhere in the account.
 */
class NextcloudTrash(private val http: OkHttpClient, private val session: Session, private val allowedRoots: List<String>) {

    sealed interface Outcome {
        data object Deleted : Outcome
        /** The file was already gone. */
        data object AlreadyGone : Outcome
        data class Refused(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun isAllowed(path: String): Boolean {
        val parts = path.split('/')
        if (parts.any { it == ".." || it == "." } || path.startsWith("/") || path.contains("//") || parts.last().isEmpty()) return false
        return allowedRoots.any { root -> path.startsWith(root.trim('/') + "/") }
    }

    suspend fun moveToTrash(path: String): Outcome = withContext(Dispatchers.IO) {
        if (!isAllowed(path)) return@withContext Outcome.Refused("not inside the photo folders")
        val req = Request.Builder().url(session.originalUrl(path)).header("Authorization", session.authHeader).delete().build()
        try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 -> throw SnapshotSync.UnauthorizedException()
                    resp.code == 404 -> Outcome.AlreadyGone
                    resp.isSuccessful -> Outcome.Deleted
                    else -> Outcome.Failed("Nextcloud answered ${resp.code}")
                }
            }
        } catch (e: IOException) {
            if (e is SnapshotSync.UnauthorizedException) throw e
            Outcome.Failed(e.message ?: "network error")
        }
    }
}

/** What a delete would touch, for the confirmation dialog. */
data class DeletePlan(val total: Int, val onPhone: Int, val inNextcloud: Int, val bothSides: Int) {
    val phoneOnly: Int get() = onPhone - bothSides
    val nextcloudOnly: Int get() = inNextcloud - bothSides

    companion object {
        fun of(lib: LibraryIndex, ids: LongArray): DeletePlan {
            var onPhone = 0; var inNc = 0; var both = 0; var total = 0
            for (id in ids) {
                val i = lib.indexOfId(id) ?: continue
                total++
                val phone = lib.hasLocalCopy(i)
                val nc = lib.ids[i] > 0
                if (phone) onPhone++
                if (nc) inNc++
                if (phone && nc) both++
            }
            return DeletePlan(total, onPhone, inNc, both)
        }
    }
}
