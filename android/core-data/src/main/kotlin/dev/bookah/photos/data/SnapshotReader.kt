package dev.bookah.photos.data

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** A few facts about a snapshot, for the first screen. The grid itself arrives in the next step. */
data class SnapshotInfo(val generation: Long, val assets: Int, val videos: Int, val undated: Int, val schemaMajor: Int)

/** What is needed to fetch the original of one photo or video from Nextcloud. */
class ShareItem(val id: Long, val path: String, val mime: String)

object SnapshotReader {
    /** Opens the snapshot read-only. Returns null if it cannot be read (the caller keeps the previous one). */
    fun info(file: File): SnapshotInfo? = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val userVersion = db.rawQuery("PRAGMA user_version", null).use { it.moveToFirst(); it.getInt(0) }
            val generation = db.rawQuery("SELECT value FROM meta WHERE key='generation'", null).use { if (it.moveToFirst()) it.getString(0).toLongOrNull() else null } ?: 0L
            db.rawQuery("SELECT COUNT(*), COALESCE(SUM(flags & 1), 0), COALESCE(SUM(taken_utc IS NULL), 0) FROM assets", null).use {
                it.moveToFirst()
                SnapshotInfo(generation, it.getInt(0), it.getInt(1), it.getInt(2), userVersion / 1000)
            }
        }
    }.getOrNull()

    /** Reads the columns the grid needs into a [LibraryIndex]. Returns null if the snapshot cannot be read. */
    fun loadLibrary(file: File): LibraryIndex? = loadRows(file)?.let { LibraryIndex.build(it) }

    /** The snapshot as library rows (with file name and size, which the merge with the phone's photos needs). Null if it cannot be read. */
    fun loadRows(file: File): List<AssetRow>? = loadRowsFrom(file, "assets")

    /** The photos the reader hid (schema 1.3 `hidden_assets`), as the same kind of rows. Empty for an older snapshot. */
    fun loadHiddenRows(file: File): List<AssetRow> = loadRowsFrom(file, "hidden_assets").orEmpty()

    private fun loadRowsFrom(file: File, table: String): List<AssetRow>? = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val rows = ArrayList<AssetRow>(32_768)
            db.rawQuery("SELECT id, day_key, taken_utc, flags, thumbhash, etag, mime_type, duration_ms, path, size FROM $table", null).use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(6).orEmpty()
                    rows.add(AssetRow(
                        id = c.getLong(0),
                        day = if (c.isNull(1)) 0 else LibraryIndex.parseDayKey(c.getString(1)),
                        takenUtc = if (c.isNull(2)) 0L else c.getLong(2),
                        flags = c.getInt(3),
                        thumbhash = if (c.isNull(4)) null else c.getBlob(4),
                        etagHash = (c.getString(5) ?: "").hashCode(),
                        isRaw = mime == "image/x-dcraw",
                        durationMs = if (c.isNull(7)) 0 else c.getInt(7),
                        name = c.getString(8)?.substringAfterLast('/'),
                        size = if (c.isNull(9)) -1L else c.getLong(9),
                    ))
                }
            }
            rows
        }
    }.getOrNull()

    /** Photos that are the same picture saved more than once: asset id -> group number (the smallest file id of the group). Empty for an older snapshot. */
    fun loadDuplicateGroups(file: File): Map<Long, Long> = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val out = HashMap<Long, Long>()
            db.rawQuery("SELECT id, duplicate_group FROM assets WHERE duplicate_group IS NOT NULL", null).use { c -> while (c.moveToNext()) out[c.getLong(0)] = c.getLong(1) }
            out
        }
    }.getOrDefault(emptyMap())

    /** Path and type of the given files, in the order of [ids]; ids that are not in the snapshot are left out. */
    fun shareItems(file: File, ids: LongArray): List<ShareItem> = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val byId = HashMap<Long, ShareItem>()
            for (chunk in ids.asList().chunked(500)) {
                val cursor = db.rawQuery("SELECT id, path, mime_type FROM assets WHERE id IN (${chunk.joinToString(",")})", null)
                try {
                    while (cursor.moveToNext()) {
                        byId[cursor.getLong(0)] = ShareItem(cursor.getLong(0), cursor.getString(1), cursor.getString(2) ?: "application/octet-stream")
                    }
                } finally {
                    cursor.close()
                }
            }
            ids.asList().mapNotNull { byId[it] }
        }
    }.getOrDefault(emptyList())
}
