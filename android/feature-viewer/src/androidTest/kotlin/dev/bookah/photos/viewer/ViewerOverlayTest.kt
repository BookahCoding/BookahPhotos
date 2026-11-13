package dev.bookah.photos.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.AssetDetails
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.Exposure
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.LibraryController
import dev.bookah.photos.library.Previews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The real viewer on a device or emulator with generated data only: no network, so pictures stay as placeholders. */
@OptIn(ExperimentalTestApi::class)
class ViewerOverlayTest {
    @get:Rule val rule = createComposeRule()

    private val hash = "61f7093d9a8087877078788888788888707007f878".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun lib(n: Int): LibraryIndex = LibraryIndex.build(
        (0 until n).map { AssetRow(100L + it, 20240101 + it % 28, it.toLong(), if (it == 3) 1 else 0, hash, it, false, if (it == 3) 7_000 else 0) },
    )

    // 2024-08-24 08:46:33 UTC at +03:00
    private fun details(id: Long) = AssetDetails(
        id, "Photos/Test/IMG_${id}.jpg", "image/jpeg", 3_400_000, 4032, 3024, null, 1_724_489_193L, 180, false,
        "Test Camera 14", "Test lens 26 mm", Exposure(0.0303, 1.5, 500.0, 26.0), null, null, "Test place", "Δοκιμή", 0,
    )

    private var closedAt: Int? = null
    private var shared: Long? = null
    private var deleteAsked: Long? = null

