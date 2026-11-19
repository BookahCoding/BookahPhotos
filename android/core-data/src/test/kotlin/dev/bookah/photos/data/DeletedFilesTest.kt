package dev.bookah.photos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeletedFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000_000L
    private fun make(f: File) = DeletedFiles(f, { now }, keepMs = 1000)

    @Test fun deletedPhotosStayGoneAfterARestart() {
        val f = File(tmp.root, "d.txt")
        make(f).add(setOf(7L, 9L))
        val restarted = make(f).also { it.load() }          // a new process reads the saved list
        assertEquals(setOf(7L, 9L), restarted.ids)
    }

    @Test fun anIdLeavesTheListOnceTheIndexNoLongerHasIt() {
        val f = File(tmp.root, "d.txt")
        val d = make(f); d.add(setOf(7L, 9L))
        d.prune(listed = setOf(9L, 100L))                    // the new index dropped 7 but still lists 9
        assertEquals(setOf(9L), d.ids)
        assertEquals(setOf(9L), make(f).also { it.load() }.ids)
    }

    @Test fun aRestoredFileShowsAgainAfterTheKeepTime() {
        val f = File(tmp.root, "d.txt")
        make(f).add(setOf(7L))
        now += 1001
        assertTrue(make(f).also { it.load() }.ids.isEmpty())
    }

    @Test fun aDamagedFileIsIgnored() {
        val f = File(tmp.root, "d.txt").also { it.writeText("x,y\n5\n6,$now") }
        assertEquals(setOf(6L), make(f).also { it.load() }.ids)
    }
}
