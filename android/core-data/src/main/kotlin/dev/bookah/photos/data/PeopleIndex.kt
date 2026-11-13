package dev.bookah.photos.data

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** One detected face of a snapshot: the box is in fractions of the upright picture (x, y, width, height), as in the snapshot's `faces` table. */
class FaceBox(val id: Long, val assetId: Long, val x: Double, val y: Double, val w: Double, val h: Double, val quality: Float)

/**
 * One person of the snapshot. [box] is the cover face as fractions of the upright picture (x, y, width, height). [assetIds] are the photos the person
 * is in, each once (a photo with two of their faces counts once). [faces] are all their faces (used to name the person in an op and to pick a cover).
 */
class Person(
    val id: Long, val name: String?, val coverFaceId: Long, val coverAssetId: Long, val box: FloatArray, val assetIds: LongArray,
    val hidden: Boolean = false, val faces: List<FaceBox> = emptyList(),
) {
    val photoCount: Int get() = assetIds.size
}

/**
 * The people of a snapshot (with the reader's own changes on top, see [PeopleOverlay]): named people first (most photos first), then the unnamed groups
 * (most photos first). Hidden people are kept apart in [hiddenPeople].
 */
class PeopleIndex(all: List<Person>) {
    private val order = compareByDescending<Person> { !it.name.isNullOrBlank() }.thenByDescending { it.photoCount }.thenBy { it.id }
    val people: List<Person> = all.filter { !it.hidden }.sortedWith(order)
    val hiddenPeople: List<Person> = all.filter { it.hidden }.sortedWith(order)
    val isEmpty: Boolean get() = people.isEmpty()
    fun byId(id: Long): Person? = people.firstOrNull { it.id == id } ?: hiddenPeople.firstOrNull { it.id == id }
    /** Visible people whose name is [name] (ignoring case and accents), for the "same person?" question. */
    fun namedLike(name: String, except: Long? = null): List<Person> {
        val key = foldName(name)
        return if (key.isEmpty()) emptyList() else people.filter { it.id != except && it.name != null && foldName(it.name) == key }
    }
    /** Names that start with what is typed (ignoring case and accents), most photos first, one entry per name. */
    fun suggestions(typed: String, except: Long? = null, limit: Int = 4): List<Person> {
        val key = foldName(typed)
        if (key.isEmpty()) return emptyList()
        return people.filter { it.id != except && it.name != null && foldName(it.name).startsWith(key) }.distinctBy { foldName(it.name!!) }.take(limit)
    }

    companion object {
        val EMPTY = PeopleIndex(emptyList())

        /** Lower case, without accents and extra spaces: "Έλενα" and "ελενα " are the same name. */
        fun foldName(s: String): String =
            java.text.Normalizer.normalize(s.trim().lowercase(), java.text.Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.replace(Regex(" +"), " ")
    }
}

object PeopleReader {
    /** The people of a snapshot: faces of photos that are not in the snapshot are ignored. Empty when the snapshot has no people (older schema). */
    fun load(file: File): PeopleIndex = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            class Row(val id: Long, val name: String?, val cover: Long, val hidden: Boolean)
            val rows = ArrayList<Row>()
            db.rawQuery("SELECT id, name, cover_face_id, hidden FROM people", null).use { c ->
                while (c.moveToNext()) rows.add(Row(c.getLong(0), c.getString(1)?.trim()?.ifBlank { null }, if (c.isNull(2)) -1L else c.getLong(2), c.getInt(3) != 0))
            }
            if (rows.isEmpty()) return@use PeopleIndex.EMPTY
            val faces = HashMap<Long, ArrayList<FaceBox>>()
            db.rawQuery("SELECT f.id, f.asset_id, f.person_id, f.x, f.y, f.w, f.h, COALESCE(f.quality, 0) FROM faces f JOIN assets a ON a.id = f.asset_id WHERE f.person_id IS NOT NULL", null).use { c ->
                while (c.moveToNext()) faces.getOrPut(c.getLong(2)) { ArrayList() }.add(FaceBox(c.getLong(0), c.getLong(1), c.getDouble(3), c.getDouble(4), c.getDouble(5), c.getDouble(6), c.getFloat(7)))
            }
            PeopleIndex(rows.mapNotNull { r -> faces[r.id]?.let { make(r.id, r.name, r.cover, r.hidden, it) } })
        }
    }.getOrDefault(PeopleIndex.EMPTY)

    /** The ids of the corrections (ops) this snapshot already contains (schema 1.2). An older snapshot has no such table: nothing is applied. */
    fun appliedOps(file: File): Set<String> = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT op_id FROM applied_ops", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        }
    }.getOrDefault(emptySet())

    /** A person from their faces: the chosen cover face, or else the clearest face, is the cover. */
    fun make(id: Long, name: String?, cover: Long, hidden: Boolean, faces: List<FaceBox>): Person {
        val c = faces.firstOrNull { it.id == cover } ?: faces.maxByOrNull { it.quality }!!
        return Person(id, name, c.id, c.assetId, floatArrayOf(c.x.toFloat(), c.y.toFloat(), c.w.toFloat(), c.h.toFloat()), faces.map { it.assetId }.distinct().toLongArray(), hidden, faces)
    }
}
