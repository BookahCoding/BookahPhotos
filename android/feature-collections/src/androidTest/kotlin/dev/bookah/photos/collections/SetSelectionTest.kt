package dev.bookah.photos.collections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.Previews
import dev.bookah.photos.library.SelectionActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A page of photos (an album, favourites, a place...) has the Library's selection: Select button, press and hold, the bar of buttons. */
class SetSelectionTest {
    @get:Rule val rule = createComposeRule()

    private val previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }
    private val lib = LibraryIndex.build((0 until 12).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) })
    private val loader get() = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()

    private var shared: List<Long>? = null
    private var deleted: List<Long>? = null
    private var hidden: List<Long>? = null
    private var favourited: Pair<List<Long>, Boolean>? = null
    private var album: List<Long>? = null
    private var opened = 0
    private val actions = SelectionActions(
        onShare = { shared = it.toList() }, onDelete = { deleted = it.toList() }, onHide = { hidden = it.toList() },
        onFavourite = { ids, on -> favourited = ids.toList() to on }, onAddToAlbum = { album = it.toList() },
    )

    private fun show(favourites: Set<Long> = emptySet(), selection: SelectionActions? = actions) = rule.setContent {
        BookahPhotosTheme {
            PhotoSetPage(
                "Summer", lib, IntArray(12) { 11 - it }, previews, loader, favourites, onOpen = { opened++ }, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f),
                selection = selection,
            )
        }
    }

    @Test fun theSelectButtonStartsSelectingAndTapsPickPhotos() {
        show()
        rule.onNodeWithTag("set-select").performClick()
        rule.onNodeWithText("Done").assertIsDisplayed()
        rule.onNodeWithTag("set-cell-111").performClick(); rule.onNodeWithTag("set-cell-110").performClick()
        rule.onNodeWithText("Selected (2)").assertIsDisplayed()
        assertEquals(0, opened)                                                              // while selecting a tap selects, it does not open
        rule.onNodeWithTag("set-cell-110").performClick()                                    // tap again: deselect
        rule.onNodeWithText("Selected (1)").assertIsDisplayed()
    }

    @Test fun pressAndHoldStartsSelectingWithThatPhoto() {
        show()
        rule.onNodeWithTag("set-cell-105").performTouchInput { longClick() }
        rule.onNodeWithText("Selected (1)").assertIsDisplayed()
        rule.onNodeWithTag("select-add-album").assertIsDisplayed()
    }

    @Test fun theBarHasTheSameButtonsAsTheLibraryAndPassesTheSelectedPhotos() {
        show()
        rule.onNodeWithTag("set-select").performClick()
        rule.onNodeWithTag("set-cell-111").performClick(); rule.onNodeWithTag("set-cell-108").performClick()
        rule.onNodeWithTag("select-favourite").performClick()
        assertEquals(setOf(111L, 108L), favourited!!.first.toSet()); assertEquals(true, favourited!!.second)           // not favourites yet: sets them
        rule.onNodeWithTag("select-add-album").performClick(); assertEquals(setOf(111L, 108L), album!!.toSet())
        rule.onNodeWithContentDescription("Hide").performClick(); assertEquals(setOf(111L, 108L), hidden!!.toSet())
        rule.onNodeWithContentDescription("Share").performClick(); assertEquals(setOf(111L, 108L), shared!!.toSet())
        rule.onNodeWithContentDescription("Delete").performClick(); assertEquals(setOf(111L, 108L), deleted!!.toSet())
    }

    @Test fun whenEverySelectedPhotoIsAFavouriteTheHeartClears() {
        show(favourites = setOf(111L, 110L))
        rule.onNodeWithTag("set-select").performClick()
        rule.onNodeWithTag("set-cell-111").performClick(); rule.onNodeWithTag("set-cell-110").performClick()
        rule.onNodeWithTag("select-favourite").performClick()
        assertEquals(false, favourited!!.second)
    }

    @Test fun doneEndsSelectingAndATapOpensAgain() {
        show()
        rule.onNodeWithTag("set-select").performClick(); rule.onNodeWithTag("set-cell-111").performClick()
        rule.onNodeWithTag("set-select").performClick()                                      // Done
        rule.onNodeWithText("Selected (1)").assertDoesNotExist()
        rule.onNodeWithTag("set-cell-111").performClick(); assertEquals(1, opened)
    }

    @Test fun withoutSelectionActionsThereIsNoSelectButton() {
        show(selection = null)
        rule.onNodeWithTag("set-select").assertDoesNotExist()
        rule.onNodeWithTag("set-cell-111").performClick(); assertEquals(1, opened)
    }
}


/** Hidden photos: several can be shown again at once; there is no share or delete there. */
class HiddenSelectionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun severalHiddenPhotosCanBeUnhiddenAtOnce() {
        val lib = LibraryIndex.build((0 until 6).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) })
        var unhidden: List<Long>? = null
        rule.setContent {
            BookahPhotosTheme {
                PhotoSetPage(
                    "Hidden", lib, IntArray(6) { 5 - it }, object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" },
                    ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build(), emptySet(), onOpen = {}, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f),
                    selection = SelectionActions(onShare = null, onDelete = null, onUnhide = { unhidden = it.toList() }),
                )
            }
        }
        rule.onNodeWithTag("set-select").performClick()
        rule.onNodeWithTag("set-cell-105").performClick(); rule.onNodeWithTag("set-cell-103").performClick()
        rule.onNodeWithContentDescription("Share").assertDoesNotExist(); rule.onNodeWithContentDescription("Delete").assertDoesNotExist()
        rule.onNodeWithTag("select-unhide").performClick()
        assertEquals(setOf(105L, 103L), unhidden!!.toSet())
    }
}
