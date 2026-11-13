package dev.bookah.photos.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.LocalMerge
import dev.bookah.photos.data.MemoriesReader
import dev.bookah.photos.data.PeopleReader
import dev.bookah.photos.data.PlacesReader
import dev.bookah.photos.data.SnapshotReader
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Times every step the app does at start-up on a REAL snapshot (`/data/local/tmp/bookah/snapshot.db`, not in the repo; skipped without it). Not a check: it prints the numbers. */
@RunWith(AndroidJUnit4::class)
class StartupTimingTest {
    @Test fun timesTheStartUpSteps() {
        val snap = File("/data/local/tmp/bookah/snapshot.db")
        assumeTrue("push snapshot.db to /data/local/tmp/bookah/ to run this", snap.exists())
        fun <T> step(name: String, block: () -> T): T { val t0 = System.nanoTime(); val r = block(); android.util.Log.i("STARTUP", "$name ${(System.nanoTime() - t0) / 1_000_000} ms"); return r }
        repeat(2) { round ->
            android.util.Log.i("STARTUP", "--- round ${round + 1}")
            val rows = step("rows (assets)") { SnapshotReader.loadRows(snap)!! }
            val hidden = step("hidden rows") { SnapshotReader.loadHiddenRows(snap) }
            step("duplicate groups") { SnapshotReader.loadDuplicateGroups(snap) }
            step("memories") { MemoriesReader.load(snap) }
            step("places") { PlacesReader.load(snap) }
            step("people (faces, unassigned)") { PeopleReader.load(snap) }
            step("vectors + tags (search)") { VectorIndex.load(snap); TagIndex.load(snap) }
            step("merge + build the library") { LibraryIndex.build(LocalMerge.merge(rows + hidden, emptyList(), hidden = hidden.map { it.id }.toSet()).rows) }
        }
    }
}
