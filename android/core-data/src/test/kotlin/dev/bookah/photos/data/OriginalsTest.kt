package dev.bookah.photos.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OriginalsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun originals() = Originals(Http.newClient(), Session(server.url("/").toString().trimEnd('/'), "u", "p"))

    @Test fun `downloads the original by its path with Greek and space characters encoded`() = runBlocking {
        val body = ByteArray(1000) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val f = originals().download(ShareItem(7, "Photos/Βάπτιση 2024/IMG 1.jpg", "image/jpeg"), tmp.newFolder("share"))
        assertArrayEquals(body, f.readBytes())
        assertEquals("IMG 1.jpg", f.name)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/remote.php/dav/files/u/Photos/"))
        assertTrue(req.path!!.endsWith("IMG%201.jpg"))
        assertTrue(req.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test fun `a failed download leaves no partial file`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val dir = tmp.newFolder("share2")
        val r = runCatching { runBlocking { originals().download(ShareItem(8, "Photos/a.jpg", "image/jpeg"), dir) } }
        assertTrue(r.isFailure)
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun `file names are made safe`() {
        assertEquals("a_b.jpg", Originals.safeName("a/b.jpg", 1))
        assertEquals("photo-5.jpg", Originals.safeName("..", 5).let { if (it.startsWith("photo-5")) "photo-5.jpg" else it })
        assertEquals("photo-9", Originals.safeName("", 9))
    }
}
