package dev.bookah.photos.library

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.LocalMerge
import dev.bookah.photos.data.LocalPhotosReader
import dev.bookah.photos.data.Source
import dev.bookah.photos.design.BookahPhotosTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Photos of this phone, on a device or emulator with generated pictures only: they are put into MediaStore by the test itself (so no
 * permission is needed to read them back), read by the real reader, merged with a made-up index and shown in the real Library screen.
 */
class LocalPhotosTest {
    @get:Rule val rule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val inserted = mutableListOf<android.net.Uri>()

    private fun jpeg(colour: Int, w: Int = 320, h: Int = 240): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(colour)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun insert(name: String, colour: Int, folder: String = "BookahTest"): Int {
        val bytes = jpeg(colour)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$folder")
            put(MediaStore.Images.Media.DATE_TAKEN, 1_767_265_200_000L)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        inserted.add(uri)
        return bytes.size
    }

    @After fun cleanUp() { inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } } }

    @Test fun phonePhotosAreReadMergedAndShown() {
        val matchedSize = insert("bk_match_1.JPG", Color.RED)
        insert("bk_only_a.jpg", Color.GREEN)
        insert("Screenshot_2026-11-12-06-36-55.jpg", Color.BLUE, folder = "BookahShots")

        // MediaStore fills in size and dimensions a moment after an insert: wait for it, as the app does when the observer fires
        var locals = emptyList<dev.bookah.photos.data.LocalRow>()
        for (attempt in 0 until 30) {
            locals = LocalPhotosReader.read(context).filter { it.folder.startsWith("Bookah") }
            if (locals.size == 3 && locals.all { it.size > 0 }) break
            Thread.sleep(300)
        }
        assertEquals(3, locals.size)
        assertTrue(locals.all { it.uri.startsWith("content://") && it.size > 0 })

        // a made-up index that holds the same file (name in another case, same size) and an unrelated photo
        val index = listOf(
            AssetRow(500, 20260101, 1_767_265_200L, 0, null, 1, false, name = "bk_match_1.jpg", size = matchedSize.toLong()),
            AssetRow(501, 20250101, 1_735_689_600L, 0, null, 2, false, name = "other.jpg", size = 1234),
        )
        val merged = LocalMerge.merge(index, locals)
        assertEquals(1, merged.stats.matched)
        assertEquals(2, merged.stats.phoneOnly)
        assertEquals(setOf("BookahTest", "BookahShots"), merged.stats.perFolder.keys)

        val lib = LibraryIndex.build(merged.rows)
        assertEquals(4, lib.size)                                             // one cell per photo, never two
        assertEquals(3, lib.bySource(Source.Phone).size)
        assertEquals(2, lib.bySource(Source.Nextcloud).size)
        assertTrue(lib.hasLocalCopy(lib.indexOfId(500)!!))

        rule.setContent {
            BookahPhotosTheme {
                LibraryScreen(lib, object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }, ImageLoader.Builder(context).build(), {}, {}, {}, showSource = true)
            }
        }
        rule.waitForIdle(); Thread.sleep(1500)               // the system makes the thumbnails
        rule.onNodeWithText("Library").assertIsDisplayed()
        val dir = context.getExternalFilesDir(null)!!
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(dir, "library-local.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
