package dev.bookah.photos.search

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * The Kotlin tokenizer must give EXACTLY the ids of the PC's tokenizer. The expected ids in the fixtures come from the Hugging Face library (not from our
 * own code): `bookahphotos-indexer phone-fixtures`. The tokenizer file is the one the phone downloads (`publish-models --build-only` makes it); tests are
 * skipped on a machine that does not have it.
 */
class PhoneTokenizerTest {
    @Serializable class Case(val text: String, val ids: IntArray)
    @Serializable class Fixtures(val seq: Int, val pad: Int, val cases: List<Case>)

    private fun expected(c: Case): IntArray = IntArray(PhoneTokenizer.SEQ) { if (it < c.ids.size) c.ids[it] else PhoneTokenizer.PAD }

    private fun check(fixtures: Fixtures) {
        assertEquals(PhoneTokenizer.SEQ, fixtures.seq)
        val bad = fixtures.cases.filter { !it.let { c -> expected(c).contentEquals(tokenizer.encode(c.text)) } }
        if (bad.isNotEmpty()) {
            val c = bad.first()
            fail("${bad.size} of ${fixtures.cases.size} differ; first: '${c.text.take(60)}' (${c.text.map { it.code.toString(16) }.take(12)}) " +
                "expected ${expected(c).take(14)} got ${tokenizer.encode(c.text).take(14)}")
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    @Test fun committedFixturesMatchThePcTokenizerExactly() {
        assumeTrue(assetFile.isFile)
        val text = javaClass.getResourceAsStream("/tokenizer_fixtures.json")!!.readBytes().toString(Charsets.UTF_8)
        val fixtures = Json.decodeFromString<Fixtures>(text)
        assertTrue(fixtures.cases.size > 3000)
        check(fixtures)
    }

    @Test fun sixtyThousandFuzzedStringsMatch() {
        assumeTrue(assetFile.isFile && bigFuzz.isFile)
        check(Json.decodeFromString<Fixtures>(bigFuzz.readText()))
    }

    @Test fun shapeAndSimpleCases() {
        assumeTrue(assetFile.isFile)
        val empty = tokenizer.encode("")
        assertEquals(PhoneTokenizer.SEQ, empty.size)
        assertEquals(listOf(PhoneTokenizer.BOS, PhoneTokenizer.EOS), empty.take(2))
        assertTrue(empty.drop(2).all { it == PhoneTokenizer.PAD })
        val long = tokenizer.encode("παραλία ".repeat(200))
        assertEquals(PhoneTokenizer.BOS, long.first())
        assertEquals(PhoneTokenizer.EOS, long.last())                                   // cut, but still closed with </s>
        val dog = tokenizer.encode("dog")
        assertEquals(PhoneTokenizer.BOS, dog[0])
        assertTrue(dog.contains(PhoneTokenizer.EOS))
    }

    @Test fun greekWithAndWithoutAccentsAndDecomposedAccentsGiveTheSameIds() {
        assumeTrue(assetFile.isFile)
        assertEquals(tokenizer.encode("σκύλος").toList(), tokenizer.encode("σκυ\u0301λος").toList())          // decomposed tonos is composed by the normalizer
        assertTrue(tokenizer.encode("σκύλος").toList() != tokenizer.encode("σκυλος").toList())               // but the accent is NOT dropped
    }

    @Test fun whitespaceIsNotSignificant() {
        assumeTrue(assetFile.isFile)
        assertEquals(tokenizer.encode("dog on the beach").toList(), tokenizer.encode("  dog\ton \u00a0the   beach \n").toList())
    }

    companion object {
        private val assetFile = File(System.getenv("BOOKAH_TOKENIZER_ASSET") ?: "C:\\BookahPC\\bookahphotos\\models-build\\text_tokenizer.bin")
        private val bigFuzz = File(System.getenv("BOOKAH_TOKENIZER_FUZZ") ?: "C:\\BookahPC\\bookahphotos\\models-build\\tokenizer_fuzz_big.json")
        private lateinit var tokenizer: PhoneTokenizer

        @JvmStatic @BeforeClass fun load() {
            if (assetFile.isFile) tokenizer = PhoneTokenizer.load(assetFile)
        }
    }
}
