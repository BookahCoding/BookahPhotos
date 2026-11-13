package dev.bookah.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Thumbnails made from originals, with generated pictures only (no network: the "download" is a made-up stream). */
class FallbackThumbsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun jpeg(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.MAGENTA)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun fresh(): File = File(context.cacheDir, "fallback-thumbs").also { it.deleteRecursively() }

    @Test fun anOriginalBecomesASmallSquareThumbnailAndIsKept() = runBlocking {
        fresh()
        var downloads = 0
        val thumbs = FallbackThumbs(context, { downloads++; ByteArrayInputStream(jpeg(4000, 3000)) }, { "https://example.invalid/o/$it" })
        val f = thumbs.get(77, 256)!!
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        assertEquals(256 to 256, opts.outWidth to opts.outHeight)
        assertNotNull(thumbs.get(77, 256))
        assertEquals(1, downloads)                    // the second time it is a local file
        assert(f.length() < 60_000)
    }

    @Test fun anUndecodableFileIsRememberedAndNotTriedAgain() = runBlocking {
        fresh()
        var downloads = 0
        val thumbs = FallbackThumbs(context, { downloads++; ByteArrayInputStream("IIRO not an image".toByteArray()) }, { "https://example.invalid/o/$it" })
        assertNull(thumbs.get(78, 256))
        assertNull(thumbs.get(78, 256))
        assertEquals(1, downloads)
    }

    @Test fun noAddressOrADownloadFailureGivesNothing() = runBlocking {
        fresh()
        assertNull(FallbackThumbs(context, { null }, { null }).get(79, 256))
        assertNull(FallbackThumbs(context, { throw java.io.IOException("offline") }, { "https://example.invalid/x" }).get(80, 256))
        // a network failure is not remembered: a later try can succeed
        assertNotNull(FallbackThumbs(context, { ByteArrayInputStream(jpeg(640, 480)) }, { "https://example.invalid/x" }).get(80, 256))
    }
}
