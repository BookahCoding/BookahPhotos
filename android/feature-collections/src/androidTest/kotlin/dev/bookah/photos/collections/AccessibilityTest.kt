package dev.bookah.photos.collections

import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.Album
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.Memory
import dev.bookah.photos.data.NextcloudLists
import dev.bookah.photos.data.PeopleIndex
import dev.bookah.photos.data.PeopleReader
import dev.bookah.photos.data.FaceBox
import dev.bookah.photos.data.Place
import dev.bookah.photos.data.PlacesIndex
import dev.bookah.photos.data.SectionPrefs
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.Previews
import dev.bookah.photos.library.SelectionActions
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Android's accessibility checker (the one TalkBack users and the Play Store pre-launch report rely on) run over the screens of this module: labels for every
 * button, touch targets of at least 48 dp, text contrast. A failure means a real problem for somebody with low vision, a screen reader or a big finger.
 */
class AccessibilityTest {
    @get:Rule val rule = createComposeRule()
    @Before fun on() { rule.enableAccessibilityChecks() }

    private val previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }
    private val lib = LibraryIndex.build((0 until 24).map { AssetRow(100L + it, 20240101 + it, it.toLong(), if (it == 3) 1 else 0, null, it, false, if (it == 3) 7_000 else 0) })
    private val labels = CollectionLabels("Favourites", "Recently added", "Videos", "Screenshots", "Panoramas", "RAW")
    private val loader get() = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()
    private fun person(id: Long, name: String?) = PeopleReader.make(id, name, -1, false, listOf(FaceBox(id * 10, 100L + id, 0.4, 0.3, 0.2, 0.2, 0.9f), FaceBox(id * 10 + 1, 101L + id, 0.4, 0.3, 0.2, 0.2, 0.8f)))
    private val people = PeopleIndex(listOf(person(1, "Ann"), person(2, null)))
    private val trip = Memory("trip:a", Memory.TYPE_TRIP, "Island trip, Aug 2024", "Ταξίδι", "2024-08-01", "2024-08-03", 101, longArrayOf(100, 101))

    @Test fun collections() {
        rule.setContent {
            BookahPhotosTheme {
                CollectionsScreen(
                    lib, CollectionsModel.build(lib, NextcloudLists(listOf(101), listOf(Album("Summer", 2, 101, listOf(100, 101)))), labels, Locale.ENGLISH, PlacesIndex(listOf(Place(1, "Corfu", null, null, 39.6, 19.9, longArrayOf(100, 101))), emptyList())),
                    SectionPrefs(), {}, previews, loader, onCard = {}, onSeeAll = {}, onSettings = {}, onReorder = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f),
                    people = people, memories = listOf(trip), trips = listOf(trip), duplicates = 4,
                )
            }
        }
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test fun peoplePage() {
        rule.setContent { BookahPhotosTheme { PeoplePage(people, previews, loader, onPerson = {}, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f)) } }
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test fun personPage() {
        rule.setContent {
            BookahPhotosTheme {
                PersonPage(people.byId(1)!!, people, lib, IntArray(8) { it }, previews, loader, PersonActions({ _, _ -> }, { _, _ -> }, {}, {}), onOpen = {}, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f))
            }
        }
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test fun photoPage() {
        rule.setContent {
            BookahPhotosTheme {
                PhotoSetPage("Summer", lib, IntArray(12) { it }, previews, loader, setOf(101L), onOpen = {}, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f), selection = SelectionActions({}, {}, {}, { _, _ -> }, {}))
            }
        }
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test fun memories() {
        rule.setContent { BookahPhotosTheme { MemoriesPage(lib, listOf(trip), false, previews, loader, onMemory = {}, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f)) } }
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test fun facesPage() {
        rule.setContent { BookahPhotosTheme { FacesPage(people.byId(1)!!, people, previews, loader, FaceActions({ _, _ -> }, { _, _ -> }, {}), onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f)) } }
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    /** Text at 200% (the largest size in Android's settings): the screens must still show their titles and keep every button reachable. */
    @Test fun collectionsAndPersonPageWithTextAtTwoHundredPercent() {
        val density = androidx.compose.ui.platform.LocalDensity
        rule.setContent {
            val d = androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.density, fontScale = 2f)
            androidx.compose.runtime.CompositionLocalProvider(density provides d) {
                BookahPhotosTheme {
                    PersonPage(people.byId(2)!!, people, lib, IntArray(8) { it }, previews, loader, PersonActions({ _, _ -> }, { _, _ -> }, {}, {}), onOpen = {}, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f))
                }
            }
        }
        rule.onNodeWithTag("name-button").assertIsDisplayed(); rule.onNodeWithTag("hide-button").assertIsDisplayed(); rule.onNodeWithTag("faces-button").assertIsDisplayed()
        rule.onRoot().tryPerformAccessibilityChecks()
    }
}
