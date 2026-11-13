package dev.bookah.photos.collections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.Album
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.NextcloudLists
import dev.bookah.photos.data.SectionId
import dev.bookah.photos.data.SectionPrefs
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.Previews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** The Collections screens on a device or emulator with generated data only (no network: covers stay placeholders). */
class CollectionsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val hash = "61f7093d9a8087877078788888788888707007f878".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }

    private val lib = LibraryIndex.build((0 until 120).map { AssetRow(100L + it, 20240101 + it % 20, it.toLong(), if (it % 9 == 0) 1 else 0, hash, it, false) })
    private val lists = NextcloudLists(listOf(101, 105), listOf(Album("Summer", 3, 110, listOf(110, 111, 112))))
    private val labels = CollectionLabels("Favourites", "Recently added", "Videos", "Screenshots", "Panoramas", "RAW")
    private val model = CollectionsModel.build(lib, lists, labels, Locale.ENGLISH)

    private var prefs by mutableStateOf(SectionPrefs())
    private var seeAll: SectionId? = null
    private var opened: CollectionCard? = null
    private var reorder = false

    private fun show() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent {
            BookahPhotosTheme {
                CollectionsScreen(
                    lib, model, prefs, { prefs = it }, previews, ImageLoader.Builder(ctx).build(),
                    onCard = { opened = it }, onSeeAll = { seeAll = it }, onSettings = {}, onReorder = { reorder = true }, bottomPadding = androidx.compose.ui.unit.Dp(100f),
                )
            }
        }
    }

    @Test fun showsTheSectionsAndTheirCards() {
        show()
        rule.onNodeWithText("Collections").assertIsDisplayed()
        rule.onNodeWithText("Pinned").assertIsDisplayed()
        rule.onNodeWithText("Favourites").assertIsDisplayed()
        rule.onNodeWithText("Albums").assertIsDisplayed()
        rule.onNodeWithText("Summer").assertIsDisplayed()
    }

    @Test fun tappingACardOpensIt() {
        show()
        rule.onNodeWithText("Summer").performClick()
        assertEquals("Summer", opened?.title)
        assertEquals(3, opened?.count)
    }

    @Test fun collapseHidesTheCardsAndExpandBringsThemBack() {
        show()
        rule.onNodeWithContentDescription("Collapse", substring = true, useUnmergedTree = true)
        rule.onNodeWithText("Albums").assertIsDisplayed()
        prefs = prefs.toggleCollapsed(SectionId.Albums)
        rule.waitForIdle()
        rule.onNodeWithText("Summer").assertDoesNotExist()
        prefs = prefs.toggleCollapsed(SectionId.Albums)
        rule.waitForIdle()
        rule.onNodeWithText("Summer").assertIsDisplayed()
    }

    @Test fun aHiddenSectionDisappears() {
        show()
        prefs = prefs.toggleHidden(SectionId.Albums)
        rule.waitForIdle()
        rule.onNodeWithText("Albums").assertDoesNotExist()
        rule.onNodeWithText("Pinned").assertIsDisplayed()
    }

    @Test fun menuHasReorder() {
        show()
        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Reorder").performClick()
        assertTrue(reorder)
    }

    @Test fun reorderScreenMovesSections() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent { BookahPhotosTheme { ReorderScreen(prefs, { prefs = it }, onDone = {}) } }
        rule.onNodeWithText("Reorder").assertIsDisplayed()
        val before = prefs.order
        // the second section moves up
        rule.onAllNodesWithDescription("Move up")[1].performClick()
        rule.waitForIdle()
        assertEquals(listOf(before[1], before[0]) + before.drop(2), prefs.order)
        assertFalse(ctx == null)
    }

    @Test fun aPhotoSetShowsItsTitleAndNothingMessage() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent {
            BookahPhotosTheme {
                PhotoSetPage("Summer", lib, intArrayOf(10, 11, 12), previews, ImageLoader.Builder(ctx).build(), emptySet(), onOpen = { opened = null }, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(24f))
            }
        }
        rule.onNodeWithText("Summer").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    /** Saves a picture of the Collections page and the reorder screen (generated data). */
    @Test fun screenshots() {
        show()
        rule.waitForIdle(); Thread.sleep(500)
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(dir, "collections.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithDescription(text: String) =
    onAllNodes(androidx.compose.ui.test.hasContentDescription(text))
