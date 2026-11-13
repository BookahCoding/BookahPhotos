package dev.bookah.photos.search

import android.database.sqlite.SQLiteDatabase
import android.util.Half
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on an emulator or a phone. The first test builds a real SQLite snapshot (generated data) and reads it with the real code. The second runs the real
 * text model with ONNX Runtime and must reproduce the PC's question vectors; it needs the model files, which are NOT in the app or the repo: push them with
 * `adb push text_small.onnx text_tokenizer.bin /data/local/tmp/bookah/` (made by `bookahphotos-indexer publish-models --build-only`); without them it is skipped.
 */
@RunWith(AndroidJUnit4::class)
class SearchOnDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    private fun half(f: Float): ByteArray { val s = Half.toHalf(f).toInt(); return byteArrayOf((s and 0xFF).toByte(), ((s shr 8) and 0xFF).toByte()) }

    @Test fun snapshotVectorsAndTagsAreReadFromARealSqliteFile() {
        val f = File(context.cacheDir, "generated-snapshot.db").also { it.delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL("CREATE TABLE asset_vectors (asset_id INTEGER PRIMARY KEY, model_id TEXT NOT NULL, dim INTEGER NOT NULL, vec BLOB NOT NULL)")
        db.execSQL("CREATE TABLE labels (key TEXT PRIMARY KEY, name_en TEXT NOT NULL, name_el TEXT NOT NULL)")
        db.execSQL("CREATE TABLE tags (asset_id INTEGER NOT NULL, label_key TEXT NOT NULL, score REAL NOT NULL, PRIMARY KEY (asset_id, label_key))")
        val rows = mapOf(100L to floatArrayOf(1f, 0f, 0f, 0f), 101L to floatArrayOf(0.8f, 0.6f, 0f, 0f), 102L to floatArrayOf(0f, 1f, 0f, 0f), 103L to floatArrayOf(0f, 0f, 0f, 1f))
        for ((id, v) in rows) db.execSQL("INSERT INTO asset_vectors VALUES (?,?,?,?)", arrayOf(id, "m1", 4, v.flatMap { half(it).toList() }.toByteArray()))
        db.execSQL("INSERT INTO labels VALUES ('beach','Beach','Παραλία'), ('dog','Dog','Σκύλος'), ('unused','Unused','Άχρηστο')")
        db.execSQL("INSERT INTO tags VALUES (100,'beach',0.31),(101,'beach',0.25),(102,'dog',0.4),(103,'beach',0.2)")
        db.close()

        val v = VectorIndex.load(f)!!
        assertEquals("m1", v.modelId); assertEquals(4, v.dim); assertEquals(4, v.size)
        assertEquals(listOf(100L, 101L), v.search(floatArrayOf(1f, 0f, 0f, 0f), 2).map { it.id })
        assertEquals(listOf(102L, 101L), v.search(floatArrayOf(0f, 1f, 0f, 0f), 2).map { it.id })

        val t = TagIndex.load(f)
        assertEquals(listOf("beach", "dog"), t.labels.map { it.key })                              // the label without a photo is not offered; most photos first
        assertEquals(3, t.labels[0].count)
        assertEquals("Παραλία", t.labels[0].name(greek = true)); assertEquals("Beach", t.labels[0].name(greek = false))
        assertEquals(listOf(100L, 101L, 103L), t.filesFor("beach").toList())                       // best score first
        assertEquals(0, t.filesFor("nothing").size)
    }

    @Test fun aSnapshotWithoutVectorsGivesNoIndexAndNoTags() {
        val f = File(context.cacheDir, "old-snapshot.db").also { it.delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL("CREATE TABLE assets (id INTEGER PRIMARY KEY)")
        db.close()
        assertEquals(null, VectorIndex.load(f))
        assertTrue(TagIndex.load(f).labels.isEmpty())
    }

    /** The whole chain on the real model with made-up "photos" (a vector per English description): Greek and English questions must find the right one. */
    @Test fun greekQuestionsFindTheMatchingPhotoThroughTheRealModel() {
        val dir = listOf(File("/data/local/tmp/bookah"), File(context.filesDir, "bookah")).firstOrNull { File(it, "text_small.onnx").canRead() && File(it, "text_tokenizer.bin").canRead() }
        assumeTrue("model files not on this device", dir != null)
        val tokenizer = PhoneTokenizer.load(File(dir, "text_tokenizer.bin"))
        val manifest = ModelManifest(1, "m1", "d", "", TextModelInfo(), listOf(ModelFile("text_small.onnx", 0, ""), ModelFile("text_tokenizer.bin", 0, "")))
        val encoder = OnnxQueryEncoder(InstalledModel(manifest, File(dir, "text_small.onnx"), File(dir, "text_tokenizer.bin")), tokenizer)
        val photos = listOf("a photo of a dog", "a photo of a beach", "a photo of snow", "a photo of a birthday cake", "a photo of a mountain", "a photo of a meal of food",
            "a photo of a car", "a photo of a church", "a photo of flowers", "a photo of a sunset", "a photo of a baby", "a photo of a cat")
        val vecs = photos.map { encoder.encode(it) }
        val index = VectorIndex.of("m1", vecs[0].size, LongArray(photos.size) { 1000L + it }, vecs.flatMap { it.toList() }.toFloatArray())
        val engine = SearchEngine(index, "m1", encoder)
        val expected = mapOf("σκύλος" to 0, "παραλία" to 1, "χιόνι" to 2, "τούρτα" to 3, "βουνό" to 4, "φαγητό" to 5, "αυτοκίνητο" to 6, "εκκλησία" to 7, "λουλούδια" to 8,
            "ηλιοβασίλεμα" to 9, "μωρό" to 10, "γάτα" to 11, "dog" to 0, "beach" to 1, "snow" to 2, "mountain" to 4, "church" to 7)
        val wrong = expected.filter { (q, i) -> engine.search(q, 1).first().id != 1000L + i }.keys
        assertTrue("wrong top result for: $wrong", wrong.size <= 1)                              // quantised model: allow one near miss, report it
        android.util.Log.i("SearchOnDeviceTest", "cross-language check: ${expected.size - wrong.size} of ${expected.size} right; wrong: $wrong")
        encoder.close()
    }

    @Serializable class Expected(val text: String, val ids: IntArray, val vec: FloatArray)
    @Serializable class ExpectedFile(val dim: Int, val cases: List<Expected>)

    @Test fun theRealTextModelOnThePhoneReproducesThePcsQuestionVectors() {
        val dir = listOf(File("/data/local/tmp/bookah"), File(context.filesDir, "bookah")).firstOrNull { File(it, "text_small.onnx").canRead() && File(it, "text_tokenizer.bin").canRead() }
        assumeTrue("model files not on this device", dir != null)
        val tokenizer = PhoneTokenizer.load(File(dir, "text_tokenizer.bin"))
        val manifest = ModelManifest(1, "m1", "d", "", TextModelInfo(), listOf(ModelFile("text_small.onnx", 0, ""), ModelFile("text_tokenizer.bin", 0, "")))
        val encoder = OnnxQueryEncoder(InstalledModel(manifest, File(dir, "text_small.onnx"), File(dir, "text_tokenizer.bin")), tokenizer)
        val expected = Json.decodeFromString<ExpectedFile>(testContext.assets.open("encoder_expected.json").readBytes().toString(Charsets.UTF_8))
        val millis = ArrayList<Long>()
        val cosines = ArrayList<Double>()
        for (c in expected.cases) {
            assertEquals("token ids of '${c.text}'", c.ids.toList(), tokenizer.encode(c.text).toList())      // the tokenizer part must be EXACT
            val t0 = System.nanoTime()
            val v = encoder.encode(c.text)
            millis.add((System.nanoTime() - t0) / 1_000_000)
            var dot = 0.0
            for (j in v.indices) dot += v[j] * c.vec[j]
            cosines.add(dot)
        }
        android.util.Log.i("SearchOnDeviceTest", "cosines to the PC: ${cosines.map { "%.4f".format(it) }}; question times ms: ${millis.sorted()} (median ${millis.sorted()[millis.size / 2]})")
        // The model is int8-quantized, so another CPU (an x86 emulator, an ARM phone) rounds a little differently: Phase 0 measured mean 0.9993 and minimum 0.9935 on the phone.
        assertTrue("worst cosine ${cosines.min()}", cosines.min() > 0.99)
        assertTrue("mean cosine ${cosines.average()}", cosines.average() > 0.997)
        encoder.close()
    }
}
