package dev.bookah.photos.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestTest {
    /** The manifest as the indexer wrote it (generation 3 of the first real index, copied from the server; it holds counts only). */
    private val real = "{\n  \"counts\": {\n    \"assets\": 24703,\n    \"faces\": 0,\n    \"people\": 0,\n    \"photos\": 22710,\n    \"videos\": 1993\n  },\n" +
        "  \"created_utc\": \"2026-11-11T19:18:07+00:00\",\n  \"file\": \"bookahphotos-g000003.db\",\n  \"generation\": 3,\n" +
        "  \"models\": {\n    \"face_model\": \"planned\",\n    \"search_model\": \"planned\"\n  },\n  \"schema_major\": 1,\n  \"schema_minor\": 0,\n" +
        "  \"sha256\": \"8835fed93baae14bdf8dae1ba7e8b485ca0e12789f0e5533374916fa8150d897\",\n  \"size\": 8478720\n}\n"

    @Test fun `parses the manifest the indexer really publishes`() {
        val m = Manifest.parse(real)
        assertEquals(3L, m.generation)
        assertEquals("bookahphotos-g000003.db", m.file)
        assertEquals(8_478_720L, m.size)
        assertEquals(1, m.schemaMajor)
        assertEquals(24_703, m.counts.assets)
        assertEquals(1_993, m.counts.videos)
    }
}
