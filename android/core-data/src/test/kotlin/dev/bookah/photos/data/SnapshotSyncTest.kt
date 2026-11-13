package dev.bookah.photos.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class SnapshotSyncTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var store: SnapshotStore
    private lateinit var session: Session

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        store = SnapshotStore(tmp.newFolder("snapshots"))
        // MockWebServer speaks plain http; the sync code does not care about the scheme (the sign-in screen enforces https).
        session = Session(server.url("/").toString().trimEnd('/'), "tester", "app-password")
    }

    @After fun tearDown() = server.shutdown()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun manifestJson(gen: Long, body: ByteArray, major: Int = 1, sha: String = sha(body), size: Long = body.size.toLong()) =
        """{"generation":$gen,"file":"bookahphotos-g%06d.db","size":$size,"sha256":"$sha","schema_major":$major,"schema_minor":0,
            "counts":{"assets":3,"photos":2,"videos":1},"unknown_new_field":true}""".format(gen)

    private fun enqueueBody(b: ByteArray) = server.enqueue(MockResponse().setBody(Buffer().write(b)))

    private fun sync() = runBlocking { SnapshotSync(Http.newClient(), session, store).sync() }

    @Test fun `first sync downloads, verifies and switches`() {
        val body = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(manifestJson(1, body)))
        enqueueBody(body)
        val r = sync()
        assertEquals(SyncResult.Updated(1), r)
        assertEquals(1L, store.currentGeneration())
        assertEquals(body.size.toLong(), store.currentFile()!!.length())
        // the request carried the app password as Basic auth and used the user's WebDAV folder
        val first = server.takeRequest()
        assertEquals("/remote.php/dav/files/tester/BookahPhotos/index/manifest.json", first.path)
        assertTrue(first.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test fun `on mobile data without permission a newer index waits for Wi-Fi and nothing big is downloaded`() {
        val body = ByteArray(5_000) { 3 }
        server.enqueue(MockResponse().setBody(manifestJson(4, body)))
        val r = runBlocking { SnapshotSync(Http.newClient(), session, store).sync(allowBigDownload = false) }
        assertEquals(SyncResult.WaitingForWifi(4, body.size.toLong()), r)
        assertNull(store.currentGeneration())
        assertEquals(1, server.requestCount)                                                  // only the small manifest was asked for
        // later, with permission (or on Wi-Fi), it downloads
        server.enqueue(MockResponse().setBody(manifestJson(4, body))); enqueueBody(body)
        assertEquals(SyncResult.Updated(4), runBlocking { SnapshotSync(Http.newClient(), session, store).sync(allowBigDownload = true) })
    }

    @Test fun `a current index on mobile data is simply up to date`() {
        val body = ByteArray(1000) { 9 }
        server.enqueue(MockResponse().setBody(manifestJson(2, body))); enqueueBody(body); assertEquals(SyncResult.Updated(2), sync())
        server.enqueue(MockResponse().setBody(manifestJson(2, body)))
        assertEquals(SyncResult.UpToDate(2), runBlocking { SnapshotSync(Http.newClient(), session, store).sync(allowBigDownload = false) })
    }

    @Test fun `same generation downloads nothing`() {
        val body = ByteArray(1000) { 7 }
        server.enqueue(MockResponse().setBody(manifestJson(1, body))); enqueueBody(body)
        sync()
        server.enqueue(MockResponse().setBody(manifestJson(1, body)))
        assertEquals(SyncResult.UpToDate(1), sync())
        assertEquals(3, server.requestCount)     // manifest, snapshot, manifest again: no second download
    }

    @Test fun `a damaged download keeps the previous snapshot`() {
        val good = ByteArray(5000) { 1 }
        server.enqueue(MockResponse().setBody(manifestJson(1, good))); enqueueBody(good)
        sync()
        val bad = ByteArray(5000) { 2 }
        // manifest promises one checksum, the bytes differ
        server.enqueue(MockResponse().setBody(manifestJson(2, bad, sha = sha(good)))); enqueueBody(bad)
        val r = sync()
        assertTrue(r is SyncResult.Offline)
        assertEquals(1L, store.currentGeneration())
        assertFalse(store.fileFor(2).exists())
        assertNull(store.dirListing().find { it.endsWith(".tmp") && it != "current" })
    }

    @Test fun `a truncated download is refused`() {
        val body = ByteArray(5000) { 3 }
        server.enqueue(MockResponse().setBody(manifestJson(1, body))); enqueueBody(body.copyOf(4000))
        val r = sync()
        assertTrue(r is SyncResult.Offline)
        assertNull(store.currentGeneration())
    }

    @Test fun `a newer schema major version asks for an app update and downloads nothing`() {
        val body = ByteArray(10)
        server.enqueue(MockResponse().setBody(manifestJson(5, body, major = 2)))
        assertEquals(SyncResult.AppTooOld(2), sync())
        assertEquals(1, server.requestCount)
    }

    @Test fun `no manifest yet means no index yet`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(SyncResult.NoIndexYet, sync())
    }

    @Test fun `a revoked app password is reported as signed out`() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(SyncResult.SignedOut, sync())
    }

    @Test fun `offline with an older snapshot keeps it`() {
        val body = ByteArray(100) { 9 }
        server.enqueue(MockResponse().setBody(manifestJson(1, body))); enqueueBody(body)
        sync()
        server.shutdown()
        val r = sync() as SyncResult.Offline
        assertTrue(r.haveSnapshot)
        assertEquals(1L, store.currentGeneration())
    }

    @Test fun `only the current and the previous snapshot are kept`() {
        for (g in 1L..3L) {
            val b = ByteArray(50) { g.toByte() }
            server.enqueue(MockResponse().setBody(manifestJson(g, b))); enqueueBody(b)
            sync()
        }
        assertFalse(store.fileFor(1).exists())
        assertTrue(store.fileFor(2).exists())
        assertTrue(store.fileFor(3).exists())
        assertNotNull(store.currentFile())
    }

    @Test fun `a server file name with a slash is refused`() {
        val body = ByteArray(10)
        server.enqueue(MockResponse().setBody("""{"generation":1,"file":"../evil.db","size":10,"sha256":"${sha(body)}","schema_major":1}"""))
        val r = runCatching { sync() }
        assertTrue(r.isFailure || r.getOrNull() is SyncResult.Offline)
        assertNull(store.currentGeneration())
    }
}

private fun SnapshotStore.dirListing(): List<String> = (fileFor(0).parentFile?.list() ?: emptyArray()).toList()
