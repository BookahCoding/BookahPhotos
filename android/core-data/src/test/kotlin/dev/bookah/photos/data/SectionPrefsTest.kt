package dev.bookah.photos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionPrefsTest {
    @Test fun `default shows every section in the default order`() {
        val p = SectionPrefs()
        assertEquals(SectionId.entries, p.visible())
    }

    @Test fun `move changes the order and stays inside the list`() {
        var p = SectionPrefs()
        p = p.move(SectionId.MediaTypes, -1)
        assertEquals(listOf(SectionId.Pinned, SectionId.Memories, SectionId.People, SectionId.Places, SectionId.Trips, SectionId.Albums, SectionId.MediaTypes, SectionId.RecentDays, SectionId.OnThisPhone, SectionId.Utilities), p.order)
        assertEquals(p, p.move(SectionId.Pinned, -1))            // already first
        assertEquals(p, p.move(SectionId.Utilities, 1))          // already last
    }

    @Test fun `hide and collapse are separate`() {
        var p = SectionPrefs().toggleHidden(SectionId.Albums).toggleCollapsed(SectionId.Pinned)
        assertFalse(SectionId.Albums in p.visible())
        assertTrue(SectionId.Pinned in p.visible() && SectionId.Pinned in p.collapsed)
        p = p.showAll()
        assertEquals(SectionId.entries, p.visible()); assertTrue(p.collapsed.isEmpty())
        assertEquals(SectionId.entries.toSet(), p.collapseAll().collapsed)
    }

    @Test fun `a save survives and an old or damaged save is repaired`() {
        val p = SectionPrefs().move(SectionId.Albums, 2).toggleHidden(SectionId.Pinned).toggleCollapsed(SectionId.MediaTypes)
        assertEquals(p, SectionPrefs.parse(p.serialize()))
        // a save from an older version that did not know MediaTypes, plus an entry that no longer exists
        val old = SectionPrefs.parse("Albums,Pinned,Gone,RecentDays|Gone|")
        assertEquals(listOf(SectionId.Albums, SectionId.Pinned, SectionId.RecentDays, SectionId.Memories, SectionId.People, SectionId.Places, SectionId.Trips, SectionId.MediaTypes, SectionId.OnThisPhone, SectionId.Utilities), old.order)
        assertTrue(old.hidden.isEmpty())
        assertEquals(SectionPrefs(), SectionPrefs.parse(null))
        assertEquals(SectionPrefs(), SectionPrefs.parse("garbage"))
    }

    @Test fun `an order saved before People, Memories, Places and Utilities existed keeps its order and gets them at the end`() {
        val saved = "Pinned,Albums,RecentDays,MediaTypes,OnThisPhone|Albums|Pinned"
        val p = SectionPrefs.parse(saved)
        assertEquals(listOf(SectionId.Pinned, SectionId.Albums, SectionId.RecentDays, SectionId.MediaTypes, SectionId.OnThisPhone, SectionId.Memories, SectionId.People, SectionId.Places, SectionId.Trips, SectionId.Utilities), p.order)
        assertEquals(setOf(SectionId.Albums), p.hidden); assertEquals(setOf(SectionId.Pinned), p.collapsed)
        assertTrue(SectionId.People in p.visible())
    }
}
