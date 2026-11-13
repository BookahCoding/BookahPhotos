package dev.bookah.photos.searchui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.Previews
import dev.bookah.photos.search.ModelUi
import dev.bookah.photos.search.SearchActions
import dev.bookah.photos.search.SearchBlock
import dev.bookah.photos.search.SearchResultsUi
import dev.bookah.photos.search.SearchUi
import dev.bookah.photos.search.TagLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The search page on an emulator or phone with generated data only (no network: tiles stay placeholders). */
class SearchPageTest {
    @get:Rule val rule = createComposeRule()

    private val hash = "61f7093d9a8087877078788888788888707007f878".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }
    private val lib = LibraryIndex.build((0 until 60).map { AssetRow(100L + it, 20240101 + it % 20, it.toLong(), 0, hash, it, false) })
    private val beach = TagLabel("beach", "Beach", "Παραλία", 12)
    private val dog = TagLabel("dog", "Dog", "Σκύλος", 5)

    private class Recorder : SearchActions {
        val submitted = ArrayList<String>(); val picked = ArrayList<String>(); var cleared = 0; var downloads = 0; var retries = 0
        override fun submit(question: String) { submitted.add(question) }
        override fun pickCategory(label: TagLabel, greek: Boolean) { picked.add(label.name(greek)) }
        override fun clearResults() { cleared++ }
        override fun downloadModel() { downloads++ }
        override fun retryModel() { retries++ }
        override fun clearRecents() {}
    }

    private var ui by mutableStateOf(SearchUi())
    private val actions = Recorder()
    private var opened: Pair<Int, List<Long>>? = null
    private var backs = 0

    private fun show(greek: Boolean = false) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent {
            BookahPhotosTheme {
                SearchPage(ui, lib, previews, ImageLoader.Builder(ctx).build(), actions, greek, onOpen = { i, ids -> opened = i to ids.toList() }, onBack = { backs++ }, bottomPadding = androidx.compose.ui.unit.Dp(60f))
            }
        }
    }

    private val ready = SearchUi(model = ModelUi.Ready, hasVectors = true, labels = listOf(beach, dog), recents = listOf("old question"))

    @Test fun offersTheDownloadWithItsSizeAndStartsItOnTap() {
        ui = SearchUi(model = ModelUi.Available(158_000_000L), labels = listOf(beach))
        show()
        rule.onNodeWithTag("model-card").assertIsDisplayed()
        rule.onNodeWithText("Download").performClick()
        assertEquals(1, actions.downloads)
        rule.onNodeWithText("Beach", substring = true).assertIsDisplayed()                       // the categories work without the model
    }

    @Test fun showsProgressWhileDownloading() {
        ui = SearchUi(model = ModelUi.Downloading(79_000_000L, 158_000_000L))
        show()
        rule.onNodeWithText("Downloading", substring = true).assertIsDisplayed()
    }

    @Test fun explainsWhyContentSearchIsOffInWords() {
        ui = SearchUi(model = ModelUi.NotPublished); show()
        rule.onNodeWithText("not in your Nextcloud yet", substring = true).assertIsDisplayed()
    }

    @Test fun saysWhenTheIndexAndTheModelDoNotMatch() {
        ui = SearchUi(model = ModelUi.Ready, hasVectors = true, blocked = SearchBlock.WrongModel); show()
        rule.onNodeWithText("different versions", substring = true).assertIsDisplayed()
    }

    @Test fun aFailedModelCanBeRetried() {
        ui = SearchUi(model = ModelUi.Failed("disk full")); show()
        rule.onNodeWithText("disk full", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, actions.retries)
    }

    @Test fun typingAndPressingSearchSubmitsTheQuestion() {
        ui = ready; show()
        rule.onNodeWithTag("search-field").performTextInput("sunset over the sea")
        rule.onNodeWithTag("search-field").performImeAction()
        assertEquals(listOf("sunset over the sea"), actions.submitted)
    }

    @Test fun aRecentSearchAndAnExampleSubmitWhenTapped() {
        ui = ready; show()
        rule.onNodeWithText("old question").performClick()
        rule.onNodeWithText("birthday cake").performClick()
        assertEquals(listOf("old question", "birthday cake"), actions.submitted)
    }

    @Test fun nothingIsSubmittedWhileSearchIsNotReady() {
        ui = SearchUi(model = ModelUi.Available(1L)); show()
        rule.onNodeWithTag("search-field").performTextInput("dog")
        rule.onNodeWithTag("search-field").performImeAction()
        assertTrue(actions.submitted.isEmpty())
    }

    @Test fun categoryNamesFollowTheLanguageAndTapPicksOne() {
        ui = ready; show(greek = true)
        rule.onNodeWithText("Παραλία", substring = true).performClick()
        assertEquals(listOf("Παραλία"), actions.picked)
    }

    @Test fun resultsShowTheCountAndTappingATileOpensItThroughTheResults() {
        val ids = longArrayOf(105, 103, 101, 9999)                                               // 9999 is not in the library: left out
        ui = ready.copy(results = SearchResultsUi("beach sunset", ids, false, 12)); show()
        rule.onNodeWithText("beach sunset").assertIsDisplayed()
        rule.onNodeWithText("3 photos").assertIsDisplayed()
        rule.onNodeWithTag("search-tile-0").performClick()                                        // the best match
        val (index, order) = opened!!
        assertEquals(105L, lib.ids[index])                                                        // the tile is the photo with id 105
        assertEquals(ids.toList(), order)                                                         // and the viewer is opened through exactly these results, in this order
        rule.onNodeWithTag("search-tile-3").assertDoesNotExist()                                  // only the three that are in the library are shown
    }

    @Test fun noResultsSaysSo() {
        ui = ready.copy(results = SearchResultsUi("zzz", LongArray(0), false, 3)); show()
        rule.onNodeWithText("Nothing found.").assertIsDisplayed()
    }

    @Test fun theBackArrowFirstClearsTheResultsThenLeaves() {
        ui = ready.copy(results = SearchResultsUi("beach", longArrayOf(101), false, 1)); show()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, actions.cleared); assertEquals(0, backs)
        ui = ready; rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
