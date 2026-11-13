package dev.bookah.photos.collections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.FaceBox
import dev.bookah.photos.data.PeopleIndex
import dev.bookah.photos.data.PeopleReader
import dev.bookah.photos.data.Person
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.Previews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The faces page with made-up faces: cover, "not this person", "someone else", "not a person", and Undo. */
class FacesScreenTest {
    @get:Rule val rule = createComposeRule()

    private val previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }
    private fun face(asset: Long, q: Float) = FaceBox(asset * 10, asset, 0.4, 0.3, 0.2, 0.2, q)
    private val ann = PeopleReader.make(1, "Ann", -1, false, listOf(face(1, 0.9f), face(2, 0.8f), face(3, 0.3f)))
    private val bob = PeopleReader.make(2, "Bob", -1, false, listOf(face(10, 0.9f)))
    private val index = PeopleIndex(listOf(ann, bob))

    private var cover: Pair<Long, Long>? = null
    private var moved: Pair<Long, Long?>? = null
    private var notPerson: Long? = null
    private val actions = FaceActions({ p, f -> cover = p.id to f.id }, { f, to -> moved = f.id to to?.id }, { notPerson = it.id })
    private val loader get() = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()

    private fun show() = rule.setContent {
        BookahPhotosTheme { FacesPage(ann, index, previews, loader, actions, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f)) }
    }

    @Test fun aFaceCanBeTheCoverAndTheCoverChangeCanBeUndone() {
        show()
        rule.onNodeWithTag("face-20").performClick()
        rule.onNodeWithText("This face").assertIsDisplayed()
        rule.onNodeWithTag("face-cover").performClick()
        assertEquals(1L to 20L, cover)
        rule.onNodeWithTag("faces-undo").performClick()                                        // back to the clearest face, the cover until now
        assertEquals(1L to 10L, cover)
    }

    @Test fun notThisPersonSendsTheFaceToNobodyAndUndoGivesItBack() {
        show()
        rule.onNodeWithTag("face-30").performClick()
        rule.onNodeWithTag("face-not-this").performClick()
        assertEquals(30L to null, moved)
        rule.onNodeWithTag("faces-undo").performClick()
        assertEquals(30L to 1L, moved)
    }

    @Test fun aFaceCanBeGivenToSomeoneElse() {
        show()
        rule.onNodeWithTag("face-20").performClick()
        rule.onNodeWithTag("face-someone").performClick()
        rule.onNodeWithText("Whose face is this?").assertIsDisplayed()
        rule.onNodeWithTag("move-to-2").performClick()
        assertEquals(20L to 2L, moved)
    }

    @Test fun notAPersonIsReportedAndCanBeUndone() {
        show()
        rule.onNodeWithTag("face-10").performClick()
        rule.onNodeWithTag("face-not-person").performClick()
        assertEquals(10L, notPerson); assertNull(moved)
        rule.onNodeWithTag("faces-undo").performClick()
        assertEquals(10L to 1L, moved)
    }

    @Test fun theOrderCanBeSwitchedToFindMistakes() {
        show()
        rule.onNodeWithText("Clearest first. Tap to show the least clear first.").performClick()
        rule.onNodeWithText("Least clear first. Tap to show the clearest first.").assertIsDisplayed()
    }
}
