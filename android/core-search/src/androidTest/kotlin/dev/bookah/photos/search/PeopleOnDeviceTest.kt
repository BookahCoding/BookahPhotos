package dev.bookah.photos.search

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bookah.photos.data.PeopleIndex
import dev.bookah.photos.data.PeopleReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * People of a snapshot on a real SQLite file. The first test uses generated data. The second reads a REAL snapshot and compares it with numbers made from the same
 * file by SQL (`/data/local/tmp/bookah/snapshot.db` and `people-expected.json`; neither is in the repo, without them the test is skipped).
 */
@RunWith(AndroidJUnit4::class)
class PeopleOnDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun peopleAreReadFromGeneratedSnapshot() {
        val f = File(context.cacheDir, "people-generated.db").also { it.delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL("CREATE TABLE assets (id INTEGER PRIMARY KEY)")
        db.execSQL("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT, cover_face_id INTEGER, hidden INTEGER NOT NULL, face_count INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE faces (id INTEGER PRIMARY KEY, asset_id INTEGER NOT NULL, x REAL, y REAL, w REAL, h REAL, person_id INTEGER, quality REAL)")
        for (a in listOf(1, 2, 3, 4)) db.execSQL("INSERT INTO assets VALUES ($a)")
        db.execSQL("INSERT INTO people VALUES (1,'Ann',11,0,4),(2,NULL,NULL,0,2),(3,'Hidden',30,1,1),(4,'   ',NULL,0,1)")
        // person 1: two faces in photo 1 (counts once), cover face 11; person 2: no cover chosen, the clearest face wins; face 40 is in a photo that is not in the snapshot
        db.execSQL("INSERT INTO faces VALUES (10,1,0.1,0.1,0.2,0.2,1,0.9),(11,1,0.5,0.5,0.1,0.1,1,0.7),(12,2,0.3,0.3,0.1,0.1,1,0.95),(20,3,0,0,0.1,0.1,2,0.6),(21,4,0.2,0.2,0.3,0.3,2,0.99),(30,1,0,0,0.1,0.1,3,0.9),(40,99,0,0,0.1,0.1,4,0.9),(50,2,0,0,0.1,0.1,NULL,0.9)")
        db.close()
        val idx = PeopleReader.load(f)
        assertEquals(listOf(1L, 2L), idx.people.map { it.id })                       // named first; photo-less people are gone; "   " is unnamed but has no photo here
        assertEquals(listOf(3L), idx.hiddenPeople.map { it.id })                     // hidden people are kept apart (to show them again)
        val ann = idx.byId(1)!!
        assertEquals("Ann", ann.name); assertEquals(2, ann.photoCount)               // 3 faces, 2 photos
        assertEquals(11L, ann.coverFaceId); assertEquals(0.5f, ann.box[0], 0f)       // the chosen cover, not the clearest
        val two = idx.byId(2)!!
        assertNull(two.name); assertEquals(21L, two.coverFaceId); assertEquals(4L, two.coverAssetId)
        assertEquals(true, idx.byId(3)!!.hidden); assertNull(idx.byId(4))
        assertEquals(setOf<String>(), PeopleReader.appliedOps(f))                    // no applied_ops table (a 1.1 snapshot): nothing is applied
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use { d -> d.execSQL("CREATE TABLE applied_ops (op_id TEXT PRIMARY KEY)"); d.execSQL("INSERT INTO applied_ops VALUES ('a'), ('b')") }
        assertEquals(setOf("a", "b"), PeopleReader.appliedOps(f))
    }

    @Test fun aSnapshotWithoutPeopleTablesGivesNobody() {
        val f = File(context.cacheDir, "people-old.db").also { it.delete() }
        SQLiteDatabase.openOrCreateDatabase(f, null).apply { execSQL("CREATE TABLE assets (id INTEGER PRIMARY KEY)"); close() }
        assertTrue(PeopleReader.load(f).isEmpty)
        assertEquals(PeopleIndex.EMPTY.people, PeopleReader.load(File(context.cacheDir, "does-not-exist.db")).people)
    }

    @Test fun theRealSnapshotMatchesTheNumbersFromSql() {
        val snap = File("/data/local/tmp/bookah/snapshot.db"); val expected = File("/data/local/tmp/bookah/people-expected.json")
        assumeTrue("push snapshot.db and people-expected.json to /data/local/tmp/bookah/ to run this", snap.exists() && expected.exists())
        val t0 = System.nanoTime()
        val idx = PeopleReader.load(snap)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val json = kotlinx.serialization.json.Json.parseToJsonElement(expected.readText()).let { it as kotlinx.serialization.json.JsonObject }["people"] as kotlinx.serialization.json.JsonObject
        assertEquals(json.size, idx.people.size)
        var named = 0
        for ((key, v) in json) {
            val arr = v as kotlinx.serialization.json.JsonArray
            val p = idx.byId(key.toLong())!!
            val name = (arr[0] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifBlank { null }
            assertEquals("name of $key", name, p.name)
            assertEquals("photos of $key", (arr[1] as kotlinx.serialization.json.JsonPrimitive).content.toInt(), p.photoCount)
            if (name != null) named++
        }
        // named first
        val firstUnnamed = idx.people.indexOfFirst { it.name == null }
        assertTrue(idx.people.drop(firstUnnamed).all { it.name == null }); assertEquals(named, firstUnnamed)
        val biggest = idx.people.maxByOrNull { it.photoCount }!!
        println("PEOPLE people=${idx.people.size} named=$named biggest=${biggest.photoCount} loadMs=$ms")
        assertTrue("reading all people took $ms ms", ms < 1500)
    }
}
