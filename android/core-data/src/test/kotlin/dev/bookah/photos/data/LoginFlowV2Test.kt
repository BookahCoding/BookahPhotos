package dev.bookah.photos.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoginFlowV2Test {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun base() = server.url("/").toString().trimEnd('/')
    private fun startBody() = """{"poll":{"token":"tok123","endpoint":"${base()}/index.php/login/v2/poll"},"login":"${base()}/index.php/login/v2/flow/tok123"}"""

    @Test fun `start returns the login address`() = runBlocking {
        server.enqueue(MockResponse().setBody(startBody()))
        val s = LoginFlowV2(Http.newClient()).start(base())
        assertTrue(s.loginUrl.endsWith("/login/v2/flow/tok123"))
        assertEquals("/index.php/login/v2", server.takeRequest().path)
    }

    @Test fun `polling waits through 404 and then returns the session`() = runBlocking {
        server.enqueue(MockResponse().setBody(startBody()))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"server":"https://cloud.example.ts.net","loginName":"someone","appPassword":"secret-app-password"}"""))
        val flow = LoginFlowV2(Http.newClient(), pollIntervalMs = 10)
        val r = flow.awaitApproval(flow.start(base()))
        r as LoginFlowV2.Result.Approved
        assertEquals("https://cloud.example.ts.net", r.session.server)
        assertEquals("someone", r.session.user)
        // the poll sends the token as a form field, never in the address
        server.takeRequest()
        val poll = server.takeRequest()
        assertEquals("token=tok123", poll.body.readUtf8())
    }

    @Test fun `it gives up after the timeout`() = runBlocking {
        server.enqueue(MockResponse().setBody(startBody()))
        repeat(50) { server.enqueue(MockResponse().setResponseCode(404)) }
        val flow = LoginFlowV2(Http.newClient(), pollIntervalMs = 10, timeoutMs = 50)
        assertEquals(LoginFlowV2.Result.TimedOut, flow.awaitApproval(flow.start(base())))
    }

    @Test fun `a server that is not Nextcloud fails clearly`() {
        server.enqueue(MockResponse().setResponseCode(405))
        val r = runCatching { runBlocking { LoginFlowV2(Http.newClient()).start(base()) } }
        assertTrue(r.isFailure)
    }

    @Test fun `the session never prints its password`() {
        assertTrue("secret" !in Session("https://x", "u", "secret").toString())
    }

    @Test fun `server address is cleaned and must be https`() {
        assertEquals("https://cloud.example.ts.net", normalizeServerAddress("cloud.example.ts.net"))
        assertEquals("https://cloud.example.ts.net", normalizeServerAddress("  https://cloud.example.ts.net/ "))
        assertEquals("https://host/nextcloud", normalizeServerAddress("https://host/nextcloud/"))
        assertNull(normalizeServerAddress("http://cloud.example.ts.net"))
        assertNull(normalizeServerAddress("two words"))
        assertNull(normalizeServerAddress(""))
        assertNull(normalizeServerAddress("https://host/?a=b"))
    }
}
