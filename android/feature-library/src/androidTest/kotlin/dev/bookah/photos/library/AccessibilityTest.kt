package dev.bookah.photos.library

import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.design.BookahPhotosTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Android's accessibility checker over the Library: labels for tiles and buttons, touch targets, contrast (also in select mode). */
class AccessibilityTest {
    @get:Rule val rule = createComposeRule()
    @Before fun on() { rule.enableAccessibilityChecks() }

    private val lib = LibraryIndex.build((0 until 200).map { AssetRow(1000L + it, 20240101 + it % 28, it.toLong(), if (it % 13 == 0) 1 else 0, null, it, false, if (it % 13 == 0) 5_000 else 0) })

    private fun show() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent {
            BookahPhotosTheme {
                LibraryScreen(lib, object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }, ImageLoader.Builder(ctx).build(), {}, {}, {}, startAtItem = 100, onDelete = {}, onHide = {}, onFavourite = { _, _ -> }, onAddToAlbum = {})
            }
        }
    }

    @Test fun library() { show(); rule.onRoot().tryPerformAccessibilityChecks() }

    @Test fun libraryInSelectMode() {
        show()
        rule.onNodeWithText("Select").performClick()
        rule.onRoot().tryPerformAccessibilityChecks()
    }
}
