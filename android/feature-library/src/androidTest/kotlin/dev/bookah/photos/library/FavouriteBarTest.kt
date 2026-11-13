package dev.bookah.photos.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.bookah.photos.design.BookahPhotosTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The heart in the Select bar: it sets, or (when every selected photo is a favourite) clears. */
class FavouriteBarTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theHeartSetsWhenNotAllAreFavouritesAndClearsWhenTheyAre() {
        var taps = 0
        rule.setContent { BookahPhotosTheme { SelectBottomBar(2, onShare = {}, onDelete = {}, onFavourite = { taps++ }, favouriteClears = false) } }
        rule.onNodeWithContentDescription("Favourite").performClick()
        assertEquals(1, taps)
    }

    @Test fun whenAllSelectedAreFavouritesTheButtonSaysRemove() {
        rule.setContent { BookahPhotosTheme { SelectBottomBar(2, onShare = {}, onDelete = {}, onFavourite = {}, favouriteClears = true) } }
        rule.onNodeWithContentDescription("Remove from favourites").assertExists()
    }

    @Test fun withoutAFavouriteActionThereIsNoHeart() {
        rule.setContent { BookahPhotosTheme { SelectBottomBar(2, onShare = {}, onDelete = {}) } }
        rule.onNodeWithTag("select-favourite").assertDoesNotExist()
    }
}
