package dev.bookah.photos.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.bookah.photos.design.BookahPhotosTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AlbumBarTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theAddToAlbumButtonIsInTheSelectBar() {
        var taps = 0
        rule.setContent { BookahPhotosTheme { SelectBottomBar(2, onShare = {}, onDelete = {}, onAddToAlbum = { taps++ }) } }
        rule.onNodeWithTag("select-add-album").performClick()
        assertEquals(1, taps)
    }
}
