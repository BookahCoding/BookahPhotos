package dev.bookah.photos.collections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.Memory
import dev.bookah.photos.data.PeopleIndex
import dev.bookah.photos.data.SectionPrefs
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.Previews
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Memories in Collections and the playback, with made-up memories (no network: the pictures stay placeholders). */
class MemoriesScreenTest {
    @get:Rule val rule = createComposeRule()

    private val previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }
    private val lib = LibraryIndex.build((0 until 30).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) })
    private val trip = Memory("trip:a", Memory.TYPE_TRIP, "Island trip, Aug 2024", "Ταξίδι στο νησί, Αύγ 2024", "2024-08-01", "2024-08-03", 101, longArrayOf(100, 101, 102))
    private val month = Memory("month:2024-03", Memory.TYPE_MONTH, "March 2024", "Μάρτιος 2024", "2024-03-01", "2024-03-31", 110, longArrayOf(110, 111))
    private val loader get() = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()
    private var opened: Memory? = null
    private val labels = CollectionLabels("Favourites", "Recently added", "Videos", "Screenshots", "Panoramas", "RAW")

    private fun showRow(list: List<Memory>, greek: Boolean = false) = rule.setContent {
        BookahPhotosTheme {
            CollectionsScreen(
                lib, CollectionsModel.build(lib, null, labels, Locale.ENGLISH), SectionPrefs(), {}, previews, loader, onCard = {}, onSeeAll = {}, onSettings = {}, onReorder = {},
                bottomPadding = androidx.compose.ui.unit.Dp(100f), memories = list, greek = greek, onMemory = { opened = it },
            )
        }
    }

    @Test fun theRowShowsMemoriesInTheLanguageAndOpensOne() {
        showRow(listOf(trip, month))
        rule.onNodeWithText("Memories").assertIsDisplayed(); rule.onNodeWithText("Island trip, Aug 2024").assertIsDisplayed()
        rule.onNodeWithTag("row-memory-trip:a", useUnmergedTree = true).performClick()
        assertEquals("trip:a", opened?.key)
    }

    @Test fun greekTitlesWhenThePhoneIsGreek() {
        showRow(listOf(trip), greek = true)
        rule.onNodeWithText("Ταξίδι στο νησί, Αύγ 2024").assertIsDisplayed()
    }

    @Test fun noMemoriesMeansNoSection() {
        showRow(emptyList())
        rule.onNodeWithText("Memories").assertDoesNotExist()
    }

    private var closed = 0; private var seeAll = 0; private var unwanted = 0
    private fun play() = rule.setContent {
        BookahPhotosTheme { MemoryPlayer(lib, trip, false, previews, loader, onClose = { closed++ }, onSeeAll = { seeAll++ }, onUnwanted = { unwanted++ }) }
    }

    @Test fun thePlaybackAdvancesByItselfAndByTapAndStopsAtTheEnd() {
        rule.mainClock.autoAdvance = false
        play()
        rule.onNodeWithTag("memory-player").assert(hasStateDescription("1/3"))
        rule.onNodeWithTag("memory-next").performClick(); rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithTag("memory-player").assert(hasStateDescription("2/3"))
        rule.onNodeWithTag("memory-back").performClick(); rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithTag("memory-player").assert(hasStateDescription("1/3"))
        rule.mainClock.advanceTimeBy(MEMORY_SECONDS * 1000L + 1500)                                      // by itself
        rule.onNodeWithTag("memory-player").assert(hasStateDescription("2/3"))
        rule.mainClock.advanceTimeBy((MEMORY_SECONDS * 1000L + 1500) * 3)
        rule.onNodeWithTag("memory-player").assert(hasStateDescription("3/3"))                           // the last picture stays
    }

    @Test fun theMenuOpensAllPhotosAndAsksBeforeHidingTheMemory() {
        play()
        rule.onNodeWithTag("memory-menu").performClick(); rule.onNodeWithTag("memory-see-all").performClick()
        assertEquals(1, seeAll)
        rule.onNodeWithTag("memory-menu").performClick(); rule.onNodeWithTag("memory-unwanted").performClick()
        rule.onNodeWithText("Don’t show this memory?").assertIsDisplayed()
        assertEquals(0, unwanted)
        rule.onNodeWithTag("memory-unwanted-yes").performClick()
        assertEquals(1, unwanted)
        rule.onNodeWithTag("memory-close").performClick(); assertEquals(1, closed)
    }
}

/** The Trips section: the trip memories again as their own row. */
class TripsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theTripsRowShowsTripsAndOpensOne() {
        val lib = LibraryIndex.build((0 until 10).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) })
        val trip = Memory("trip:a", Memory.TYPE_TRIP, "Island trip, Aug 2024", "Ταξίδι", "2024-08-01", "2024-08-03", 101, longArrayOf(100, 101))
        var opened: Memory? = null
        val labels = CollectionLabels("Favourites", "Recently added", "Videos", "Screenshots", "Panoramas", "RAW")
        rule.setContent {
            BookahPhotosTheme {
                CollectionsScreen(
                    lib, CollectionsModel.build(lib, null, labels, Locale.ENGLISH), SectionPrefs(), {}, object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" },
                    ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build(), onCard = {}, onSeeAll = {}, onSettings = {}, onReorder = {},
                    bottomPadding = androidx.compose.ui.unit.Dp(300f), trips = listOf(trip), onMemory = { opened = it },
                )
            }
        }
        rule.onNodeWithText("Trips").assertIsDisplayed()
        rule.onNodeWithTag("row-trip-trip:a", useUnmergedTree = true).performClick()
        assertEquals("trip:a", opened?.key)
    }
}
