package dev.bookah.photos.viewer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.LibraryController
import dev.bookah.photos.library.Previews
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Add to album and (inside an album) remove from this album. */
class ViewerAlbumTest {
    @get:Rule val rule = createComposeRule()

    private fun lib() = LibraryIndex.build(listOf(AssetRow(-5, 20240101, 1, 0, null, 1, false), AssetRow(100, 20240102, 2, 0, null, 2, false)))
    private fun env(add: ((Long) -> Unit)?, remove: ((Long) -> Unit)? = null) = ViewerEnv(
        previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" },
        loader = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build(),
        okHttp = okhttp3.OkHttpClient(), originalUrl = { "http://127.0.0.1:9/o/$it" }, details = { null }, onAddToAlbum = add, onRemoveFromAlbum = remove,
    )

    @Test fun theAddButtonOpensTheAlbumChoiceForThisPhoto() {
        var added: Long? = null
        rule.setContent { BookahPhotosTheme { ViewerOverlay(lib(), 1, env({ added = it }), LibraryController(), onShare = {}, onClose = {}) } }
        rule.onNodeWithTag("viewer-add-album").performClick()
        assertEquals(100L, added)
    }

    @Test fun aPhotoOnlyOnThePhoneHasNoAddButton() {
        rule.setContent { BookahPhotosTheme { ViewerOverlay(lib(), 0, env({}), LibraryController(), onShare = {}, onClose = {}) } }
        rule.onNodeWithTag("viewer-add-album").assertDoesNotExist()
    }

    @Test fun insideAnAlbumTheRemoveButtonTakesThePhotoOut() {
        var removed: Long? = null
        rule.setContent { BookahPhotosTheme { ViewerOverlay(lib(), 1, env({}, { removed = it }), LibraryController(), onShare = {}, onClose = {}) } }
        rule.onNodeWithTag("viewer-remove-album").performClick()
        assertEquals(100L, removed)
    }
}
