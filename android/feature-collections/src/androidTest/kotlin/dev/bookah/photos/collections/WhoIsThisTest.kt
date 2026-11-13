package dev.bookah.photos.collections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.FaceBox
import dev.bookah.photos.data.PeopleIndex
import dev.bookah.photos.data.PeopleReader
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.Previews
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** "Who is this?": faces of nobody, selected and given to a person or made a new person. Made-up faces only. */
class WhoIsThisTest {
    @get:Rule val rule = createComposeRule()

    private val previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }
    private fun face(asset: Long, q: Float) = FaceBox(asset * 10, asset, 0.4, 0.3, 0.2, 0.2, q)
    private val ann = PeopleReader.make(1, "Ann", -1, false, listOf(face(1, 0.9f), face(2, 0.8f)))
    private val nobody = listOf(face(50, 0.95f), face(51, 0.9f), face(52, 0.8f))
    private val people = PeopleIndex(listOf(ann), nobody)
    private val loader get() = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()

    private var assigned: Pair<List<Long>, Long>? = null
    private var made: Pair<String, List<Long>>? = null
    private var notPeople: List<Long>? = null
    private val actions = WhoIsActions({ f, p -> assigned = f.map { it.id } to p.id }, { n, f -> made = n to f.map { it.id } }, { notPeople = it.map { f -> f.id } })

    private fun show(p: PeopleIndex = people) = rule.setContent {
        BookahPhotosTheme { WhoIsThisPage(p, previews, loader, actions, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f)) }
    }

    @Test fun selectingFacesAndChoosingAPersonGivesThemToThatPerson() {
        show()
        rule.onNodeWithText("3 faces nobody has named", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("who-face-500").performClick(); rule.onNodeWithTag("who-face-510").performClick()
        rule.onNodeWithTag("who-assign").performClick()
        rule.onNodeWithTag("who-pick-1").performClick()
        assertEquals(setOf(500L, 510L), assigned!!.first.toSet()); assertEquals(1L, assigned!!.second)
    }

    @Test fun aNewPersonIsMadeFromTheSelectedFacesWithTheTypedName() {
        show()
        rule.onNodeWithTag("who-face-520").performClick()
        rule.onNodeWithTag("who-assign").performClick(); rule.onNodeWithTag("who-new").performClick()
        rule.onNodeWithTag("name-field").performTextInput("Zoe"); rule.onNodeWithTag("name-save").performClick()
        assertEquals("Zoe" to listOf(520L), made)
    }

    @Test fun aNameThatExistsAsksSamePersonAndYesGivesTheFacesToThem() {
        show()
        rule.onNodeWithTag("who-face-500").performClick()
        rule.onNodeWithTag("who-assign").performClick(); rule.onNodeWithTag("who-new").performClick()
        rule.onNodeWithTag("name-field").performTextInput("ann"); rule.onNodeWithTag("name-save").performClick()
        rule.onNodeWithTag("merge-yes").performClick()
        assertEquals(listOf(500L), assigned!!.first); assertEquals(1L, assigned!!.second)
    }

    @Test fun notAPersonAndClear() {
        show()
        rule.onNodeWithTag("who-face-500").performClick(); rule.onNodeWithTag("who-face-510").performClick()
        rule.onNodeWithTag("who-clear").performClick()
        rule.onNodeWithTag("who-assign").assertDoesNotExist()                                  // nothing selected: no buttons
        rule.onNodeWithTag("who-face-500").performClick()
        rule.onNodeWithTag("who-not-person").performClick()
        assertEquals(listOf(500L), notPeople)
    }

    @Test fun whenNobodyIsLeftItSaysSo() {
        show(PeopleIndex(listOf(ann)))
        rule.onNodeWithText("Every face is placed. Nothing to name.").assertIsDisplayed()
    }

    @Test fun thePeoplePageLinksToItOnlyWhenThereAreFaces() {
        var opened = 0
        rule.setContent { BookahPhotosTheme { PeoplePage(people, previews, loader, onPerson = {}, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f), onWhoIsThis = { opened++ }) } }
        rule.onNodeWithTag("who-link").performClick(); assertEquals(1, opened)
    }
}
