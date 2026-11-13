package dev.bookah.photos.search

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class SearchCoreTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- float16 ----
    @Test fun halfNumbersDecodeLikeNumpy() {
        // values written by numpy.float16(x).view(uint16)
        val cases = mapOf(0x0000 to 0.0f, 0x8000 to -0.0f, 0x3C00 to 1.0f, 0xBC00 to -1.0f, 0x3800 to 0.5f, 0x4000 to 2.0f, 0x3555 to 0.333251953125f,
            0x0001 to 5.960464477539063e-08f, 0x03FF to 6.097555160522461e-05f, 0x0400 to 6.103515625e-05f, 0x7BFF to 65504.0f, 0x2E66 to 0.0999755859375f,
            0xB266 to -0.199951171875f, 0x5640 to 100.0f, 0x1234 to 0.0007572174072265625f, 0xABCD to -0.060943603515625f)
        for ((bits, expected) in cases) assertEquals("0x%04X".format(bits), expected, halfToFloat(bits), 0f)
        assertTrue(halfToFloat(0x7C00).isInfinite())
        assertTrue(halfToFloat(0x7E00).isNaN())
    }

    // ---- vector search ----
    private fun unit(vararg v: Float): FloatArray { val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(v.size) { v[it] / n } }

    private fun index(): VectorIndex {
        val rows = listOf(unit(1f, 0f, 0f), unit(0.9f, 0.1f, 0f), unit(0f, 1f, 0f), unit(0f, 0f, 1f), unit(0.7f, 0.7f, 0f))
        return VectorIndex.of("m1", 3, longArrayOf(10, 11, 12, 13, 14), rows.flatMap { it.toList() }.toFloatArray())
    }

    @Test fun searchReturnsTheBestFirstAndRespectsK() {
        val hits = index().search(unit(1f, 0f, 0f), 3)
        assertEquals(listOf(10L, 11L, 14L), hits.map { it.id })
        assertTrue(hits[0].score >= hits[1].score && hits[1].score >= hits[2].score)
        assertEquals(1f, hits[0].score, 1e-6f)
        assertEquals(5, index().search(unit(1f, 1f, 1f), 99).size)                      // k larger than the library
        assertEquals(0, index().search(unit(1f, 0f, 0f), 0).size)
    }

    @Test fun searchMatchesABruteForceSortOnRandomData() {
        val rnd = java.util.Random(4)
        val dim = 16; val n = 700
        val data = FloatArray(n * dim) { rnd.nextGaussian().toFloat() }
        for (i in 0 until n) { val v = unit(*FloatArray(dim) { data[i * dim + it] }); for (j in 0 until dim) data[i * dim + j] = v[j] }
        val idx = VectorIndex.of("m", dim, LongArray(n) { it.toLong() }, data)
        val q = unit(*FloatArray(dim) { rnd.nextGaussian().toFloat() })
        val brute = (0 until n).sortedByDescending { i -> (0 until dim).sumOf { (data[i * dim + it] * q[it]).toDouble() } }.take(50)
        assertEquals(brute.map { it.toLong() }, idx.search(q, 50).map { it.id })
    }

    @Test fun aQueryOfTheWrongSizeIsRefused() {
        try { index().search(FloatArray(5), 3); fail() } catch (_: IllegalArgumentException) {}
    }

    // ---- the engine refuses to mix models ----
    private class Fake(val v: FloatArray) : QueryEncoder { override fun encode(text: String) = v }

    @Test fun engineBlocksWhenTheModelIsMissingOrTheVectorsComeFromAnotherModel() {
        val q = Fake(unit(1f, 0f, 0f))
        assertEquals(SearchBlock.NoModel, SearchEngine(index(), null, null).blocked())
        assertEquals(SearchBlock.NoVectors, SearchEngine(null, "m1", q).blocked())
        assertEquals(SearchBlock.WrongModel, SearchEngine(index(), "other-model", q).blocked())
        assertTrue(SearchEngine(index(), "other-model", q).search("dog").isEmpty())            // no silent nonsense
        val ok = SearchEngine(index(), "m1", q)
        assertNull(ok.blocked())
        assertEquals(10L, ok.search("  dog  ", 2).first().id)
        assertTrue(ok.search("   ").isEmpty())
    }

    // ---- model manifest, store and download ----
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val onnx = ByteArray(5000) { (it % 251).toByte() }
    private val tok = ByteArray(300) { (it % 7).toByte() }

    private fun manifestJson(dir: String = "xlmr-v1", model: String = "m1", extra: String = "", onnxSize: Int = onnx.size, onnxSha: String = sha(onnx)) =
        """{"version":1,"model_id":"$model","dir":"$dir","created_utc":"2026-11-13T10:00:00+00:00","text_model":{"input":"text","input_type":"int32","seq":77,"output_dim":512},
        "files":[{"name":"text_small.onnx","size":$onnxSize,"sha256":"$onnxSha"},{"name":"text_tokenizer.bin","size":${tok.size},"sha256":"${sha(tok)}"}]$extra}"""

    @Test fun manifestParsesAndRefusesPathsInNames() {
        val m = ModelManifest.parse(manifestJson(extra = ""","something_new":1"""))                // unknown fields are ignored
        assertEquals("m1", m.modelId); assertEquals(5300L, m.totalSize)
        for (bad in listOf("../evil", "a/b", "", "x y")) {
            try { ModelManifest.parse(manifestJson(dir = bad)); fail("accepted dir '$bad'") } catch (_: IllegalArgumentException) {} catch (_: kotlinx.serialization.SerializationException) {}
        }
    }

    @Test fun installVerifiesEveryFileAndWritesThePointerLast() {
        val store = ModelStore(tmp.newFolder("models"))
        assertNull(store.installed())
        val m = ModelManifest.parse(manifestJson())
        val progress = ArrayList<Long>()
        store.install(m, { f -> ByteArrayInputStream(if (f.name == "text_small.onnx") onnx else tok) }) { done, _ -> progress.add(done) }
        val got = store.installed()
        assertNotNull(got); assertEquals("m1", got!!.manifest.modelId)
        assertTrue(got.onnx.readBytes().contentEquals(onnx) && got.tokenizer.readBytes().contentEquals(tok))
        assertEquals(5300L, progress.last())
    }

    @Test fun aDamagedDownloadNeverReplacesAWorkingModel() {
        val store = ModelStore(tmp.newFolder("models"))
        store.install(ModelManifest.parse(manifestJson()), { f -> ByteArrayInputStream(if (f.name == "text_small.onnx") onnx else tok) })
        val broken = onnx.copyOf().also { it[10] = 99 }                                           // right size, wrong content
        try {
            store.install(ModelManifest.parse(manifestJson(dir = "xlmr-v2", model = "m2")), { f -> ByteArrayInputStream(if (f.name == "text_small.onnx") broken else tok) })
            fail("accepted a damaged file")
        } catch (_: ModelStore.BadDownload) {}
        assertEquals("m1", store.installed()!!.manifest.modelId)                                  // the old one still works
        try {
            store.install(ModelManifest.parse(manifestJson(dir = "xlmr-v3")), { f -> ByteArrayInputStream(if (f.name == "text_small.onnx") onnx.copyOf(4000) else tok) })
            fail("accepted a short file")
        } catch (_: ModelStore.BadDownload) {}
        assertEquals("m1", store.installed()!!.manifest.modelId)
    }

    @Test fun aMissingFileOnDiskMeansNotInstalledAndDeleteRemovesEverything() {
        val dir = tmp.newFolder("models")
        val store = ModelStore(dir)
        store.install(ModelManifest.parse(manifestJson()), { f -> ByteArrayInputStream(if (f.name == "text_small.onnx") onnx else tok) })
        java.io.File(dir, "xlmr-v1/text_small.onnx").delete()
        assertNull(store.installed())
        store.install(ModelManifest.parse(manifestJson()), { f -> ByteArrayInputStream(if (f.name == "text_small.onnx") onnx else tok) })
        assertNotNull(store.installed())
        store.delete()
        assertNull(store.installed()); assertEquals(0L, store.sizeOnDisk())
    }

    @Test fun syncDownloadsFromNextcloudWithTheSignInAndSavesTheModel() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                    request.headers["Authorization"] != "Basic dGVzdDpwdw==" -> MockResponse().setResponseCode(401)
                    request.path!!.endsWith("/BookahPhotos/models/manifest.json") -> MockResponse().setBody(manifestJson())
                    request.path!!.endsWith("/BookahPhotos/models/xlmr-v1/text_small.onnx") -> MockResponse().setBody(okio.Buffer().write(onnx))
                    request.path!!.endsWith("/BookahPhotos/models/xlmr-v1/text_tokenizer.bin") -> MockResponse().setBody(okio.Buffer().write(tok))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val session = dev.bookah.photos.data.Session(server.url("/").toString().trimEnd('/'), "test", "pw")
            val store = ModelStore(tmp.newFolder("models"))
            val sync = ModelSync(OkHttpClient(), session, store)
            val m = sync.fetchManifest()!!
            sync.download(m) { _, _ -> }
            assertEquals("m1", store.installed()!!.manifest.modelId)
            val seen = ArrayList<String>()
            repeat(server.requestCount) { seen.add(server.takeRequest().path!!) }
            assertFalse(seen.any { it.contains("..") })
        } finally { server.shutdown() }
    }

    @Test fun noManifestMeansNoModelYet() = runTest {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(404)); server.start()
        try {
            val session = dev.bookah.photos.data.Session(server.url("/").toString().trimEnd('/'), "test", "pw")
            assertNull(ModelSync(OkHttpClient(), session, ModelStore(tmp.newFolder("m"))).fetchManifest())
        } finally { server.shutdown() }
    }
}
