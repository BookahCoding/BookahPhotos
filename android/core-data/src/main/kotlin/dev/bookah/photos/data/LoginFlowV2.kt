package dev.bookah.photos.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Nextcloud Login Flow v2: the app asks for a login address, Chris approves it in the browser, and the app receives an
 * app password that he can revoke in Nextcloud. The app never sees his real password.
 */
class LoginFlowV2(private val http: OkHttpClient, private val pollIntervalMs: Long = 2_000, private val timeoutMs: Long = 20 * 60_000) {
    sealed interface Result {
        data class Approved(val session: Session) : Result
        data object TimedOut : Result
        data class Failed(val message: String) : Result
    }

    @Serializable private data class Start(val poll: Poll, val login: String)
    @Serializable private data class Poll(val token: String, val endpoint: String)
    @Serializable private data class Done(val server: String, val loginName: String, val appPassword: String)

    private val json = Json { ignoreUnknownKeys = true }

    class Started(val loginUrl: String, internal val token: String, internal val endpoint: String)

    /** Step 1: returns the address to open in the browser. */
    suspend fun start(server: String): Started = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$server/index.php/login/v2")
            .header("User-Agent", USER_AGENT)
            .post(ByteArray(0).toRequestBody()).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("The server answered ${resp.code}. Is this a Nextcloud address?")
            val s = json.decodeFromString<Start>(resp.body.string())
            Started(s.login, s.poll.token, s.poll.endpoint)
        }
    }

    /** Step 2: waits until Chris has approved (the server answers 404 until then). */
    suspend fun awaitApproval(started: Started): Result {
        var waited = 0L
        while (waited < timeoutMs) {
            try {
                val done = withContext(Dispatchers.IO) { pollOnce(started) }
                if (done != null) return Result.Approved(Session(normalizeServerAddress(done.server) ?: done.server.trimEnd('/'), done.loginName, done.appPassword))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // a short network break while the browser is open is normal: keep waiting
            }
            delay(pollIntervalMs)
            waited += pollIntervalMs
        }
        return Result.TimedOut
    }

    private fun pollOnce(s: Started): Done? {
        val req = Request.Builder().url(s.endpoint).header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().add("token", s.token).build()).build()
        http.newCall(req).execute().use { resp ->
            return when {
                resp.code == 404 -> null
                resp.isSuccessful -> json.decodeFromString<Done>(resp.body.string())
                else -> throw IOException("HTTP ${resp.code}")
            }
        }
    }

    private companion object { const val USER_AGENT = "BookahPhotos (Android)" }
}
