package dev.bookah.photos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
private data class GhAsset(val name: String, val browser_download_url: String, val size: Long = 0)

@Serializable
private data class GhRelease(val tag_name: String, val html_url: String = "", val draft: Boolean = false, val prerelease: Boolean = false, val assets: List<GhAsset> = emptyList())

/** A newer version of the app that is published on GitHub. [apkUrl] is the file to download, [pageUrl] the release page. */
data class UpdateInfo(val version: String, val pageUrl: String, val apkUrl: String?, val sizeBytes: Long)

/**
 * Asks GitHub which version is newest (the releases list of a public repository, no password: the same way the other Bookah apps update).
 * It is switched on only when the build knows a repository (`-PreleasesRepo=owner/name`); a private repository cannot be asked without a
 * password, so while the repository is private the app never looks.
 */
class UpdateChecker(private val http: OkHttpClient, private val repo: String, private val baseUrl: String = "https://api.github.com") {
    private val json = Json { ignoreUnknownKeys = true }

    /** The newest published (not draft, not pre-release) version that is newer than [currentVersion], or null when this one is the newest. */
    suspend fun newerThan(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        require(repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "unexpected repository name" }
        // GitHub's own "latest release": the newest one that is neither a draft nor a pre-release, however many test builds were published after it
        val req = Request.Builder().url("$baseUrl/repos/$repo/releases/latest").header("Accept", "application/vnd.github+json").build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub answered ${resp.code}")
            resp.body.string()
        }
        val best = json.decodeFromString<GhRelease>(body).takeIf { !it.draft && !it.prerelease } ?: return@withContext null
        if (compareVersions(best.tag_name, currentVersion) <= 0) return@withContext null
        val apks = best.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val apk = apks.firstOrNull { it.name.contains("arm64", ignoreCase = true) } ?: apks.firstOrNull()
        UpdateInfo(best.tag_name.removePrefix("v"), best.html_url, apk?.browser_download_url, apk?.size ?: 0)
    }

    companion object {
        /** "v0.7.0" against "0.6.1": numbers part by part; a suffix after "-" (like "-spike") is ignored. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val pa = parts(a); val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
