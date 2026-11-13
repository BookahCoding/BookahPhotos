package dev.bookah.photos.map

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.Previews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The map screen with made-up photos at made-up places. The picture tiles come from the internet when there is some; the markers do not need it. */
class MapPageTest {
    @get:Rule val rule = createComposeRule()

    private val previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }
    private val lib = LibraryIndex.build((0 until 20).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) })
    // two photos at one place (Athens), one far away (Corfu)
    private val points = listOf(
        MapPoint(1, 101, 37.9838, 23.7275), MapPoint(2, 102, 37.9838, 23.7275), MapPoint(3, 103, 39.62, 19.92),
    )
    private var opened: List<MapPoint>? = null
    private var back = 0
    private val loader get() = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()

    private fun show(list: List<MapPoint> = points) = rule.setContent {
        BookahPhotosTheme { MapPage(list, lib, previews, loader, "test", onOpen = { opened = it }, onBack = { back++ }) }
    }

    private fun waitForMarkers(count: Int) = rule.waitUntil(20_000) {
        rule.onAllNodes(hasContentDescription("map-marker", substring = true)).fetchSemanticsNodes().size >= count
    }

    @Test fun theAttributionIsAlwaysOnTheMap() {
        show()
        rule.onNodeWithTag("map-attribution").assertIsDisplayed()
    }

    @Test fun photosAtOnePlaceAreOneMarkerWithACount() {
        show()
        waitForMarkers(2)
        // at the zoom that fits both places on the screen the two photos at one spot are one marker (count 2) and the other is a single one
        val descriptions = rule.onAllNodes(hasContentDescription("map-marker", substring = true)).fetchSemanticsNodes().map { node -> node.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].first() }
        assertTrue(descriptions.toString(), "map-marker 2" in descriptions && "map-marker 1" in descriptions)
    }

    @Test fun tappingAMarkerOfPhotosAtOneSpotOpensThem() {
        show()
        waitForMarkers(2)
        rule.onNode(hasContentDescription("map-marker 2")).performClick()
        rule.waitUntil(5_000) { opened != null }
        assertEquals(setOf(101L, 102L), opened!!.map { it.assetId }.toSet())
    }

    @Test fun theMapComesBackWhereItWasAndReportsWhereItIs() {
        var reported: MapCamera? = null
        rule.setContent {
            BookahPhotosTheme { MapPage(points, lib, previews, loader, "test", onOpen = {}, onBack = {}, initialCamera = MapCamera(38.0, 23.7, 9.0), onCamera = { reported = it }) }
        }
        rule.waitUntil(20_000) { reported != null }
        rule.onNodeWithTag("map-page").assert(androidx.compose.ui.test.hasStateDescription("zoom 9.0"))      // not fitted to all photos: it looks where it was told
        assertEquals(9.0, reported!!.zoom, 0.05); assertEquals(38.0, reported!!.lat, 0.01); assertEquals(23.7, reported!!.lon, 0.01)
    }

    @Test fun noPhotosWithAPlaceSaysSo() {
        show(emptyList())
        rule.onNodeWithTag("map-page").assertIsDisplayed()
    }

    /** Not a check: waits for the tiles (when the emulator has internet) and saves a picture of the screen to look at. */
    @Test fun savesAScreenshotForLooking() {
        show()
        waitForMarkers(2)
        Thread.sleep(8_000)
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val out = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "map-shot.png")
        out.outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
        assertTrue(out.length() > 1000)
    }
}
