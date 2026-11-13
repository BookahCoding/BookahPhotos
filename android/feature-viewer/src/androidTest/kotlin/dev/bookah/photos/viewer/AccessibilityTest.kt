package dev.bookah.photos.viewer

import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.LibraryController
import dev.bookah.photos.library.Previews
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Android's accessibility checker over the photo viewer with every button on. */
class AccessibilityTest {
    @get:Rule val rule = createComposeRule()
    @Before fun on() { rule.enableAccessibilityChecks() }

    @Test fun viewerWithAllButtons() {
        val lib = LibraryIndex.build((0 until 5).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) })
        val env = ViewerEnv(
            previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" },
            loader = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build(),
            okHttp = okhttp3.OkHttpClient(), originalUrl = { "http://127.0.0.1:9/o/$it" }, details = { null },
            onHide = {}, onFavourite = { _, _ -> }, onAddToAlbum = {}, onRemoveFromAlbum = {},
        )
        rule.setContent { BookahPhotosTheme { ViewerOverlay(lib, 2, env, LibraryController(), onShare = {}, onClose = {}) } }
        rule.onRoot().tryPerformAccessibilityChecks()
    }
}
