package dev.bookah.photos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleIndexTest {
    private fun person(id: Long, name: String?, photos: Int) = Person(id, name, id * 10, id * 100, floatArrayOf(0.4f, 0.4f, 0.1f, 0.1f), LongArray(photos) { it.toLong() })

    @Test fun `named people come first, each group by number of photos`() {
        // a name of only spaces is not a name
        val idx = PeopleIndex(listOf(person(1, null, 90), person(2, "Ann", 10), person(3, "  ", 50), person(4, "Bob", 30), person(5, null, 5)))
        assertEquals(listOf(4L, 2L, 1L, 3L, 5L), idx.people.map { it.id })
    }

    @Test fun `empty index is empty and finds nobody`() {
        assertTrue(PeopleIndex.EMPTY.isEmpty); assertEquals(null, PeopleIndex.EMPTY.byId(1))
    }
}

class PeopleOnLibraryTest {
    @Test fun `a person's photos are found in the library, also when a photo was merged with its phone copy`() {
        val nextcloud = AssetRow(10, 20260101, 1_767_265_200L, 0, null, 0, false, name = "IMG_0001.jpg", size = 5000)
        val other = AssetRow(11, 20260102, 1_767_351_600L, 0, null, 0, false, name = "IMG_0002.jpg", size = 6000)
        val phone = LocalRow(7, "content://media/external/images/media/7", "img_0001.jpg", 5000, 0, "image/jpeg", 4000, 3000, 0, "Camera", false)
        val merged = LocalMerge.merge(listOf(nextcloud, other), listOf(phone), java.time.ZoneId.of("Europe/Athens"))
        val lib = LibraryIndex.build(merged.rows)
        assertTrue(lib.localUri[lib.indexOfId(10)!!] != null)                    // photo 10 is the merged one
        val p = Person(1, "Ann", 100, 10, floatArrayOf(0.4f, 0.4f, 0.1f, 0.1f), longArrayOf(10, 11, 999))   // 999 is not in the library
        assertEquals(2, lib.indicesOfIds(p.assetIds.asList()).size)
    }
}
