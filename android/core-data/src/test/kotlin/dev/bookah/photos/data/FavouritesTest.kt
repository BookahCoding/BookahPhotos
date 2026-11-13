package dev.bookah.photos.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FavouritesTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun api() = FavouritesApi(Http.newClient(), Session(server.url("/").toString().trimEnd('/'), "u", "p"), listOf("Photos", "InstantUpload"))
    private val ok = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:propstat><d:prop><oc:favorite xmlns:oc="http://owncloud.org/ns"/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""

    @Test fun `setting a favourite is one PROPPATCH on that file and nothing else`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody(ok))
        assertEquals(FavouritesApi.Outcome.Done, api().set("Photos/Βάπτιση 2024/IMG 1.jpg", true))
        val req = server.takeRequest()
        assertEquals("PROPPATCH", req.method)
        assertTrue(req.path!!.startsWith("/remote.php/dav/files/u/Photos/")); assertTrue(req.path!!.endsWith("IMG%201.jpg"))
        assertTrue(req.body.readUtf8().contains("<oc:favorite>1</oc:favorite>"))
        assertTrue(req.getHeader("Authorization")!!.startsWith("Basic "))
        assertEquals(1, server.requestCount)
    }

    @Test fun `clearing sends 0, a missing file is finished, a server error is kept for later, a wrong password is reported`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody(ok)); assertEquals(FavouritesApi.Outcome.Done, api().set("Photos/a.jpg", false))
        assertTrue(server.takeRequest().body.readUtf8().contains("<oc:favorite>0</oc:favorite>"))
        server.enqueue(MockResponse().setResponseCode(404)); assertEquals(FavouritesApi.Outcome.Gone, api().set("Photos/gone.jpg", true))
        server.enqueue(MockResponse().setResponseCode(500)); assertTrue(api().set("Photos/a.jpg", true) is FavouritesApi.Outcome.Failed)
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching { runBlocking { api().set("Photos/a.jpg", true) } }.exceptionOrNull() is SnapshotSync.UnauthorizedException)
    }

    @Test fun `only paths inside the photo folders are accepted`() = runBlocking {
        val a = api()
        assertTrue(a.isAllowed("Photos/2024/x.jpg")); assertTrue(a.isAllowed("InstantUpload/x.jpg"))
        assertFalse(a.isAllowed("Documents/x.jpg")); assertFalse(a.isAllowed("Photos/../Documents/x.jpg")); assertFalse(a.isAllowed("/Photos/x.jpg")); assertFalse(a.isAllowed("Photos/"))
        assertTrue(a.set("Documents/x.jpg", true) is FavouritesApi.Outcome.Refused)
        assertEquals(0, server.requestCount)
    }

    @Test fun `the latest wish wins, a finished change does not erase a newer one, and it survives a restart`() = runBlocking {
        val f = tmp.newFile("fav.txt")
        val store = PendingFavouritesStore(f)
        store.set(listOf(PendingFavourite(1, "Photos/a.jpg", true), PendingFavourite(2, "Photos/b|c.jpg", true)))
        store.set(listOf(PendingFavourite(1, "Photos/a.jpg", false)))                                   // changed their mind about 1
        assertEquals(listOf(PendingFavourite(1, "Photos/a.jpg", false), PendingFavourite(2, "Photos/b|c.jpg", true)), PendingFavouritesStore(f).all())
        // the earlier "on" for photo 1 was being sent when the reader cleared it: finishing the old one must not forget the new one
        store.remove(listOf(PendingFavourite(1, "Photos/a.jpg", true), PendingFavourite(2, "Photos/b|c.jpg", true)))
        assertEquals(listOf(PendingFavourite(1, "Photos/a.jpg", false)), store.all())
        store.remove(listOf(PendingFavourite(1, "Photos/a.jpg", false))); assertFalse(f.exists())
    }

    @Test fun `unconfirmed changes show on top of Nextcloud's list, and the heart in the Select bar sets unless all are set`() {
        val shown = FavouritesOverlay.effective(listOf(1L, 2L), listOf(PendingFavourite(2, "p", false), PendingFavourite(3, "p", true)))
        assertEquals(setOf(1L, 3L), shown)
        assertTrue(FavouritesOverlay.wantedState(longArrayOf(1, 5), setOf(1L)))             // 5 is not a favourite: set them all
        assertFalse(FavouritesOverlay.wantedState(longArrayOf(1, 5), setOf(1L, 5L)))        // all are: clear them
        assertFalse(FavouritesOverlay.wantedState(longArrayOf(-3), emptySet()))             // a photo only on this phone: nothing to set
    }
}
