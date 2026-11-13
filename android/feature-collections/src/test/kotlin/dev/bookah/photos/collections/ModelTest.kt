package dev.bookah.photos.collections

import dev.bookah.photos.data.Album
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.NextcloudLists
import dev.bookah.photos.data.SectionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ModelTest {
    private val labels = CollectionLabels("Favourites", "Recently added", "Videos", "Screenshots", "Panoramas", "RAW")

    private fun row(id: Long, day: Int, flags: Int = 0, raw: Boolean = false) = AssetRow(id, day, id, flags, null, 0, raw)

    private fun lib(): LibraryIndex = LibraryIndex.build(listOf(
        row(1, 20240101), row(2, 20240102, flags = LibraryIndex.FLAG_VIDEO), row(3, 20240103, flags = LibraryIndex.FLAG_SCREENSHOT),
        row(4, 20240104, flags = LibraryIndex.FLAG_PANORAMA), row(5, 20240105, raw = true), row(6, 20240105),
    ))

    @Test fun `pinned and media types come from the flags, favourites and albums from Nextcloud`() {
        val lists = NextcloudLists(listOf(1, 6, 999), listOf(Album("Trip", 3, 6, listOf(1, 4, 777)), Album("Empty", 0, null, emptyList())))
        val m = CollectionsModel.build(lib(), lists, labels, Locale.ENGLISH)
        assertEquals(listOf("Favourites", "Recently added", "Videos", "Screenshots"), m.of(SectionId.Pinned).map { it.title })
        assertEquals(2, m.of(SectionId.Pinned).first { it.key == "favourites" }.count)          // 999 is not in the index
        assertEquals(listOf("Videos", "Screenshots", "Panoramas", "RAW"), m.of(SectionId.MediaTypes).map { it.title })
        // an album with nothing in the index is not shown; the cover is the album's own newest photo
        assertEquals(listOf("Trip"), m.of(SectionId.Albums).map { it.title })
        val trip = m.of(SectionId.Albums).single()
        assertEquals(2, trip.count)
        assertEquals(lib().indexOfId(6), trip.coverIndex)
    }

    @Test fun `recent days list the latest days, newest first`() {
        val m = CollectionsModel.build(lib(), null, labels, Locale.ENGLISH)
        val days = m.of(SectionId.RecentDays)
        assertEquals("5 Jan", days.first().title)
        assertEquals(2, days.first().count)
        assertEquals(5, days.size)
    }

    @Test fun `without lists there are no favourites or albums, and an empty library does not crash`() {
        val m = CollectionsModel.build(lib(), null, labels, Locale.ENGLISH)
        assertTrue(m.of(SectionId.Albums).isEmpty())
        assertTrue(m.of(SectionId.Pinned).none { it.key == "favourites" })
        val empty = CollectionsModel.build(LibraryIndex.build(emptyList()), null, labels, Locale.ENGLISH)
        assertTrue(empty.of(SectionId.Pinned).isEmpty())
        assertNull(empty.of(SectionId.MediaTypes).firstOrNull())
    }

    @Test fun `places become cards with the photos there, newest first, in the phone's language, most photos first`() {
        val places = dev.bookah.photos.data.PlacesIndex(listOf(
            dev.bookah.photos.data.Place(1, "Corfu", "Κέρκυρα", null, 39.6, 19.9, longArrayOf(1, 4)),
            dev.bookah.photos.data.Place(2, "Potamos", null, null, 37.0, 22.0, longArrayOf(2, 3, 6, 999)),
            dev.bookah.photos.data.Place(3, "Nowhere", null, null, 0.0, 0.0, longArrayOf(777)),             // none of its photos is in the library
        ), emptyList())
        val en = CollectionsModel.build(lib(), null, labels, Locale.ENGLISH, places).of(SectionId.Places)
        assertEquals(listOf("Potamos", "Corfu"), en.map { it.title })
        assertEquals(3, en[0].count)
        assertTrue(en[0].indices.toList() == en[0].indices.sortedDescending())
        val el = CollectionsModel.build(lib(), null, labels, Locale("el"), places).of(SectionId.Places)
        assertEquals(listOf("Potamos", "Κέρκυρα"), el.map { it.title })                                      // no Greek name: the English one
    }

    @Test fun `selfies and motion photos appear in Media types when there are some`() {
        val withFlags = LibraryIndex.build(listOf(row(1, 20240101, flags = LibraryIndex.FLAG_SELFIE), row(2, 20240102, flags = LibraryIndex.FLAG_MOTION_PHOTO), row(3, 20240103), row(4, 20240104, flags = LibraryIndex.FLAG_SELFIE)))
        val m = CollectionsModel.build(withFlags, null, labels, Locale.ENGLISH)
        assertEquals(listOf("Selfies", "Motion photos"), m.of(SectionId.MediaTypes).map { it.title })
        assertEquals(2, m.of(SectionId.MediaTypes).first { it.key == "m-selfies" }.count)
        assertTrue(CollectionsModel.build(lib(), null, labels, Locale.ENGLISH).of(SectionId.MediaTypes).none { it.key == "m-selfies" || it.key == "m-motion" })
    }
}
