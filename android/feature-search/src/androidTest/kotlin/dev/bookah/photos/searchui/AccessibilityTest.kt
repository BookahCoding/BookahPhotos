package dev.bookah.photos.searchui

import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.Previews
import dev.bookah.photos.search.ModelUi
import dev.bookah.photos.search.SearchActions
import dev.bookah.photos.search.SearchUi
import dev.bookah.photos.search.TagLabel
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Android's accessibility checker over the search page (ready to search, with chips and recent searches). */
class AccessibilityTest {
    @get:Rule val rule = createComposeRule()
    @Before fun on() { rule.enableAccessibilityChecks() }

    @Test fun searchPage() {
        val lib = LibraryIndex.build((0 until 30).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) })
        val ui = SearchUi(model = ModelUi.Ready, hasVectors = true, labels = listOf(TagLabel("beach", "Beach", "Παραλία", 12), TagLabel("dog", "Dog", "Σκύλος", 5)), recents = listOf("old question"))
        val actions = object : SearchActions {
            override fun submit(question: String) {}
            override fun pickCategory(label: TagLabel, greek: Boolean) {}
            override fun clearResults() {}
            override fun downloadModel() {}
            override fun retryModel() {}
            override fun clearRecents() {}
        }
        rule.setContent {
            BookahPhotosTheme {
                SearchPage(ui, lib, object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }, ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build(), actions, false, onOpen = { _, _ -> }, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(60f))
            }
        }
        rule.onRoot().tryPerformAccessibilityChecks()
    }
}
