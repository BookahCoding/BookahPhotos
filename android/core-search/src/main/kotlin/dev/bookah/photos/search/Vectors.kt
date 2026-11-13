package dev.bookah.photos.search

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** float16 (IEEE half, as numpy stores it) to float. Pure Kotlin so it runs in JVM tests; checked against numpy values. */
fun halfToFloat(bits: Int): Float {
    val s = (bits shr 15) and 1
    val e = (bits shr 10) and 0x1F
    val m = bits and 0x3FF
    val v = when {
        e == 0 -> if (m == 0) 0f else Math.scalb(m.toFloat(), -24)                  // subnormal: m * 2^-24
        e == 31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
        else -> Math.scalb((m or 0x400).toFloat(), e - 25)                          // (1024 + m) * 2^(e - 25)
    }
    return if (s == 1) -v else v
}

/** One search result: the Nextcloud file id and its cosine score. */
class Hit(val id: Long, val score: Float)

/**
 * All the content vectors of the snapshot, decoded once into one flat float array (24,500 x 512 floats is about 50 MB), so a search is a plain dot-product
 * loop. [modelId] says which model made them: a question may only be compared with vectors of the same model.
 */
class VectorIndex(val modelId: String, val dim: Int, val ids: LongArray, private val data: FloatArray) {
    val size: Int get() = ids.size

    /** The [k] best matches for a unit-length [query], best first. */
    fun search(query: FloatArray, k: Int): List<Hit> {
        require(query.size == dim) { "query has ${query.size} numbers, the index has $dim" }
        val n = size
        if (n == 0 || k <= 0) return emptyList()
        val keep = minOf(k, n)
        val topScore = FloatArray(keep) { Float.NEGATIVE_INFINITY }
        val topIdx = IntArray(keep) { -1 }
        var worst = 0                                                                     // position of the lowest kept score
        var base = 0
        for (i in 0 until n) {
            var s = 0f
            for (j in 0 until dim) s += data[base + j] * query[j]
            base += dim
            if (s > topScore[worst]) {
                topScore[worst] = s; topIdx[worst] = i
                worst = 0
                for (t in 1 until keep) if (topScore[t] < topScore[worst]) worst = t
            }
        }
        return (0 until keep).filter { topIdx[it] >= 0 }.sortedByDescending { topScore[it] }.map { Hit(ids[topIdx[it]], topScore[it]) }
    }

    companion object {
        /** Reads `asset_vectors` of a snapshot (schema 1.1). Null when the snapshot has none (an older index). Rows of another dimension are skipped. */
        fun load(snapshot: File): VectorIndex? = runCatching {
            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val count = db.rawQuery("SELECT COUNT(*) FROM asset_vectors", null).use { it.moveToFirst(); it.getInt(0) }
                if (count == 0) return@use null
                var model = ""; var dim = 0
                db.rawQuery("SELECT model_id, dim FROM asset_vectors LIMIT 1", null).use { it.moveToFirst(); model = it.getString(0); dim = it.getInt(1) }
                val ids = LongArray(count); val data = FloatArray(count * dim)
                var n = 0
                db.rawQuery("SELECT asset_id, vec FROM asset_vectors WHERE model_id = ? AND dim = ?", arrayOf(model, dim.toString())).use { c ->
                    while (c.moveToNext() && n < count) {
                        val b = c.getBlob(1)
                        if (b.size != 2 * dim) continue
                        ids[n] = c.getLong(0)
                        val o = n * dim
                        for (j in 0 until dim) data[o + j] = halfToFloat((b[2 * j].toInt() and 0xFF) or ((b[2 * j + 1].toInt() and 0xFF) shl 8))
                        n++
                    }
                }
                if (n == count) VectorIndex(model, dim, ids, data) else VectorIndex(model, dim, ids.copyOf(n), data.copyOf(n * dim))
            }
        }.getOrNull()

        fun of(modelId: String, dim: Int, ids: LongArray, data: FloatArray) = VectorIndex(modelId, dim, ids, data)
    }
}

/** One tag label with its names, for the category chips. */
class TagLabel(val key: String, val nameEn: String, val nameEl: String, val count: Int) {
    fun name(greek: Boolean) = if (greek) nameEl else nameEn
}

/** The tags of a snapshot: the labels and, per label, the files that carry it (best score first). */
class TagIndex(val labels: List<TagLabel>, private val byLabel: Map<String, LongArray>) {
    fun filesFor(key: String): LongArray = byLabel[key] ?: LongArray(0)

    companion object {
        val EMPTY = TagIndex(emptyList(), emptyMap())

        fun load(snapshot: File): TagIndex = runCatching {
            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val files = LinkedHashMap<String, ArrayList<Long>>()
                db.rawQuery("SELECT label_key, asset_id FROM tags ORDER BY label_key, score DESC", null).use { c ->
                    while (c.moveToNext()) files.getOrPut(c.getString(0)) { ArrayList() }.add(c.getLong(1))
                }
                val labels = ArrayList<TagLabel>()
                db.rawQuery("SELECT key, name_en, name_el FROM labels", null).use { c ->
                    while (c.moveToNext()) {
                        val n = files[c.getString(0)]?.size ?: 0
                        if (n > 0) labels.add(TagLabel(c.getString(0), c.getString(1), c.getString(2), n))
                    }
                }
                TagIndex(labels.sortedByDescending { it.count }, files.mapValues { it.value.toLongArray() })
            }
        }.getOrDefault(EMPTY)
    }
}
