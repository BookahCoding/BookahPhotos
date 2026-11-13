package dev.bookah.photos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbHashTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Hashes made by the indexer's own encoder from generated gradient images (red grows to the right, green downwards).
    // Size and average colour were checked against the Python ThumbHash library (its aspect ratio and average colour functions).
    private val landscape = "61f7093d9a8087877078788888788888707007f878"   // 60 x 40 image: ratio 1.4, average (0.489, 0.505, 0.577)
    private val portrait = "61070a3d1a8087707888877888887888708007f878"    // 40 x 60 image: ratio 0.714, average (0.505, 0.489, 0.577)
    private val square = "61070a3f1a80878780888888887888888887080777808f07" // 50 x 50

    @Test fun `size follows the aspect ratio`() {
        val l = ThumbHash.decode(hex(landscape))!!
        assertEquals(32 to 23, l.width to l.height)
        val p = ThumbHash.decode(hex(portrait))!!
        assertEquals(23 to 32, p.width to p.height)
        val s = ThumbHash.decode(hex(square))!!
        assertEquals(32 to 32, s.width to s.height)
        assertEquals(1.4f, ThumbHash.approximateAspectRatio(hex(landscape)), 0.001f)
    }

    private fun mean(img: ThumbImage): Triple<Double, Double, Double> {
        var r = 0.0; var g = 0.0; var b = 0.0
        for (px in img.argb) { r += (px shr 16) and 255; g += (px shr 8) and 255; b += px and 255 }
        val n = img.argb.size * 255.0
        return Triple(r / n, g / n, b / n)
    }

    @Test fun `average colour matches the reference`() {
        val (r, g, b) = mean(ThumbHash.decode(hex(landscape))!!)
        assertEquals(0.489, r, 0.03); assertEquals(0.505, g, 0.03); assertEquals(0.577, b, 0.03)
        val (r2, g2, b2) = mean(ThumbHash.decode(hex(portrait))!!)
        assertEquals(0.505, r2, 0.03); assertEquals(0.489, g2, 0.03); assertEquals(0.577, b2, 0.03)
    }

    @Test fun `the picture is not flat and keeps the gradient direction`() {
        val img = ThumbHash.decode(hex(landscape))!!
        fun red(x: Int, y: Int) = (img.argb[y * img.width + x] shr 16) and 255
        fun green(x: Int, y: Int) = (img.argb[y * img.width + x] shr 8) and 255
        assertTrue("red grows to the right", red(img.width - 2, img.height / 2) > red(1, img.height / 2) + 40)
        assertTrue("green grows downwards", green(img.width / 2, img.height - 2) > green(img.width / 2, 1) + 40)
        assertTrue(img.argb.all { (it ushr 24) == 255 })
    }

    @Test fun `short or empty hashes are refused, not crashed on`() {
        assertNull(ThumbHash.decode(ByteArray(0)))
        assertNull(ThumbHash.decode(ByteArray(3)))
        assertNotNull(ThumbHash.decode(hex(landscape).copyOf(8)))   // truncated: still returns something, reading missing bytes as zero
    }
}
