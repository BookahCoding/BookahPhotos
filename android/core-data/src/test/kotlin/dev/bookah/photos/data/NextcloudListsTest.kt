package dev.bookah.photos.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NextcloudListsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun api() = NextcloudListsApi(Http.newClient(), Session(server.url("/").toString().trimEnd('/'), "u", "p"))

    private fun multistatus(vararg responses: String) =
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">${responses.joinToString("")}</d:multistatus>"""

    private fun item(href: String, id: Long?) =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop>${if (id != null) "<oc:fileid>$id</oc:fileid>" else ""}</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    @Test fun `favourites come from the REPORT filter and nothing is changed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(item("/remote.php/dav/files/u/Photos/a.jpg", 11), item("/remote.php/dav/files/u/Photos/b.jpg", 22))))
        assertEquals(listOf(11L, 22L), api().favouriteIds())
        val req = server.takeRequest()
        assertEquals("REPORT", req.method)
        assertEquals("/remote.php/dav/files/u/", req.path)
        assertTrue(req.body.readUtf8().contains("<oc:favorite>1</oc:favorite>"))
        assertTrue(req.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test fun `albums list then members, skipping the album folder itself`() = runBlocking {
        val album = "<d:response><d:href>/remote.php/dav/photos/u/albums/Trip%20%CE%A6/</d:href><d:propstat><d:prop><nc:nbItems>2</nc:nbItems><nc:last-photo>22</nc:last-photo></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        val root = "<d:response><d:href>/remote.php/dav/photos/u/albums/</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(root, album)))
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(
            item("/remote.php/dav/photos/u/albums/Trip%20%CE%A6/", 999), item("/remote.php/dav/photos/u/albums/Trip%20%CE%A6/11.jpg", 11), item("/remote.php/dav/photos/u/albums/Trip%20%CE%A6/22.jpg", 22),
        )))
        val result = api().albums()
        assertEquals(1, result.size)
        assertEquals(Album("Trip Φ", 2, 22L, listOf(11L, 22L)), result[0])
        assertEquals("/remote.php/dav/photos/u/albums", server.takeRequest().path)
        assertEquals("/remote.php/dav/photos/u/albums/Trip%20%CE%A6", server.takeRequest().path)
    }

    @Test fun `a revoked password is reported`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val r = runCatching { runBlocking { api().favouriteIds() } }
        assertTrue(r.exceptionOrNull() is SnapshotSync.UnauthorizedException)
    }

    @Test fun `the cache survives a restart and tolerates a damaged file`() {
        val f = tmp.newFile("lists.json")
        val cache = ListsCache(f)
        assertNull(cache.read())
        cache.write(NextcloudLists(listOf(1, 2), listOf(Album("A", 1, 1, listOf(1))), 5))
        assertEquals(listOf(1L, 2L), ListsCache(f).read()!!.favouriteIds)
        f.writeText("{not json")
        assertNull(ListsCache(f).read())
    }
}
