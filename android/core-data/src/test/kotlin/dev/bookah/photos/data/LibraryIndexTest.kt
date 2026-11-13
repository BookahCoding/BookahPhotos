package dev.bookah.photos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LibraryIndexTest {
    private var nextId = 1L
    private fun row(day: Int, t: Long = 0, flags: Int = 0, raw: Boolean = false) =
        AssetRow(nextId++, day, t, flags, null, 0, raw)

    private val en = Locale.ENGLISH

    @Test fun `grid order is undated first, then by day and time, newest last`() {
        val idx = LibraryIndex.build(listOf(
            row(20240105, 300), row(0), row(20240105, 100), row(20231231, 50), row(0), row(20240301, 10),
        ))
        assertEquals(2, idx.undatedCount)
        assertEquals(listOf(0, 0, 20231231, 20240105, 20240105, 20240301), idx.day.toList())
        assertEquals(listOf(100L, 300L), idx.takenUtc.toList().subList(3, 5))
    }

    @Test fun `days, months and years are grouped as index ranges`() {
        val idx = LibraryIndex.build(listOf(
            row(0), row(20231231), row(20240105), row(20240105), row(20240228), row(20240301),
        ))
        assertEquals(listOf(2023, 2024), idx.years.map { it.year })
        assertEquals(listOf(1 to 1, 2 to 4), listOf(idx.years[0].from to idx.years[0].count, idx.years[1].from to idx.years[1].count))
        assertEquals(listOf(2023 to 12, 2024 to 1, 2024 to 2, 2024 to 3), idx.months.map { it.year to it.month })
        assertEquals(listOf(2, 1), idx.months[1].days.map { it.count }.let { listOf(it[0], it.size) })
        assertEquals(1, idx.months[0].from)
        assertEquals(6, idx.months.last().toExclusive)
    }

    @Test fun `no dates at all still works`() {
        val idx = LibraryIndex.build(listOf(row(0), row(0)))
        assertTrue(idx.months.isEmpty() && idx.years.isEmpty())
        assertEquals("Unknown date", idx.rangeLabel(0, 1, en, "Unknown date"))
    }

    @Test fun `an empty library does not crash`() {
        val idx = LibraryIndex.build(emptyList())
        assertEquals("", idx.rangeLabel(0, 0, en, "x"))
        assertEquals(0, idx.size)
    }

    @Test fun `range labels`() {
        val idx = LibraryIndex.build(listOf(
            row(0), row(20260903), row(20260910), row(20261007), row(20251220), row(20260103),
        ))
        // order: undated, 20251220, 20260103, 20260903, 20260910, 20261007
        assertEquals("10 Sep 2026", idx.rangeLabel(4, 4, en, "?"))
        assertEquals("3 – 10 Sep 2026", idx.rangeLabel(3, 4, en, "?"))
        assertEquals("3 Sep – 7 Oct 2026", idx.rangeLabel(3, 5, en, "?"))
        assertEquals("20 Dec 2025 – 3 Jan 2026", idx.rangeLabel(1, 2, en, "?"))
        assertEquals("Unknown date", idx.rangeLabel(0, 0, en, "Unknown date"))
        assertEquals("Sep 2026", idx.monthYearLabel(3, en, "?"))
    }

    @Test fun `index of a day finds the first item on or after it`() {
        val idx = LibraryIndex.build(listOf(row(0), row(20240105), row(20240105), row(20240301)))
        assertEquals(1, idx.indexOfDay(20240105))
        assertEquals(3, idx.indexOfDay(20240201))
        assertEquals(4, idx.indexOfDay(20250101))
    }

    @Test fun `a cover is a middle photo, not a video, a screenshot or a RAW tile`() {
        val idx = LibraryIndex.build(listOf(
            row(20240101, 1, flags = LibraryIndex.FLAG_VIDEO), row(20240101, 2, flags = LibraryIndex.FLAG_SCREENSHOT),
            row(20240101, 3), row(20240101, 4, raw = true), row(20240101, 5, flags = LibraryIndex.FLAG_VIDEO),
        ))
        assertEquals(2, idx.coverIn(0, 5))
        // only videos: still returns an item inside the range
        val v = LibraryIndex.build(listOf(row(20240101, 1, flags = LibraryIndex.FLAG_VIDEO), row(20240101, 2, flags = LibraryIndex.FLAG_VIDEO)))
        assertTrue(v.coverIn(0, 2) in 0..1)
    }

    @Test fun `day keys parse safely`() {
        assertEquals(20240824, LibraryIndex.parseDayKey("2024-08-24"))
        assertEquals(0, LibraryIndex.parseDayKey(null))
        assertEquals(0, LibraryIndex.parseDayKey("2024-13-01"))
        assertEquals(0, LibraryIndex.parseDayKey("garbage!!!"))
        assertFalse(LibraryIndex.parseDayKey("2024/08/24") != 0)
    }

    @Test fun `subsets for collections`() {
        val a = row(20240101, 1); val b = row(20240102, 2, flags = LibraryIndex.FLAG_VIDEO); val c = row(20240103, 3, flags = LibraryIndex.FLAG_PANORAMA)
        val d = row(20240104, 4, raw = true)
        val idx = LibraryIndex.build(listOf(a, b, c, d))
        assertEquals(listOf(b.id), idx.indicesWhere { idx.isVideo(it) }.map { idx.ids[it] })
        assertEquals(listOf(c.id), idx.indicesWhere { idx.isPanorama(it) }.map { idx.ids[it] })
        assertEquals(listOf(d.id), idx.indicesWhere { idx.isRaw[it] }.map { idx.ids[it] })
        // newest uploads first: the highest ids
        assertEquals(listOf(d.id, c.id), idx.recentlyAdded(2).map { idx.ids[it] })
        assertEquals(listOf(20240103, 20240104), idx.recentDays(2).map { it.day })
        // file ids to positions, unknown ids dropped, order kept
        assertEquals(listOf(2, 0), idx.indicesOfIds(listOf(c.id, 999L, a.id)).toList())
        assertNull(idx.indexOfId(12345L))
    }
}
