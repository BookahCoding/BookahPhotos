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

class UpdateCheckerTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun checker(repo: String = "owner/app") = UpdateChecker(Http.newClient(), repo, server.url("/").toString().trimEnd('/'))

    private fun release(tag: String, draft: Boolean = false, vararg assets: Pair<String, String>, pre: Boolean = false) =
        """{"tag_name":"$tag","html_url":"https://github.com/owner/app/releases/tag/$tag","draft":$draft,"prerelease":$pre,"assets":[${assets.joinToString(",") { """{"name":"${it.first}","browser_download_url":"${it.second}","size":4700000}""" }}],"other":1}"""

    @Test fun `versions compare number by number`() {
        assertTrue(UpdateChecker.compareVersions("v0.10.0", "0.9.9") > 0)
        assertEquals(0, UpdateChecker.compareVersions("v0.7.0", "0.7.0"))
        assertTrue(UpdateChecker.compareVersions("0.6.1", "v0.7.0") < 0)
        assertEquals(0, UpdateChecker.compareVersions("0.0.1-spike", "0.0.1"))
    }

    @Test fun `a newer release is found with its apk, drafts are ignored`() = runBlocking {
        server.enqueue(MockResponse().setBody("[${release("v0.9.0", draft = true)},${release("v0.8.0", false, "BookahPhotos-0.8.0.apk" to "https://example/a.apk")},${release("v0.7.0")}]"))
        val u = checker().newerThan("0.7.0")!!
        assertEquals("0.8.0", u.version)
        assertEquals("https://example/a.apk", u.apkUrl)
        assertEquals("/repos/owner/app/releases?per_page=15", server.takeRequest().path)
    }

    @Test fun `test builds are never offered, only real releases, and 1_0_0 is newer than every 0_x`() = runBlocking {
        server.enqueue(MockResponse().setBody("[${release("v1.1.0-rc", false, pre = true)},${release("v1.0.0", false, "BookahPhotos-1.0.0.apk" to "https://example/b.apk")},${release("v0.19.1", pre = true)}]"))
        val u = checker().newerThan("0.19.1")!!
        assertEquals("1.0.0", u.version); assertEquals("https://example/b.apk", u.apkUrl)
        server.enqueue(MockResponse().setBody("[${release("v1.1.0", pre = true)},${release("v1.0.0")}]"))
        assertNull(checker().newerThan("1.0.0"))                                               // the pre-release 1.1.0 does not count
    }

    @Test fun `nothing newer means no update`() = runBlocking {
        server.enqueue(MockResponse().setBody("[${release("v0.7.0")},${release("v0.6.1")}]"))
        assertNull(checker().newerThan("0.7.0"))
    }

    @Test fun `a private repository or a GitHub error is an error, not a made-up update`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val r = runCatching { runBlocking { checker().newerThan("0.7.0") } }
        assertTrue(r.exceptionOrNull() is java.io.IOException)
    }

    @Test fun `a strange repository name is refused before any request`() {
        val r = runCatching { runBlocking { checker("a b/../c").newerThan("0.7.0") } }
        assertTrue(r.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }
}
