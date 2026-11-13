package dev.bookah.photos.data

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.LocalDate

/**
 * One memory of the snapshot (a trip, a month, "on this day", a person over the years). Always identify a memory by its [key], never by a number: the
 * numbers in the snapshot are given out again at every build. [assetIds] are in time order.
 */
class Memory(
    val key: String, val type: String, val titleEn: String, val titleEl: String,
    val startDay: String, val endDay: String, val coverAsset: Long, val assetIds: LongArray,
) {
    fun title(greek: Boolean) = if (greek) titleEl else titleEn

    /** For a person memory the key is `person:<id>`. */
    val personId: Long? get() = if (type == TYPE_PERSON) key.substringAfter("person:", "").toLongOrNull() else null

    companion object {
        const val TYPE_TRIP = "trip"
        const val TYPE_MONTH = "month"
        const val TYPE_ON_THIS_DAY = "on_this_day"
        const val TYPE_PERSON = "person"
    }
}

/** The memories of a snapshot, and which ones to show. */
class MemoriesIndex(val all: List<Memory>) {
    val isEmpty: Boolean get() = all.isEmpty()
    fun byKey(key: String): Memory? = all.firstOrNull { it.key == key }

    private fun shown(unwanted: Set<String>, people: PeopleIndex, inLibrary: (Long) -> Boolean): List<Memory> = all.filter { m ->
        m.key !in unwanted && m.assetIds.any(inLibrary) &&
            (m.personId == null || people.people.any { it.id == m.personId })           // memories of hidden or merged-away people are not shown
    }

    /**
     * The memories for the Collections row: the one for today first ("on this day": only today's, never the other 360), then the newest trips and months,
     * then the people. [unwanted] are keys the reader said no to (also those not yet in the snapshot).
     */
    fun forRow(today: LocalDate, unwanted: Set<String>, people: PeopleIndex, inLibrary: (Long) -> Boolean, limit: Int = 14): List<Memory> = ordered(today, unwanted, people, inLibrary).take(limit)

    /** Everything for the "see all" page, in the same order. */
    fun forAll(today: LocalDate, unwanted: Set<String>, people: PeopleIndex, inLibrary: (Long) -> Boolean): List<Memory> = ordered(today, unwanted, people, inLibrary)

    private fun ordered(today: LocalDate, unwanted: Set<String>, people: PeopleIndex, inLibrary: (Long) -> Boolean): List<Memory> {
        val list = shown(unwanted, people, inLibrary)
        val todayKey = "otd:%02d-%02d".format(today.monthValue, today.dayOfMonth)
        val otd = list.filter { it.type == Memory.TYPE_ON_THIS_DAY && it.key == todayKey }
        val timeline = list.filter { it.type == Memory.TYPE_TRIP || it.type == Memory.TYPE_MONTH }.sortedByDescending { it.endDay }
        val persons = list.filter { it.type == Memory.TYPE_PERSON }.sortedBy { it.key }
        return otd + timeline + persons
    }

    companion object { val EMPTY = MemoriesIndex(emptyList()) }
}

object MemoriesReader {
    /** Reads the memories (with their photos). Empty for a snapshot that has none. */
    fun load(file: File): MemoriesIndex = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val assets = HashMap<Long, ArrayList<Long>>()
            db.rawQuery("SELECT memory_id, asset_id FROM memory_assets ORDER BY memory_id, position", null).use { c ->
                while (c.moveToNext()) assets.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getLong(1))
            }
            val out = ArrayList<Memory>()
            db.rawQuery("SELECT id, key, type, title_en, title_el, start_day, end_day, cover_asset FROM memories", null).use { c ->
                while (c.moveToNext()) {
                    val list = assets[c.getLong(0)] ?: continue
                    out.add(Memory(c.getString(1) ?: continue, c.getString(2), c.getString(3), c.getString(4), c.getString(5).orEmpty(), c.getString(6).orEmpty(), c.getLong(7), list.toLongArray()))
                }
            }
            MemoriesIndex(out)
        }
    }.getOrDefault(MemoriesIndex.EMPTY)
}

/** Which memory keys the reader said "don't show" in corrections that the snapshot does not contain yet (in time order: an Undo cancels it). */
object MemoryOverlay {
    fun unwantedKeys(ops: List<PendingOp>): Set<String> {
        val keys = HashSet<String>()
        for (op in ops.sortedBy { it.json["t"]?.toString().orEmpty() }) {
            val key = runCatching { (op.json["key"] as? kotlinx.serialization.json.JsonPrimitive)?.content }.getOrNull() ?: continue
            when (op.type) { "unwanted_memory" -> keys.add(key); "wanted_memory" -> keys.remove(key) }
        }
        return keys
    }
}
