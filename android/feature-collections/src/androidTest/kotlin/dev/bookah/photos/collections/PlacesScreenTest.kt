package dev.bookah.photos.collections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.Place
import dev.bookah.photos.data.PlacesIndex
import dev.bookah.photos.data.SectionPrefs
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.Previews
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** The Places section: a Map tile first, then the places. Made-up places only. */
class PlacesScreenTest {
    @get:Rule val rule = createComposeRule()

    private val previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }
    private val lib = LibraryIndex.build((0 until 20).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) })
    private val labels = CollectionLabels("Favourites", "Recently added", "Videos", "Screenshots", "Panoramas", "RAW")
    private val places = PlacesIndex(listOf(Place(1, "Corfu", "Κέρκυρα", null, 39.6, 19.9, longArrayOf(100, 101, 102))), emptyList())
    private var map = 0
    private val loader get() = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()

    private fun show(p: PlacesIndex) = rule.setContent {
        BookahPhotosTheme {
            CollectionsScreen(
                lib, CollectionsModel.build(lib, null, labels, Locale.ENGLISH, p), SectionPrefs(), {}, previews, loader, onCard = {}, onSeeAll = {}, onSettings = {}, onReorder = {},
                bottomPadding = androidx.compose.ui.unit.Dp(100f), onMap = { map++ },
            )
        }
    }

    @Test fun thePlacesRowHasTheMapTileAndThePlaces() {
        show(places)
        rule.onNodeWithText("Places").assertIsDisplayed(); rule.onNodeWithText("Corfu").assertIsDisplayed()
        rule.onNodeWithTag("map-tile").performClick()
        assertEquals(1, map)
    }

    @Test fun noPlacesMeansNoSection() {
        show(PlacesIndex.EMPTY)
        rule.onNodeWithText("Places").assertDoesNotExist()
    }
}

/** The Utilities section: a lock tile with no count and no picture. */
class UtilitiesScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theHiddenTileIsThereAndOpensTheUnlock() {
        val lib = LibraryIndex.build((0 until 3).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) })
        val labels = CollectionLabels("Favourites", "Recently added", "Videos", "Screenshots", "Panoramas", "RAW")
        var opened = 0
        rule.setContent {
            BookahPhotosTheme {
                CollectionsScreen(
                    lib, CollectionsModel.build(lib, null, labels, Locale.ENGLISH), SectionPrefs(), {}, object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" },
                    ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build(), onCard = {}, onSeeAll = {}, onSettings = {}, onReorder = {},
                    bottomPadding = androidx.compose.ui.unit.Dp(300f), onHidden = { opened++ },
                )
            }
        }
        rule.onNodeWithText("Utilities").assertIsDisplayed()
        rule.onNodeWithTag("hidden-tile").performClick()
        assertEquals(1, opened)
    }
}

class DuplicatesTileTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theDuplicatesTileShowsTheCountOnlyWhenThereAreSome() {
        val lib = LibraryIndex.build((0 until 3).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) })
        val labels = CollectionLabels("Favourites", "Recently added", "Videos", "Screenshots", "Panoramas", "RAW")
        var opened = 0
        var count by androidx.compose.runtime.mutableIntStateOf(1961)
        rule.setContent {
            BookahPhotosTheme {
                CollectionsScreen(
                    lib, CollectionsModel.build(lib, null, labels, Locale.ENGLISH), SectionPrefs(), {}, object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" },
                    ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build(), onCard = {}, onSeeAll = {}, onSettings = {}, onReorder = {},
                    bottomPadding = androidx.compose.ui.unit.Dp(300f), duplicates = count, onDuplicates = { opened++ },
                )
            }
        }
        rule.onNodeWithText("1961 photos").assertIsDisplayed()
        rule.onNodeWithTag("duplicates-tile").performClick(); assertEquals(1, opened)
        count = 0
        rule.onNodeWithTag("duplicates-tile").assertDoesNotExist()
    }
}
