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

class AlbumsWriteTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun api() = AlbumsApi(Http.newClient(), Session(server.url("/").toString().trimEnd('/'), "u", "p"), listOf("Photos", "InstantUpload"))
    private val base = "/remote.php/dav/photos/u/albums"

    @Test fun `creating an album is one MKCOL, and a name that exists is reported`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201)); assertEquals(AlbumsApi.Outcome.Done, api().create("  Καλοκαίρι 2026 "))
        val r = server.takeRequest(); assertEquals("MKCOL", r.method); assertEquals("$base/%CE%9A%CE%B1%CE%BB%CE%BF%CE%BA%CE%B1%CE%AF%CF%81%CE%B9%202026", r.path)
        server.enqueue(MockResponse().setResponseCode(405)); assertEquals(AlbumsApi.Outcome.NameTaken, api().create("Summer"))
        assertTrue(api().create("a/b") is AlbumsApi.Outcome.Refused); assertTrue(api().create("   ") is AlbumsApi.Outcome.Refused); assertTrue(api().create("..") is AlbumsApi.Outcome.Refused)
        assertEquals(2, server.requestCount)                                                    // refused names never reach the server
    }

    @Test fun `renaming moves the album and never overwrites another one`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201)); assertEquals(AlbumsApi.Outcome.Done, api().rename("Old", "New"))
        val r = server.takeRequest(); assertEquals("MOVE", r.method); assertEquals("$base/Old", r.path)
        assertTrue(r.getHeader("Destination")!!.endsWith("$base/New")); assertEquals("F", r.getHeader("Overwrite"))
        server.enqueue(MockResponse().setResponseCode(412)); assertEquals(AlbumsApi.Outcome.NameTaken, api().rename("Old", "Taken"))
        assertEquals(AlbumsApi.Outcome.Done, api().rename("Same", " Same "))                    // nothing to do
        assertEquals(2, server.requestCount)
    }

    @Test fun `adding a photo copies it into the album, and one that is there already is fine`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201)); assertEquals(AlbumsApi.Outcome.Done, api().add("Trip", "Photos/2024/Βάπτιση/IMG 1.jpg"))
        val r = server.takeRequest(); assertEquals("COPY", r.method)
        assertTrue(r.path!!.startsWith("/remote.php/dav/files/u/Photos/")); assertTrue(r.path!!.endsWith("IMG%201.jpg"))
        assertEquals("http://${server.hostName}:${server.port}$base/Trip/IMG%201.jpg", r.getHeader("Destination"))
        server.enqueue(MockResponse().setResponseCode(409)); assertEquals(AlbumsApi.Outcome.AlreadyThere, api().add("Trip", "Photos/a.jpg"))
        assertTrue(api().add("Trip", "Documents/secret.pdf") is AlbumsApi.Outcome.Refused); assertTrue(api().add("Trip", "Photos/../x.jpg") is AlbumsApi.Outcome.Refused)
        assertEquals(2, server.requestCount)
    }

    @Test fun `removing finds the entry by the file id and deletes only that entry`() = runBlocking {
        val xml = """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
            <d:response><d:href>/remote.php/dav/photos/u/albums/Trip/</d:href></d:response>
            <d:response><d:href>/remote.php/dav/photos/u/albums/Trip/57183-a%20b.jpg</d:href><d:propstat><d:prop><oc:fileid>57183</oc:fileid></d:prop></d:propstat></d:response>
            <d:response><d:href>/remote.php/dav/photos/u/albums/Trip/99-c.jpg</d:href><d:propstat><d:prop><oc:fileid>99</oc:fileid></d:prop></d:propstat></d:response></d:multistatus>"""
        server.enqueue(MockResponse().setResponseCode(207).setBody(xml)); server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(AlbumsApi.Outcome.Done, api().remove("Trip", 57183))
        assertEquals("PROPFIND", server.takeRequest().method)
        val del = server.takeRequest(); assertEquals("DELETE", del.method); assertEquals("$base/Trip/57183-a%20b.jpg", del.path)
        server.enqueue(MockResponse().setResponseCode(207).setBody(xml)); assertEquals(AlbumsApi.Outcome.Done, api().remove("Trip", 12345))   // not in it: nothing to delete
        assertEquals(3, server.requestCount)
        assertEquals("/remote.php/dav/photos/u/albums/Trip/99-c.jpg", AlbumsApi.memberHref(xml, 99)); assertNull(AlbumsApi.memberHref(xml, 1))
    }

    @Test fun `deleting an album and the error cases`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204)); assertEquals(AlbumsApi.Outcome.Done, api().delete("Trip"))
        assertEquals("DELETE", server.takeRequest().method)
        server.enqueue(MockResponse().setResponseCode(404)); assertEquals(AlbumsApi.Outcome.Missing, api().delete("Gone"))
        server.enqueue(MockResponse().setResponseCode(500)); assertTrue(api().delete("x") is AlbumsApi.Outcome.Failed)
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching { runBlocking { api().delete("x") } }.exceptionOrNull() is SnapshotSync.UnauthorizedException)
        assertEquals("a", AlbumsApi.validName(" a ")); assertNull(AlbumsApi.validName("")); assertNull(AlbumsApi.validName("x".repeat(101)))
    }
}
