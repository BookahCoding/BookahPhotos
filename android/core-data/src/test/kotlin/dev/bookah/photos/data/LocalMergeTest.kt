package dev.bookah.photos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LocalMergeTest {
    private val zone = ZoneId.of("Europe/Athens")

    private fun idx(id: Long, name: String, size: Long, day: Int = 20260101, taken: Long = 1_767_265_200L) =
        AssetRow(id, day, taken, 0, null, 0, false, name = name, size = size)

    private fun loc(mediaId: Long, name: String, size: Long, takenMs: Long = 0, folder: String = "Camera", video: Boolean = false) =
        LocalRow(mediaId, "content://media/external/images/media/$mediaId", name, size, takenMs, if (video) "video/mp4" else "image/jpeg", 4000, 3000, 0, folder, video)

    @Test fun `the same photo on both sides becomes one row with the phone address`() {
        val r = LocalMerge.merge(listOf(idx(10, "IMG_0001.jpg", 5000)), listOf(loc(7, "img_0001.JPG", 5000)), zone)
        assertEquals(1, r.rows.size)
        assertEquals(10L, r.rows[0].id)                       // stays the Nextcloud id
        assertEquals("content://media/external/images/media/7", r.rows[0].localUri)
        assertEquals("Camera", r.rows[0].folder)
        assertEquals(1, r.stats.matched); assertEquals(0, r.stats.phoneOnly); assertEquals(1, r.stats.local)
    }

    @Test fun `a photo only on the phone gets a negative id and counts as phone only`() {
        val r = LocalMerge.merge(listOf(idx(10, "IMG_0001.jpg", 5000)), listOf(loc(7, "Screenshot_2026-11-12-06-36-55.png", 900, folder = "Screenshots")), zone)
        assertEquals(2, r.rows.size)
        val only = r.rows.first { it.id < 0 }
        assertEquals(-7L, only.id)
        assertTrue(only.flags and LibraryIndex.FLAG_SCREENSHOT != 0)
        assertEquals(20261112, only.day)                       // from the file name, in the phone's zone
        assertNull(r.rows.first { it.id == 10L }.localUri)
        assertEquals(1, r.stats.phoneOnly)
        assertEquals(mapOf("Screenshots" to 1), r.stats.perFolder)
    }

    @Test fun `same name but different size is a different photo`() {
        val r = LocalMerge.merge(listOf(idx(10, "IMG_0001.jpg", 5000)), listOf(loc(7, "IMG_0001.jpg", 5001)), zone)
        assertEquals(0, r.stats.matched); assertEquals(1, r.stats.phoneOnly)
    }

    @Test fun `same name and size but days apart is not a match`() {
        // index photo taken on 2026-01-01; the phone one was taken 10 days later
        val tenDaysLater = (1_767_265_200L + 10 * 86_400) * 1000
        val r = LocalMerge.merge(listOf(idx(10, "IMG_0001.jpg", 5000)), listOf(loc(7, "IMG_0001.jpg", 5000, takenMs = tenDaysLater)), zone)
        assertEquals(0, r.stats.matched)
        // within 36 hours it is a match even with a small clock difference
        val minutesLater = (1_767_265_200L + 90) * 1000
        assertEquals(1, LocalMerge.merge(listOf(idx(10, "IMG_0001.jpg", 5000)), listOf(loc(7, "IMG_0001.jpg", 5000, takenMs = minutesLater)), zone).stats.matched)
    }

    @Test fun `an undated index photo still matches by name and size`() {
        val r = LocalMerge.merge(listOf(idx(10, "scan.jpg", 777, day = 0, taken = 0)), listOf(loc(7, "scan.jpg", 777, takenMs = 1_700_000_000_000)), zone)
        assertEquals(1, r.stats.matched)
    }

    @Test fun `one index photo is matched at most once`() {
        val r = LocalMerge.merge(listOf(idx(10, "IMG_0001.jpg", 5000)), listOf(loc(7, "IMG_0001.jpg", 5000), loc(8, "IMG_0001.jpg", 5000, folder = "Download")), zone)
        assertEquals(1, r.stats.matched); assertEquals(1, r.stats.phoneOnly)
        assertEquals(2, r.rows.size)
    }

    @Test fun `phone dates use the capture time, then the file name, never file times`() {
        val takenMs = LocalDateTime.of(2026, 3, 4, 10, 0).atZone(zone).toEpochSecond() * 1000
        val a = LocalMerge.merge(emptyList(), listOf(loc(1, "photo.jpg", 1, takenMs = takenMs)), zone).rows.single()
        assertEquals(20260304, a.day)
        val b = LocalMerge.merge(emptyList(), listOf(loc(2, "IMG-20241105-WA0012.jpg", 1)), zone).rows.single()
        assertEquals(20241105, b.day)
        val c = LocalMerge.merge(emptyList(), listOf(loc(3, "random_name.jpg", 1)), zone).rows.single()
        assertEquals(0, c.day); assertEquals(0L, c.takenUtc)       // unknown date, not a guess
    }

    @Test fun `file name dates`() {
        assertEquals(LocalDateTime.of(2026, 11, 10, 19, 27, 50), DateFromName.parse("IMG_20261110_192750.jpg"))
        assertEquals(LocalDateTime.of(2026, 11, 12, 6, 36, 55), DateFromName.parse("Screenshot_2026-11-12-06-36-55-478_dev.b.png"))
        assertEquals(LocalDateTime.of(2024, 11, 5, 12, 0, 0), DateFromName.parse("IMG-20241105-WA0012.jpg"))
        assertNull(DateFromName.parse("scan_0001.tif"))
        assertNull(DateFromName.parse("IMG_20261340_000000.jpg"))
        assertNull(DateFromName.parse("IMG_20260230_101500.jpg"))     // 30 February
    }

    @Test fun `source filter and folders on the merged library`() {
        val merged = LocalMerge.merge(
            listOf(idx(10, "a.jpg", 1), idx(11, "b.jpg", 2)),
            listOf(loc(7, "a.jpg", 1, folder = "Camera"), loc(8, "only.jpg", 3, folder = "Download", takenMs = 1_700_000_000_000)), zone,
        )
        val lib = LibraryIndex.build(merged.rows)
        assertEquals(3, lib.size)
        assertEquals(2, lib.bySource(Source.Nextcloud).size)
        assertEquals(2, lib.bySource(Source.Phone).size)               // the matched one and the phone-only one
        assertEquals(3, lib.bySource(Source.All).size)
        assertEquals(setOf("Camera", "Download"), lib.folders().map { it.first }.toSet())
        assertTrue(lib.isLocalOnly(lib.indexOfId(-8)!!))
        assertFalse(lib.isLocalOnly(lib.indexOfId(10)!!))
        assertTrue(lib.hasLocalCopy(lib.indexOfId(10)!!))
    }

    @Test fun `an undated index copy takes the date the phone knows`() {
        val takenMs = LocalDateTime.of(2026, 11, 12, 8, 20).atZone(zone).toEpochSecond() * 1000
        val r = LocalMerge.merge(listOf(idx(10, "photo.jpg", 777, day = 0, taken = 0)), listOf(loc(7, "photo.jpg", 777, takenMs = takenMs)), zone).rows.single()
        assertEquals(10L, r.id)
        assertEquals(20261112, r.day)
        assertEquals(takenMs / 1000, r.takenUtc)
        // a dated index copy keeps its own date
        val kept = LocalMerge.merge(listOf(idx(10, "photo.jpg", 777, day = 20250101, taken = 1_735_689_600L)), listOf(loc(7, "photo.jpg", 777, takenMs = 1_735_689_600_000L + 30_000)), zone).rows.single()
        assertEquals(20250101, kept.day)
    }
}
