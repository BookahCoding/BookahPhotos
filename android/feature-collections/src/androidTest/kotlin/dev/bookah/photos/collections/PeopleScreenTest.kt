package dev.bookah.photos.collections

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.PeopleIndex
import dev.bookah.photos.data.Person
import dev.bookah.photos.data.SectionId
import dev.bookah.photos.data.SectionPrefs
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.Previews
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** The People row and the People page with made-up people (no names of real people anywhere); no network, so the faces stay empty circles. */
class PeopleScreenTest {
    @get:Rule val rule = createComposeRule()

    private val previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }
    private val lib = LibraryIndex.build((0 until 30).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) })
    private val labels = CollectionLabels("Favourites", "Recently added", "Videos", "Screenshots", "Panoramas", "RAW")
    private val model = CollectionsModel.build(lib, null, labels, Locale.ENGLISH)
    private fun person(id: Long, name: String?, photos: Int) = Person(id, name, id * 10, 100L + id, floatArrayOf(0.4f, 0.3f, 0.2f, 0.2f), LongArray(photos) { 100L + it })
    private val people = PeopleIndex(listOf(person(1, "Anna", 20), person(2, "Ben", 10), person(3, null, 15)))

    private var seeAll: SectionId? = null
    private var tapped: Person? = null
    private val loader get() = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()

    private fun showCollections(index: PeopleIndex) = rule.setContent {
        BookahPhotosTheme {
            CollectionsScreen(
                lib, model, SectionPrefs(), {}, previews, loader, onCard = {}, onSeeAll = { seeAll = it }, onSettings = {}, onReorder = {},
                bottomPadding = androidx.compose.ui.unit.Dp(100f), people = index, onPerson = { tapped = it },
            )
        }
    }

    @Test fun theRowShowsNamedPeopleFirstAndTheUnnamedOneAsNotNamed() {
        showCollections(people)
        rule.onNodeWithText("People").assertIsDisplayed()
        rule.onNodeWithText("Anna").assertIsDisplayed(); rule.onNodeWithText("Ben").assertIsDisplayed(); rule.onNodeWithText("Not named").assertIsDisplayed()
    }

    @Test fun tappingAPersonReportsThem() {
        showCollections(people)
        rule.onNodeWithTag("row-person-2", useUnmergedTree = true).performClick()
        assertEquals(2L, tapped?.id)
    }

    @Test fun theSectionHeaderOpensThePeoplePage() {
        showCollections(people)
        rule.onNodeWithText("People").performClick()
        assertEquals(SectionId.People, seeAll)
    }

    @Test fun noPeopleMeansNoSection() {
        showCollections(PeopleIndex.EMPTY)
        rule.onNodeWithText("Pinned").assertIsDisplayed()
        rule.onNodeWithText("People").assertDoesNotExist()
    }

    @Test fun thePeoplePageListsEveryoneAndOpensOne() {
        rule.setContent { BookahPhotosTheme { PeoplePage(people, previews, loader, onPerson = { tapped = it }, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f)) } }
        rule.onNodeWithText("Anna").assertIsDisplayed(); rule.onNodeWithText("Ben").assertIsDisplayed(); rule.onNodeWithText("Not named").assertIsDisplayed()
        rule.onNodeWithTag("person-3", useUnmergedTree = true).performClick()
        assertEquals(3L, tapped?.id); assertEquals(null, tapped?.name)
    }

    @Test fun theFaceIsCutOutOfThePictureAroundItsBox() {
        // a made-up picture, 800 x 600, blue, with a red square of 80 px whose centre is at (0.3, 0.4) of the picture: that square is the "face"
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val bmp = android.graphics.Bitmap.createBitmap(800, 600, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).apply {
            drawColor(android.graphics.Color.BLUE)
            drawRect(240f - 40f, 240f - 40f, 240f + 40f, 240f + 40f, android.graphics.Paint().apply { color = android.graphics.Color.RED })
        }
        val file = java.io.File(ctx.cacheDir, "avatar-test.png").also { f -> f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        val local = object : Previews { override fun url(fileId: Long, size: Int) = file.toURI().toString() }
        val p = Person(7, "Test", 70, 777, floatArrayOf(0.25f, 0.3333f, 0.1f, 0.1333f), longArrayOf(777))
        rule.setContent { BookahPhotosTheme { PersonAvatar(p, local, loader, Modifier.size(96.dp).testTag("face")) } }
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("face").captureToImage().let { it.width > 0 } }.getOrDefault(false) &&
                rule.onNodeWithTag("face").captureToImage().asAndroidBitmap().let { b -> b.getPixel(b.width / 2, b.height / 2) == android.graphics.Color.RED }
        }
        val shot = rule.onNodeWithTag("face").captureToImage().asAndroidBitmap()
        assertEquals(android.graphics.Color.RED, shot.getPixel(shot.width / 2, shot.height / 2))                       // the face is in the middle
        assertEquals(android.graphics.Color.BLUE, shot.getPixel(shot.width / 2, (shot.height * 0.12f).toInt()))        // and the picture around it is at the rim
    }
}
