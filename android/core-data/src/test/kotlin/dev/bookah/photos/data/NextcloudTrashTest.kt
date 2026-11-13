package dev.bookah.photos.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NextcloudTrashTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun trash(roots: List<String> = listOf("Photos", "InstantUpload")) =
        NextcloudTrash(Http.newClient(), Session(server.url("/").toString().trimEnd('/'), "u", "p"), roots)

    @Test fun `a delete is a WebDAV DELETE of exactly that file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val r = trash().moveToTrash("Photos/Βάπτιση 2024/IMG 1.jpg")
        assertEquals(NextcloudTrash.Outcome.Deleted, r)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertTrue(req.path!!.startsWith("/remote.php/dav/files/u/Photos/"))
        assertTrue(req.path!!.endsWith("IMG%201.jpg"))
        assertTrue(req.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test fun `a missing file counts as already gone, a server error is a failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(NextcloudTrash.Outcome.AlreadyGone, trash().moveToTrash("Photos/a.jpg"))
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(trash().moveToTrash("Photos/b.jpg") is NextcloudTrash.Outcome.Failed)
    }

    @Test fun `paths outside the photo folders or with tricks are refused without any request`() = runBlocking {
        val t = trash()
        for (bad in listOf("Documents/a.jpg", "Photos/../Documents/a.jpg", "/Photos/a.jpg", "Photos//a.jpg", "Photos/", "Photos", "PhotosX/a.jpg", "BookahPhotos/index/manifest.json", "")) {
            assertTrue("$bad should be refused", t.moveToTrash(bad) is NextcloudTrash.Outcome.Refused)
        }
        assertEquals(0, server.requestCount)
        assertTrue(t.isAllowed("InstantUpload/2026/IMG.jpg"))
        assertFalse(trash(listOf("Photos")).isAllowed("InstantUpload/IMG.jpg"))
    }

    @Test fun `a revoked password is reported, not swallowed`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val r = runCatching { runBlocking { trash().moveToTrash("Photos/a.jpg") } }
        assertTrue(r.exceptionOrNull() is SnapshotSync.UnauthorizedException)
    }

    @Test fun `the plan counts copies on the phone and in Nextcloud`() {
        fun row(id: Long, local: String? = null) = AssetRow(id, 20260101, id, 0, null, 0, false, localUri = local)
        val lib = LibraryIndex.build(listOf(row(1), row(2, "content://x/2"), row(-3, "content://x/3"), row(4)))
        val p = DeletePlan.of(lib, longArrayOf(1, 2, -3, 999))
        assertEquals(DeletePlan(total = 3, onPhone = 2, inNextcloud = 2, bothSides = 1), p)
        assertEquals(1, p.phoneOnly); assertEquals(1, p.nextcloudOnly)
    }
}
