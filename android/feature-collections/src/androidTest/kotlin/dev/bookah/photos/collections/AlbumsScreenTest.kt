package dev.bookah.photos.collections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.Album
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.NextcloudLists
import dev.bookah.photos.data.SectionPrefs
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.Previews
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Albums: the New album tile, the name dialog, the picker, the album menu. Made-up albums only. */
class AlbumsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }
    private val lib = LibraryIndex.build((0 until 10).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) })
    private val labels = CollectionLabels("Favourites", "Recently added", "Videos", "Screenshots", "Panoramas", "RAW")
    private val loader get() = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()
    private val albums = listOf(Album("Summer", 2, 101, listOf(100, 101)), Album("Έλενα", 1, 102, listOf(102)))

    private fun showCollections(withAlbums: Boolean, onNew: () -> Unit) = rule.setContent {
        BookahPhotosTheme {
            CollectionsScreen(
                lib, CollectionsModel.build(lib, if (withAlbums) NextcloudLists(emptyList(), albums) else null, labels, Locale.ENGLISH), SectionPrefs(), {}, previews, loader,
                onCard = {}, onSeeAll = {}, onSettings = {}, onReorder = {}, bottomPadding = androidx.compose.ui.unit.Dp(400f), onNewAlbum = onNew,
            )
        }
    }

    @Test fun theNewAlbumTileIsThereWithAndWithoutAlbums() {
        var taps = 0
        showCollections(true) { taps++ }
        rule.onNodeWithTag("new-album-tile").performClick(); assertEquals(1, taps)
    }

    @Test fun withNoAlbumsTheTileIsStillThere() {
        var taps = 0
        showCollections(false) { taps++ }
        rule.onNodeWithTag("new-album-tile").performClick(); assertEquals(1, taps)
    }

    @Test fun theNameDialogRefusesEmptyAndExistingNames() {
        rule.setContent { BookahPhotosTheme { AlbumNameDialog("New album", "", listOf("Summer", "Έλενα"), onDone = {}, onDismiss = {}) } }
        rule.onNodeWithTag("album-name-save").assertIsNotEnabled()                      // nothing typed
        rule.onNodeWithTag("album-name-field").performTextInput("summer")               // exists (case does not matter)
        rule.onNodeWithTag("album-name-save").assertIsNotEnabled(); rule.onNodeWithText("You have an album with this name already.").assertIsDisplayed()
    }

    @Test fun anAccentInsensitiveClashIsRefused() {
        rule.setContent { BookahPhotosTheme { AlbumNameDialog("New album", "", listOf("Έλενα"), onDone = {}, onDismiss = {}) } }
        rule.onNodeWithTag("album-name-field").performTextInput("ελενα")
        rule.onNodeWithTag("album-name-save").assertIsNotEnabled()
    }

    @Test fun aGoodNameIsSavedWithoutTheSpacesAtTheEnds() {
        var saved: String? = null
        rule.setContent { BookahPhotosTheme { AlbumNameDialog("New album", "", listOf("Summer"), onDone = { saved = it }, onDismiss = {}) } }
        rule.onNodeWithTag("album-name-field").performTextInput("  Winter 2026 ")
        rule.onNodeWithTag("album-name-save").assertIsEnabled().performClick()
        assertEquals("Winter 2026", saved)
    }

    @Test fun renamingKeepsTheOwnNameUsableAndASlashIsRefused() {
        rule.setContent { BookahPhotosTheme { AlbumNameDialog("Rename album", "Summer", listOf("Summer", "Έλενα"), onDone = {}, onDismiss = {}) } }
        rule.onNodeWithTag("album-name-save").assertIsEnabled()                          // its own name is not a clash
        rule.onNodeWithTag("album-name-field").performTextInput("/")
        rule.onNodeWithTag("album-name-save").assertIsNotEnabled()
    }

    @Test fun thePickerOffersTheAlbumsAndANewOne() {
        var picked: String? = null; var new = 0
        rule.setContent { BookahPhotosTheme { AlbumPickerDialog(albums, onPick = { picked = it.name }, onNew = { new++ }, onDismiss = {}) } }
        rule.onNodeWithTag("picker-Summer").performClick(); assertEquals("Summer", picked)
        rule.onNodeWithTag("picker-new").performClick(); assertEquals(1, new)
    }

    @Test fun theAlbumMenuRenamesAndDeletes() {
        var rename = 0; var delete = 0
        rule.setContent { BookahPhotosTheme { AlbumMenu(onRename = { rename++ }, onDelete = { delete++ }) } }
        rule.onNodeWithTag("album-menu").performClick(); rule.onNodeWithTag("album-rename").performClick()
        rule.onNodeWithTag("album-menu").performClick(); rule.onNodeWithTag("album-delete").performClick()
        assertEquals(1, rename); assertEquals(1, delete)
    }

    @Test fun deletingAnAlbumSaysThePhotosStay() {
        var yes = 0
        rule.setContent { BookahPhotosTheme { DeleteAlbumDialog("Summer", onConfirm = { yes++ }, onDismiss = {}) } }
        rule.onNodeWithText("Only the album is deleted. The photos stay in your library and in Nextcloud.").assertIsDisplayed()
        rule.onNodeWithTag("album-delete-yes").performClick(); assertEquals(1, yes)
    }
}
