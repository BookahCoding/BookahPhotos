package dev.bookah.photos.collections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.PeopleIndex
import dev.bookah.photos.data.Person
import dev.bookah.photos.design.BookahPhotosTheme
import dev.bookah.photos.library.Previews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Naming, "same person?", hiding and showing again, with made-up people (no real names anywhere). */
class PersonScreenTest {
    @get:Rule val rule = createComposeRule()

    private val previews = object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" }
    private val lib = LibraryIndex.build((0 until 30).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) })
    private fun person(id: Long, name: String?, photos: Int, hidden: Boolean = false) =
        Person(id, name, id * 10, 100L + id, floatArrayOf(0.4f, 0.3f, 0.2f, 0.2f), LongArray(photos) { 100L + it }, hidden)

    private val ann = person(1, "Ann", 20)
    private val unnamed = person(2, null, 8)
    private val index = PeopleIndex(listOf(ann, unnamed, person(3, "Έλενα", 5), person(4, null, 3, hidden = true)))

    private var named: Pair<Long, String>? = null
    private var merged: Pair<Long, Long>? = null
    private var hid: Long? = null
    private var unhid: Long? = null
    private var back = 0
    private val actions = PersonActions({ p, n -> named = p.id to n }, { f, i -> merged = f.id to i.id }, { hid = it.id }, { unhid = it.id })
    private val loader get() = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()

    private fun show(p: Person) = rule.setContent {
        BookahPhotosTheme { PersonPage(p, index, lib, IntArray(8) { it }, previews, loader, actions, onOpen = {}, onBack = { back++ }, bottomPadding = androidx.compose.ui.unit.Dp(100f)) }
    }

    @Test fun anUnnamedPersonCanBeNamed() {
        show(unnamed)
        rule.onNodeWithTag("person-name").assertIsDisplayed(); rule.onNodeWithText("8 photos").assertIsDisplayed()
        rule.onNodeWithTag("name-button").performClick()
        rule.onNodeWithTag("name-save").assertIsNotEnabled()                               // nothing typed yet
        rule.onNodeWithTag("name-field").performTextInput("Zoe")
        rule.onNodeWithTag("name-save").performClick()
        assertEquals(2L to "Zoe", named); assertNull(merged)
    }

    @Test fun existingNamesAreOfferedWhileTyping() {
        show(unnamed)
        rule.onNodeWithTag("name-button").performClick()
        rule.onNodeWithTag("name-field").performTextInput("an")
        rule.onNodeWithTag("suggest-1").assertIsDisplayed()
        rule.onNodeWithTag("suggest-1").performClick()                                     // fills in "Ann"
        rule.onNodeWithTag("name-save").performClick()
        rule.onNodeWithText("Same person as Ann?").assertIsDisplayed()
    }

    @Test fun aNameThatExistsAsksSamePersonAndYesJoinsThem() {
        show(unnamed)
        rule.onNodeWithTag("name-button").performClick()
        rule.onNodeWithTag("name-field").performTextInput(" ANN ")                         // case and spaces do not matter
        rule.onNodeWithTag("name-save").performClick()
        rule.onNodeWithTag("merge-yes").performClick()
        assertEquals(2L to 1L, merged); assertNull(named)
    }

    @Test fun anAccentInsensitiveMatchAndNoKeepsBoth() {
        show(unnamed)
        rule.onNodeWithTag("name-button").performClick()
        rule.onNodeWithTag("name-field").performTextInput("ελενα")                          // the existing name has an accent
        rule.onNodeWithTag("name-save").performClick()
        rule.onNodeWithText("Same person as Έλενα?").assertIsDisplayed()
        rule.onNodeWithTag("merge-no").performClick()
        assertEquals(2L to "ελενα", named); assertNull(merged)
    }

    @Test fun aNamedPersonCanBeRenamedWithoutAskingAboutThemselves() {
        show(ann)
        rule.onNodeWithTag("name-button").performClick()
        rule.onNodeWithTag("name-field").performTextReplacement("Anne")                  // still not a clash with anybody else
        rule.onNodeWithTag("name-save").performClick()
        assertEquals(1L to "Anne", named)
    }

    @Test fun hidingAsksFirstThenClosesThePage() {
        show(unnamed)
        rule.onNodeWithTag("hide-button").performClick()
        rule.onNodeWithText("Hide this person?").assertIsDisplayed()
        rule.onNodeWithTag("hide-confirm").performClick()
        assertEquals(2L, hid); assertEquals(1, back)
    }

    @Test fun hiddenPeopleCanBeShownAgain() {
        rule.setContent { BookahPhotosTheme { HiddenPeoplePage(index, previews, loader, onShow = { unhid = it.id }, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f)) } }
        rule.onNodeWithTag("show-4").performClick()
        assertEquals(4L, unhid)
    }

    @Test fun thePeoplePageLinksToHiddenPeopleOnlyWhenThereAreSome() {
        var opened = 0
        rule.setContent { BookahPhotosTheme { PeoplePage(index, previews, loader, onPerson = {}, onBack = {}, bottomPadding = androidx.compose.ui.unit.Dp(100f), onHidden = { opened++ }) } }
        rule.onNodeWithTag("hidden-link").assertIsDisplayed()
        rule.onNodeWithTag("hidden-link").performClick()
        assertEquals(1, opened)
    }
}