    private fun show(count: Int = 30, start: Int = 10) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val env = ViewerEnv(
            previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" },
            loader = ImageLoader.Builder(ctx).build(),
            okHttp = okhttp3.OkHttpClient(),
            originalUrl = { "http://127.0.0.1:9/o/$it" },
            details = { details(it) },
            onDelete = { deleteAsked = it },
        )
        val library = lib(count)
        rule.setContent {
            BookahPhotosTheme {
                ViewerOverlay(library, start, env, LibraryController(), onShare = { shared = it }, onClose = { closedAt = it })
            }
        }
    }

    @Test fun showsTheDateAndTimeInThePhotosOwnClock() {
        show()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("11:46") > 0 }
        rule.onNodeWithText("24 August 2024").assertIsDisplayed()
        rule.onNodeWithText("11:46").assertIsDisplayed()
    }

    @Test fun infoOpensTheDetailsSheet() {
        show()
        rule.onNodeWithContentDescription("Info").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Test Camera 14") > 0 }
        rule.onNodeWithText("No caption").assertIsDisplayed()
        rule.onNodeWithText("Test Camera 14").assertIsDisplayed()
        rule.onNodeWithText("JPEG").assertIsDisplayed()
        rule.onNodeWithText("ISO 500").assertIsDisplayed()
        rule.onNodeWithText("1/33 s").assertIsDisplayed()
        rule.onNodeWithText("Test place").assertIsDisplayed()
    }

    @Test fun shareSendsTheCurrentFile() {
        show(start = 12)
        rule.onNodeWithContentDescription("Share").performClick()
        assertEquals(lib(30).ids[12], shared)
    }

    @Test fun deleteAsksForTheCurrentPhoto() {
        show(start = 12)
        rule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(lib(30).ids[12], deleteAsked)
    }

    @Test fun backButtonClosesAtTheCurrentPhoto() {
        show()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitUntil(5_000) { closedAt != null }
        assertEquals(10, closedAt)
    }

    @Test fun swipingMovesToTheNextPhoto() {
        show()
        rule.onRoot().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitUntil(5_000) { closedAt != null }
        assertEquals(11, closedAt)
    }

    @Test fun swipingDownClosesAtTheCurrentPhoto() {
        show()
        rule.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.8f, durationMillis = 250) }
        rule.waitUntil(5_000) { closedAt != null }
        assertEquals(10, closedAt)
    }

    @Test fun aShortDownDragSpringsBackAndStaysOpen() {
        show()
        rule.onRoot().performTouchInput { swipeDown(startY = height * 0.4f, endY = height * 0.45f, durationMillis = 400) }
        rule.waitForIdle(); Thread.sleep(700)
        org.junit.Assert.assertNull(closedAt)
        rule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test fun pinchZoomDoesNotCrash() {
        show()
        rule.onRoot().performTouchInput {
            val c = center
            pinch(Offset(c.x - 60, c.y), Offset(c.x - 400, c.y), Offset(c.x + 60, c.y), Offset(c.x + 400, c.y), 400)
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { doubleClick() }
        rule.waitForIdle()
        assertTrue(true)
    }

    @Test fun aVideoShowsItsPosterWithoutCrashing() {
        show(start = 3)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Share").assertIsDisplayed()
    }

    /** Saves pictures of the viewer and its details sheet (generated data) to compare with docs/design-spec.md. */
    @Test fun screenshotsOfTheViewer() {
        show()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("11:46") > 0 }
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        fun shot(name: String) {
            rule.waitForIdle(); Thread.sleep(600)
            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        shot("viewer")
        rule.onNodeWithContentDescription("Info").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Test Camera 14") > 0 }
        shot("viewer-details")
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

/** The lock (hide) button and the Hidden photos viewer: unhide instead of hide, and no share or delete there. */
class ViewerHideTest {
    @get:Rule val rule = createComposeRule()

    private fun lib() = LibraryIndex.build((0 until 5).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) })
    private fun env(hide: ((Long) -> Unit)?, unhide: Boolean = false, share: Boolean = true, delete: Boolean = true) = ViewerEnv(
        previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" },
        loader = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build(),
        okHttp = okhttp3.OkHttpClient(), originalUrl = { "http://127.0.0.1:9/o/$it" }, details = { null },
        onHide = hide, hideIsUnhide = unhide, allowShare = share, allowDelete = delete,
    )

    @Test fun theLockButtonHidesTheCurrentPhoto() {
        var hidden: Long? = null
        rule.setContent { BookahPhotosTheme { ViewerOverlay(lib(), 2, env({ hidden = it }), LibraryController(), onShare = {}, onClose = {}) } }
        rule.onNodeWithTag("viewer-hide").performClick()
        assertEquals(102L, hidden)
    }

    @Test fun theHiddenViewerOffersUnhideAndNoShareOrDelete() {
        var shown: Long? = null
        rule.setContent { BookahPhotosTheme { ViewerOverlay(lib(), 1, env({ shown = it }, unhide = true, share = false, delete = false), LibraryController(), onShare = {}, onClose = {}) } }
        rule.onNodeWithText("Unhide").assertIsDisplayed()
        rule.onNodeWithContentDescription("Share").assertDoesNotExist()
        rule.onNodeWithContentDescription("Delete").assertDoesNotExist()
        rule.onNodeWithTag("viewer-unhide").performClick()
        assertEquals(101L, shown)
    }

    @Test fun withoutAHideActionThereIsNoLockButton() {
        rule.setContent { BookahPhotosTheme { ViewerOverlay(lib(), 0, env(null), LibraryController(), onShare = {}, onClose = {}) } }
        rule.onNodeWithTag("viewer-hide").assertDoesNotExist()
    }
}

/** The heart in the viewer sets and clears the favourite flag (for photos in Nextcloud only). */
class ViewerFavouriteTest {
    @get:Rule val rule = createComposeRule()

    private fun lib() = LibraryIndex.build(listOf(AssetRow(-5, 20240101, 1, 0, null, 1, false), AssetRow(100, 20240102, 2, 0, null, 2, false), AssetRow(101, 20240103, 3, 0, null, 3, false)))
    private fun env(fav: ((Long, Boolean) -> Unit)?) = ViewerEnv(
        previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" },
        loader = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build(),
        okHttp = okhttp3.OkHttpClient(), originalUrl = { "http://127.0.0.1:9/o/$it" }, details = { null }, onFavourite = fav,
    )

    @Test fun theHeartSetsAndClears() {
        val calls = ArrayList<Pair<Long, Boolean>>()
        rule.setContent { BookahPhotosTheme { ViewerOverlay(lib(), 1, env { id, on -> calls.add(id to on) }, LibraryController(), onShare = {}, onClose = {}, favourites = setOf(101L)) } }
        rule.onNodeWithTag("viewer-favourite").performClick()
        assertEquals(listOf(100L to true), calls)                                           // 100 is not a favourite yet: the heart sets it
    }

    @Test fun anExistingFavouriteIsClearedByTheHeart() {
        val calls = ArrayList<Pair<Long, Boolean>>()
        rule.setContent { BookahPhotosTheme { ViewerOverlay(lib(), 2, env { id, on -> calls.add(id to on) }, LibraryController(), onShare = {}, onClose = {}, favourites = setOf(101L)) } }
        rule.onNodeWithTag("viewer-favourite").performClick()
        assertEquals(listOf(101L to false), calls)
    }

    @Test fun aPhotoOnlyOnThePhoneHasNoWorkingHeart() {
        val calls = ArrayList<Pair<Long, Boolean>>()
        rule.setContent { BookahPhotosTheme { ViewerOverlay(lib(), 0, env { id, on -> calls.add(id to on) }, LibraryController(), onShare = {}, onClose = {}) } }
        rule.onNodeWithTag("viewer-favourite").performClick()
        assertEquals(emptyList<Pair<Long, Boolean>>(), calls)
    }
}
