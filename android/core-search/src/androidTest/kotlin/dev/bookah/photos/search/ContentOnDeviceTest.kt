package dev.bookah.photos.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bookah.photos.data.MemoriesReader
import dev.bookah.photos.data.PlacesReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Memories and places of a REAL snapshot compared with numbers made from the same file by SQL (`/data/local/tmp/bookah/snapshot.db` and
 * `content-expected.json`; neither is in the repo, without them the test is skipped).
 */
@RunWith(AndroidJUnit4::class)
class ContentOnDeviceTest {
    @Test fun memoriesAndPlacesOfTheRealSnapshotMatchTheNumbersFromSql() {
        val snap = File("/data/local/tmp/bookah/snapshot.db"); val expected = File("/data/local/tmp/bookah/content-expected.json")
        assumeTrue("push snapshot.db and content-expected.json to /data/local/tmp/bookah/ to run this", snap.exists() && expected.exists())
        val exp = Json.parseToJsonElement(expected.readText()).jsonObject
        fun obj(name: String): JsonObject = exp[name]!!.jsonObject

        val t0 = System.nanoTime()
        val mem = MemoriesReader.load(snap)
        val pl = PlacesReader.load(snap)
        val ms = (System.nanoTime() - t0) / 1_000_000

        // memories: counts per type, every memory has photos, the number of photos per memory, the cover is one of them
        assertEquals(obj("memoriesByType").mapValues { it.value.jsonPrimitive.content.toInt() }, mem.all.groupingBy { it.type }.eachCount())
        val perMemory = obj("memoryAssets").mapValues { it.value.jsonPrimitive.content.toInt() }
        for (m in mem.all) {
            assertTrue("memory ${m.key} has photos", m.assetIds.isNotEmpty())
            assertEquals("photos of ${m.key}", perMemory[m.key], m.assetIds.size)
        }
        assertEquals(exp["coversInMemory"]!!.jsonPrimitive.content.toInt(), mem.all.count { it.coverAsset in it.assetIds })
        assertEquals(mem.all.size, mem.all.map { it.key }.toSet().size)                                     // keys are unique

        // places: photos per place and photos with a position
        val perPlace = obj("places").mapValues { it.value.jsonPrimitive.content.toInt() }
        assertEquals(perPlace.size, pl.places.size)
        for (p in pl.places) assertEquals("photos at ${p.id}", perPlace[p.id.toString()], p.photoCount)
        assertEquals(exp["geoPhotos"]!!.jsonPrimitive.content.toInt(), pl.photos.size)
        assertTrue(pl.places.zipWithNext().all { (a, b) -> a.photoCount >= b.photoCount })                  // most photos first
        println("CONTENT memories=${mem.all.size} places=${pl.places.size} geo=${pl.photos.size} loadMs=$ms")
        assertTrue("reading took $ms ms", ms < 2000)
    }
}
