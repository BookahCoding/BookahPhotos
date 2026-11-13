package dev.bookah.photos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Result of one check for a newer snapshot. */
sealed interface SyncResult {
    data class UpToDate(val generation: Long) : SyncResult
    data class Updated(val generation: Long) : SyncResult
    /** The index is made by a newer schema major version than this app reads. */
    data class AppTooOld(val schemaMajor: Int) : SyncResult
    /** Nextcloud was not reachable (or answered something unexpected); [haveSnapshot] says whether an older snapshot keeps the app usable. */
    data class Offline(val haveSnapshot: Boolean, val reason: String) : SyncResult
    /** The signed-in app password was refused (revoked in Nextcloud). */
    data object SignedOut : SyncResult
    data object NoIndexYet : SyncResult
    /** A newer index exists but the phone is on mobile data and the reader has not allowed big downloads there. [sizeBytes] is what it would download. */
    data class WaitingForWifi(val generation: Long, val sizeBytes: Long) : SyncResult
}

/** Checks `BookahPhotos/index/manifest.json` and downloads a newer snapshot when the generation changed. */
class SnapshotSync(private val http: OkHttpClient, private val session: Session, private val store: SnapshotStore) {

    private fun indexUrl(name: String) = session.davRoot().newBuilder()
        .addPathSegment("BookahPhotos").addPathSegment("index").addPathSegment(name).build()

    /** [allowBigDownload] false (mobile data, not allowed): a newer index is reported as [SyncResult.WaitingForWifi] and not downloaded. Checking the small manifest is always done. */
    suspend fun sync(allowBigDownload: Boolean = true, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): SyncResult = withContext(Dispatchers.IO) {
        val have = store.currentGeneration() != null
        try {
            val manifest = fetchManifest() ?: return@withContext SyncResult.NoIndexYet
            if (manifest.schemaMajor > Manifest.SUPPORTED_MAJOR) return@withContext SyncResult.AppTooOld(manifest.schemaMajor)
            if (store.currentGeneration() == manifest.generation) return@withContext SyncResult.UpToDate(manifest.generation)
            if (!allowBigDownload) return@withContext SyncResult.WaitingForWifi(manifest.generation, manifest.size)
            download(manifest, onProgress)
            SyncResult.Updated(manifest.generation)
        } catch (e: UnauthorizedException) {
            SyncResult.SignedOut
        } catch (e: IOException) {
            SyncResult.Offline(have, e.message ?: e.javaClass.simpleName)
        } catch (e: kotlinx.serialization.SerializationException) {
            SyncResult.Offline(have, "The index file could not be read.")
        } catch (e: IllegalArgumentException) {
            SyncResult.Offline(have, "The index points at an unexpected file name.")
        }
    }

    class UnauthorizedException : IOException("Nextcloud refused the sign-in")

    /** Null when there is no manifest (the indexer has not published yet). */
    private fun fetchManifest(): Manifest? {
        val req = Request.Builder().url(indexUrl("manifest.json")).header("Authorization", session.authHeader).build()
        http.newCall(req).execute().use { resp ->
            when {
                resp.code == 401 -> throw UnauthorizedException()
                resp.code == 404 -> return null
                !resp.isSuccessful -> throw IOException("Nextcloud answered ${resp.code}")
            }
            return Manifest.parse(resp.body.string())
        }
    }

    private fun download(manifest: Manifest, onProgress: (Long, Long) -> Unit) {
        // the file name comes from the server: use it only as one plain path segment
        require('/' !in manifest.file && manifest.file.endsWith(".db")) { "unexpected snapshot name" }
        val req = Request.Builder().url(indexUrl(manifest.file)).header("Authorization", session.authHeader).build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw UnauthorizedException()
            if (!resp.isSuccessful) throw IOException("Nextcloud answered ${resp.code} for the snapshot")
            try {
                store.install(manifest, resp.body.byteStream()) { done -> onProgress(done, manifest.size) }
            } catch (e: SnapshotStore.BadDownload) {
                throw IOException("The downloaded snapshot was damaged (${e.message}). Trying again later.")
            }
        }
    }
}
