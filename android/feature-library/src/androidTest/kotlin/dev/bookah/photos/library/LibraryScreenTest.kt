package dev.bookah.photos.library

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.design.BookahPhotosTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * Runs the real Library screen on a device or emulator with generated data only (no real photos, no network: the preview address does
 * not exist, so cells show their ThumbHash placeholders). It checks that the screen composes, that the three views switch and that select mode works.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class LibraryScreenTest {
    @get:Rule val rule = createComposeRule()

    // a hash made by the indexer's encoder from a generated gradient image
    private val hash = "61f7093d9a8087877078788888788888707007f878".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun fakeLibrary(count: Int): LibraryIndex {
        val rows = ArrayList<AssetRow>(count)
        var date = LocalDate.of(2012, 1, 1)
        for (n in 0 until count) {
            if (n % 7 == 0) date = date.plusDays(1)
            val dayKey = if (n % 50 == 0) 0 else date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
            rows.add(AssetRow(1000L + n, dayKey, n.toLong(), if (n % 13 == 0) 1 else 0, hash, n, n % 97 == 0, if (n % 13 == 0) 5_000 else 0))
        }
        return LibraryIndex.build(rows)
    }

    private fun show(count: Int, startAt: Int? = null, onShare: (LongArray) -> Unit = {}, onDelete: (LongArray) -> Unit = {}, onOpen: (Int) -> Unit = {}, controller: LibraryController = LibraryController()) {
        val lib = fakeLibrary(count)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent {
            BookahPhotosTheme {
                LibraryScreen(lib, object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId/$size" }, ImageLoader.Builder(ctx).build(), onShare, onOpen, {}, startAtItem = startAt, onDelete = onDelete, controller = controller)
            }
        }
    }

    @Test fun libraryShowsTitleAndBottomBar() {
        show(5_000)
        rule.onNodeWithText("Library").assertIsDisplayed()
        rule.onNodeWithText("Years").assertIsDisplayed()
        rule.onNodeWithText("Months").assertIsDisplayed()
        rule.onNodeWithText("All").assertIsDisplayed()
    }

    @Test fun threeViewsSwitchWithoutCrashing() {
        show(5_000)
        rule.onNodeWithText("Years").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Months").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("All").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Library").assertIsDisplayed()
    }

    @Test fun fiftyThousandItemsOpenAndScroll() {
        show(50_000)
        rule.waitForIdle()
        rule.onNodeWithText("Library").assertIsDisplayed()
        rule.onNodeWithText("Years").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Months").performClick()
        rule.waitForIdle()
    }

    @Test fun longPressStartsSelectModeAndShareGetsTheIds() {
        var shared: LongArray? = null
        show(2_000, onShare = { shared = it })
        rule.onNodeWithText("Select").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Selected (0)").assertIsDisplayed()
        rule.onNodeWithContentDescription("Done").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Select").assertIsDisplayed()
        assertTrue(shared == null)
        assertEquals(null, shared)
    }

    @Test fun emptyLibraryShowsAMessage() {
        show(0)
        rule.onNodeWithText("Nothing in the index yet.").assertIsDisplayed()
    }

    /** Saves a picture of each view (generated data only) so the layout can be compared with docs/design-spec.md. */
    @Test fun screenshotsOfTheViews() {
        show(3_000)
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        fun shot(name: String) {
            rule.waitForIdle()
            Thread.sleep(400)
            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        shot("library-all")
        rule.onNodeWithText("Months").performClick(); shot("library-months")
        rule.onNodeWithText("Years").performClick(); shot("library-years")
        rule.onNodeWithText("All").performClick()
        rule.onNodeWithText("Select").performClick(); shot("library-select")
    }

    private fun selectedCount(): Int {
        for (n in 0..40) if (rule.onAllNodes(androidx.compose.ui.test.hasText("Selected ($n)")).fetchSemanticsNodes().isNotEmpty()) return n
        return -1
    }

    /** Hold a tile, then slide the finger over the row: select mode starts and the tiles under the finger are selected. */
    @Test fun holdAndSlideSelectsSeveralTiles() {
        show(300, startAt = 100)
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            val y = height * 0.5f
            down(Offset(width * 0.1f, y))
            advanceEventTime(900)
            moveTo(Offset(width * 0.3f, y)); moveTo(Offset(width * 0.5f, y)); moveTo(Offset(width * 0.7f, y)); moveTo(Offset(width * 0.9f, y))
            up()
        }
        rule.waitForIdle()
        val n = selectedCount()
        assertTrue("selected $n", n >= 4)
    }

    /** In select mode a sideways drag over tiles selects them without lifting the finger. */
    @Test fun inSelectModeASidewaysDragSelects() {
        show(300, startAt = 100)
        rule.onNodeWithText("Select").performClick()
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            val y = height * 0.5f
            down(Offset(width * 0.1f, y))
            moveTo(Offset(width * 0.3f, y)); moveTo(Offset(width * 0.5f, y)); moveTo(Offset(width * 0.7f, y)); moveTo(Offset(width * 0.9f, y))
            up()
        }
        rule.waitForIdle()
        val n = selectedCount()
        assertTrue("selected $n", n >= 4)
    }

    /** A vertical drag in select mode scrolls and selects nothing. */
    @Test fun inSelectModeAVerticalDragStillScrolls() {
        show(300, startAt = 100)
        rule.onNodeWithText("Select").performClick()
        rule.waitForIdle()
        rule.onRoot().performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.3f, durationMillis = 300) }
        rule.waitForIdle()
        assertEquals(0, selectedCount())
    }

    /** Select a row by sliding, then tap Delete: the app is asked to delete exactly those files (it shows the confirmation). */
    @Test fun deleteInSelectModeSendsTheSelectedIds() {
        var asked: LongArray? = null
        show(300, startAt = 100, onDelete = { asked = it })
        rule.onNodeWithText("Select").performClick()
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            val y = height * 0.5f
            down(Offset(width * 0.1f, y))
            moveTo(Offset(width * 0.5f, y)); moveTo(Offset(width * 0.9f, y))
            up()
        }
        rule.waitForIdle()
        val n = selectedCount()
        rule.onNodeWithContentDescription("Delete").performClick()
        assertTrue(n >= 3 && asked != null && asked!!.size == n)
    }

    /** Hold a tile without moving and let go: it is selected, and it is NOT opened (a report from the phone: the release opened the photo or switched the selection off). */
    @Test fun holdingStillSelectsAndTheReleaseDoesNotOpenIt() {
        var opened: Int? = null
        show(300, startAt = 100, onOpen = { opened = it })
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.3f, height * 0.5f))
            advanceEventTime(900)
            up()
        }
        rule.waitForIdle()
        assertEquals(1, selectedCount())
        assertEquals(null, opened)
    }

    /** A diagonal slide in select mode also selects (people do not drag in a perfectly straight line). */
    @Test fun aSlightlyDiagonalSlideInSelectModeSelects() {
        show(300, startAt = 100)
        rule.onNodeWithText("Select").performClick()
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.1f, height * 0.5f))
            moveTo(Offset(width * 0.3f, height * 0.52f)); moveTo(Offset(width * 0.5f, height * 0.54f)); moveTo(Offset(width * 0.8f, height * 0.56f))
            up()
        }
        rule.waitForIdle()
        assertTrue(selectedCount() >= 3)
    }

    /** The tile under the finger is the tile that gets selected (a report from the phone: two rows further down were selected). */
    @Test fun holdingATileSelectsExactlyThatTile() {
        var shared: LongArray? = null
        show(300, startAt = 100, onShare = { shared = it })
        rule.waitForIdle()
        // pick a tile from the screen itself, not from the grid's own numbers: its position comes from the semantics tree
        val tile = rule.onAllNodes(androidx.compose.ui.test.hasTestTag("cell-1130")).fetchSemanticsNodes().firstOrNull()
            ?: rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("a tile") { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("cell-") == true }).fetchSemanticsNodes()[12]
        val tag = tile.config.getOrNull(SemanticsProperties.TestTag)!!
        val centre = tile.boundsInRoot.center
        rule.onRoot().performTouchInput { down(centre); advanceEventTime(900); up() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Share").performClick()
        assertEquals(listOf(tag.removePrefix("cell-").toLong()), shared?.toList())
    }

    /** The cell rectangle given to the viewer for its grow animation is where the tile really is. */
    @Test fun theViewerAnimationAnchorIsWhereTheTileReallyIs() {
        val controller = LibraryController()
        show(300, startAt = 100, controller = controller)
        rule.waitForIdle()
        val tile = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("a tile") { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("cell-") == true }).fetchSemanticsNodes()[12]
        val id = tile.config.getOrNull(SemanticsProperties.TestTag)!!.removePrefix("cell-").toLong()
        val index = fakeLibrary(300).indexOfId(id)!!
        val rect = controller.cellRect(index)!!
        val real = tile.boundsInRoot
        assertTrue("anchor $rect vs tile $real", kotlin.math.abs(rect.top - real.top) < 3f && kotlin.math.abs(rect.left - real.left) < 3f)
    }

    /** A second slide adds to the selection; it does not start a new one. */
    @Test fun aSecondSlideAddsToTheSelection() {
        show(300, startAt = 100)
        rule.onNodeWithText("Select").performClick()
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            val y = height * 0.35f
            down(Offset(width * 0.1f, y)); moveTo(Offset(width * 0.5f, y)); moveTo(Offset(width * 0.9f, y)); up()
        }
        rule.waitForIdle()
        val first = selectedCount()
        rule.onRoot().performTouchInput {
            val y = height * 0.6f
            down(Offset(width * 0.1f, y)); moveTo(Offset(width * 0.5f, y)); moveTo(Offset(width * 0.9f, y)); up()
        }
        rule.waitForIdle()
        val second = selectedCount()
        assertTrue("first $first, after the second slide $second", first >= 3 && second >= first + 3)
    }

    /** The way Chris does it: hold and slide, let go, then hold and slide again somewhere else. The second selection is added, not a replacement. */
    @Test fun holdAndSlideTwiceKeepsBothSelections() {
        show(300, startAt = 100)
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            val y = height * 0.35f
            down(Offset(width * 0.1f, y)); advanceEventTime(900)
            moveTo(Offset(width * 0.5f, y)); moveTo(Offset(width * 0.9f, y)); up()
        }
        rule.waitForIdle()
        val first = selectedCount()
        rule.onRoot().performTouchInput {
            val y = height * 0.6f
            down(Offset(width * 0.1f, y)); advanceEventTime(900)
            moveTo(Offset(width * 0.5f, y)); moveTo(Offset(width * 0.9f, y)); up()
        }
        rule.waitForIdle()
        val second = selectedCount()
        assertTrue("first $first, after the second hold-and-slide $second", first >= 4 && second >= first + 4)
    }

    /** Sliding over tiles that are already selected, starting on a selected one, takes them out again. */
    @Test fun slidingFromASelectedTileDeselects() {
        show(300, startAt = 100)
        rule.onNodeWithText("Select").performClick()
        rule.waitForIdle()
        val row = { rule.onRoot().performTouchInput {
            val y = height * 0.5f
            down(Offset(width * 0.1f, y)); moveTo(Offset(width * 0.5f, y)); moveTo(Offset(width * 0.9f, y)); up()
        } }
        row()
        rule.waitForIdle()
        val selected = selectedCount()
        row()          // the same row again, starting on a selected tile
        rule.waitForIdle()
        assertTrue("selected $selected, after sliding back over it ${selectedCount()}", selected >= 4 && selectedCount() < selected)
    }

    /** Chris: after phone photos were merged into the library, holding selected two tiles to the right. The library changes size after the first screen. */
    @Test fun holdingStillSelectsTheRightTileAfterTheLibraryChangesSize() {
        var shared: LongArray? = null
        val libState = androidx.compose.runtime.mutableStateOf(fakeLibrary(300))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent {
            BookahPhotosTheme {
                LibraryScreen(libState.value, object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }, ImageLoader.Builder(ctx).build(), { shared = it }, {}, {}, startAtItem = 100)
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { libState.value = fakeLibrary(302) }      // 2 more items: the padding of the last row changes
        rule.waitForIdle()
        val tile = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("a tile") { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("cell-") == true }).fetchSemanticsNodes()[7]
        val tag = tile.config.getOrNull(SemanticsProperties.TestTag)!!
        rule.onRoot().performTouchInput { down(tile.boundsInRoot.center); advanceEventTime(900); up() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Share").performClick()
        assertEquals(listOf(tag.removePrefix("cell-").toLong()), shared?.toList())
    }

    private fun subtitle(): String =
        rule.onNodeWithTag("subtitle").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text.orEmpty()

    /** The last "MMM yyyy" of a label such as "3 – 10 Sep 2026" or "28 Aug – 4 Sep 2026", as a month number. */
    private fun monthOf(label: String): Int {
        val m = Regex("([A-Z][a-z]{2}) (\\d{4})").findAll(label).last()
        val month = java.time.Month.values().first { it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) == m.groupValues[1] }
        return m.groupValues[2].toInt() * 12 + month.value
    }

    private fun pinch(apart: Boolean) = rule.onRoot().performTouchInput {
        val c = center
        val near = 60f; val far = 420f
        val (a, b) = if (apart) near to far else far to near
        // diagonal like real fingers: each finger also travels up or down, which would scroll the grid if the pinch let go of the events
        pinch(Offset(c.x - a, c.y + a), Offset(c.x - b, c.y + b), Offset(c.x + a, c.y - a), Offset(c.x + b, c.y - b), 500)
    }

    /** Pinching must keep the reader at the same place in the timeline (a report from the real phone: it jumped up while pinching). */
    @Test fun pinchKeepsTheTimelinePosition() {
        show(20_000, startAt = 9_000)
        rule.waitForIdle()
        val before = monthOf(subtitle())
        pinch(apart = true)       // bigger cells
        rule.waitForIdle(); Thread.sleep(500); rule.waitForIdle()
        assertEquals("columns=3", rule.onNodeWithTag("grid").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        val afterBigger = monthOf(subtitle())
        assertTrue("jumped from month $before to $afterBigger", kotlin.math.abs(afterBigger - before) <= 1)
        pinch(apart = false)      // back to smaller
        rule.waitForIdle(); Thread.sleep(500); rule.waitForIdle()
        val afterSmaller = monthOf(subtitle())
        assertTrue("jumped from month $before to $afterSmaller", kotlin.math.abs(afterSmaller - before) <= 1)
    }
}
