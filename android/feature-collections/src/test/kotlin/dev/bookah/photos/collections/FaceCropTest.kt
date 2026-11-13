package dev.bookah.photos.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceCropTest {
    @Test fun `the crop is a square around the face, 1_6 times its size`() {
        // a face 100 px wide in the middle of a 1000 x 800 picture
        val sq = FaceCrop.square(floatArrayOf(0.45f, 0.4f, 0.1f, 0.125f), 1000, 800)
        assertEquals(160, sq[2])
        assertEquals(500 - 80, sq[0]); assertEquals(370 - 80, sq[1])
    }

    @Test fun `a face at the edge is moved inside the picture, never past it`() {
        val sq = FaceCrop.square(floatArrayOf(0f, 0f, 0.1f, 0.1f), 1000, 1000)
        assertEquals(0, sq[0]); assertEquals(0, sq[1])
        val far = FaceCrop.square(floatArrayOf(0.92f, 0.9f, 0.08f, 0.1f), 1000, 1000)
        assertTrue(far[0] + far[2] <= 1000 && far[1] + far[2] <= 1000)
    }

    @Test fun `a huge face is limited to the short side of the picture`() {
        val sq = FaceCrop.square(floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f), 1000, 600)
        assertEquals(600, sq[2]); assertEquals(0, sq[1])
    }

    @Test fun `small faces ask for a bigger preview, in steps, up to 1024`() {
        assertEquals(256, FaceCrop.previewSize(0.5f, 0.5f))
        assertEquals(1024, FaceCrop.previewSize(0.05f, 0.05f))
        assertEquals(1024, FaceCrop.previewSize(0.001f, 0.001f))
        val mid = FaceCrop.previewSize(0.2f, 0.2f)
        assertTrue(mid in listOf(256, 512, 768) && mid % 256 == 0)
    }
}
