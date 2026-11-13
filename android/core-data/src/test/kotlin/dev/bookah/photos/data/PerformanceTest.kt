package dev.bookah.photos.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.random.Random

/**
 * Speed of the in-memory library with 50,000 items (the plan's size target) on the build machine. The limits are generous: they catch an
 * accidental slow-down (for example a quadratic loop), not small changes. The real numbers on the phone are in Settings, Speed.
 */
class PerformanceTest {
    private val rnd = Random(7)

    private fun rows(n: Int) = (0 until n).map { i ->
        val day = 20100101 + (i / 12) % 3650 / 30 * 100 + (i / 12) % 28 + 1
        AssetRow(1_000L + i, day, i.toLong(), if (i % 17 == 0) 1 else 0, null, i, false, name = "IMG_%06d.jpg".format(i), size = 3_000_000L + rnd.nextInt(1000))
    }

    @Test fun `building a library of 50,000 items`() {
        val data = rows(50_000)
        val t0 = System.nanoTime()
        val lib = LibraryIndex.build(data)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("LibraryIndex.build(50,000): $ms ms; days=${lib.days.size} months=${lib.months.size}")
        assertTrue("took $ms ms", ms < 3_000)
    }

    @Test fun `merging 50,000 index items with 8,000 phone photos`() {
        val idx = rows(50_000)
        val locals = (0 until 8_000).map { i ->
            val j = i * 6
            LocalRow(i + 1L, "content://x/$i", if (i % 2 == 0) "IMG_%06d.jpg".format(j) else "only_$i.jpg", if (i % 2 == 0) idx[j].size else 123L + i, 0, "image/jpeg", 4000, 3000, 0, "Camera", false)
        }
        val t0 = System.nanoTime()
        val r = LocalMerge.merge(idx, locals, ZoneId.of("Europe/Athens"))
        val lib = LibraryIndex.build(r.rows)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("merge + build (50,000 + 8,000): $ms ms; matched=${r.stats.matched} phoneOnly=${r.stats.phoneOnly}; library=${lib.size}")
        assertTrue("took $ms ms", ms < 5_000)
        assertTrue(r.stats.matched in 3_900..4_100)
    }

    @Test fun `subset and source filter of 50,000 items`() {
        val lib = LibraryIndex.build(rows(50_000))
        val t0 = System.nanoTime()
        val nc = lib.bySource(Source.Nextcloud)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("bySource(Nextcloud) of 50,000: $ms ms")
        assertTrue(nc.size == 50_000 && ms < 3_000)
    }
}
