package dev.bookah.photos.library

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.bookah.photos.design.BookahPhotosTheme
import org.junit.Rule
import org.junit.Test

/** Checks the sizes of the floating controls against docs/design-spec.md (section 2): rounded to the dp, on a real density. */
class SpecSizesTest {
    @get:Rule val rule = createComposeRule()

    @Test fun libraryBottomBarMatchesTheSpec() {
        rule.setContent { BookahPhotosTheme { LibraryBottomBar(LibraryMode.All, {}, {}) } }
        rule.onNodeWithContentDescription("Library").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)     // round Library button 48 dp
        rule.onNodeWithContentDescription("Search").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)      // round Search button
        rule.onNodeWithTag("library-pill").assertHeightIsEqualTo(48.dp)                                          // Years / Months / All pill
    }

    @Test fun tabBarMatchesTheSpec() {
        rule.setContent { BookahPhotosTheme { TabBar(Tab.Library, {}, libraryLabel = "Library", collectionsLabel = "Collections", searchLabel = "Search") } }
        rule.onNodeWithTag("tabbar-pill").assertHeightIsEqualTo(60.dp)                                           // two-tab pill, 60 dp high
        rule.onNodeWithContentDescription("Search").assertWidthIsEqualTo(60.dp).assertHeightIsEqualTo(60.dp)    // separate round Search button
    }

    @Test fun headerButtonsMatchTheSpec() {
        rule.setContent { BookahPhotosTheme { LibraryHeader("Library", "", false, {}, {}, {}, dev.bookah.photos.data.Source.All, {}, false) } }
        rule.onNodeWithTag("pill-button").assertHeightIsEqualTo(44.dp)                                           // text pill "Select", 44 dp high
    }

    @Test fun roundHeaderButtonIs44Dp() {
        rule.setContent { BookahPhotosTheme { RoundButton(null, "Done", {}) {} } }
        rule.onNodeWithContentDescription("Done").assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
    }
}
