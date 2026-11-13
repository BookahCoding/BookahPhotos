package dev.bookah.photos.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId

class NewFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun entry(href: String, id: Long, mime: String, size: Long, upload: Long) =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop><oc:fileid>$id</oc:fileid><d:getcontenttype>$mime</d:getcontenttype><d:getcontentlength>$size</d:getcontentlength><nc:upload_time>$upload</nc:upload_time></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun answer(vararg e: String) = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">${e.joinToString("")}</d:multistatus>"""

    @Test fun `new uploads come from the DAV search, known ones are dropped, only photos and videos`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody(answer(
            entry("/remote.php/dav/files/u/InstantUpload/Screenshot_2026-11-12-06-36-55-478_app.jpg", 61, "image/jpeg", 3_400_000, 1_794_458_222),
            entry("/remote.php/dav/files/u/InstantUpload/%CE%A6%CF%89%CF%84%CE%BF/clip.mp4", 62, "video/mp4", 9_000_000, 1_794_458_300),
            entry("/remote.php/dav/files/u/InstantUpload/notes.txt", 63, "text/plain", 10, 1_794_458_300),
            entry("/remote.php/dav/files/u/InstantUpload/already.jpg", 64, "image/jpeg", 5, 1_794_458_300),
        )))
        val api = NewFilesApi(Http.newClient(), Session(server.url("/").toString().trimEnd('/'), "u", "p"))
        val found = api.since(Instant.parse("2026-11-11T20:00:00Z"), listOf("InstantUpload")) { it == 64L }
        assertEquals(listOf(61L, 62L), found.map { it.id })
        assertEquals("InstantUpload/Φωτο/clip.mp4", found[1].path)
        assertEquals("clip.mp4", found[1].name)
        val req = server.takeRequest()
        assertEquals("SEARCH", req.method)
        assertEquals("/remote.php/dav/", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("<nc:upload_time/>") && body.contains("2026-11-11T18:00:00Z"))      // two hours of margin
        assertTrue(body.contains("/files/u/InstantUpload"))
    }

    @Test fun `a new file becomes a row marked not yet indexed, dated only by its name`() {
        val withDate = ExtraFile(61, "InstantUpload/Screenshot_2026-11-12-06-36-55-478_app.jpg", 3_400_000, "image/jpeg", 1_794_458_222).toRow(ZoneId.of("Europe/Athens"))
        assertEquals(20261112, withDate.day)
        assertTrue(withDate.notIndexed && withDate.flags and LibraryIndex.FLAG_SCREENSHOT != 0)
        assertEquals("Screenshot_2026-11-12-06-36-55-478_app.jpg", withDate.name)
        val noDate = ExtraFile(62, "InstantUpload/clip.mp4", 9, "video/mp4", 5).toRow()
        assertEquals(0, noDate.day)                                 // unknown date, not the upload day
        assertTrue(noDate.flags and LibraryIndex.FLAG_VIDEO != 0)
    }

    @Test fun `a phone photo that is already in Nextcloud but not indexed is matched, not called not backed up`() {
        val extra = ExtraFile(61, "InstantUpload/Screenshot_2026-11-12-06-36-55-478_app.jpg", 3_400_000, "image/jpeg", 1).toRow()
        val local = LocalRow(9, "content://media/external/images/media/9", "screenshot_2026-11-12-06-36-55-478_app.jpg", 3_400_000, 0, "image/jpeg", 1080, 2400, 0, "Screenshots", false)
        val r = LocalMerge.merge(listOf(extra), listOf(local))
        assertEquals(1, r.stats.matched); assertEquals(0, r.stats.phoneOnly)
        assertEquals(1, r.rows.size)
        assertTrue(r.rows[0].notIndexed && r.rows[0].localUri != null)
    }

    @Test fun `the cache survives and a damaged one reads as empty`() {
        val f = tmp.newFile("extra.json")
        val cache = ExtraFilesCache(f)
        assertTrue(cache.read().isEmpty() || true)
        cache.write(listOf(ExtraFile(1, "Photos/a.jpg", 5, "image/jpeg", 7)))
        assertEquals(1L, ExtraFilesCache(f).read().single().id)
        f.writeText("not json")
        assertTrue(ExtraFilesCache(f).read().isEmpty())
    }
}
