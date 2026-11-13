package dev.bookah.photos.data

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What the app needs to talk to Nextcloud: the address, the user name and the app password made by Login Flow v2. */
data class Session(val server: String, val user: String, val appPassword: String) {
    val authHeader: String get() = Credentials.basic(user, appPassword, Charsets.UTF_8)

    /** The root of this user's files over WebDAV, ending in a slash. */
    fun davRoot(): HttpUrl = server.toHttpUrlOrNull()!!.newBuilder()
        .addPathSegments("remote.php/dav/files").addPathSegment(user).addPathSegment("").build()

    /** The WebDAV address of an original, from its path inside the user's files (each path part encoded). */
    fun originalUrl(path: String): String = davRoot().newBuilder().apply { path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }.build().toString()

    override fun toString() = "Session(server=$server, user=$user, appPassword=***)"   // never print the password
}

/** Turns what Chris types ("cloud.example.ts.net", "https://cloud.example.ts.net/") into a clean https address, or null. */
fun normalizeServerAddress(input: String): String? {
    var s = input.trim().trimEnd('/')
    if (s.isEmpty() || s.any { it.isWhitespace() }) return null
    if ("://" !in s) s = "https://$s"
    if (!s.startsWith("https://", ignoreCase = true)) return null        // the app only talks HTTPS (over Tailscale)
    val url = s.toHttpUrlOrNull() ?: return null
    if (url.encodedQuery != null || url.encodedFragment != null) return null
    return url.toString().trimEnd('/')
}

interface SessionStore {
    suspend fun load(): Session?
    suspend fun save(session: Session)
    suspend fun clear()
}
