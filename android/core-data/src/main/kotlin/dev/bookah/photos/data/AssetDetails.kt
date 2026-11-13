package dev.bookah.photos.data

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Everything the details sheet shows about one photo or video. */
data class AssetDetails(
    val id: Long, val path: String, val mime: String, val size: Long,
    val width: Int?, val height: Int?, val durationMs: Int?,
    val takenUtc: Long?, val offsetMin: Int?, val zoneAssumed: Boolean,
    val camera: String?, val lens: String?, val exposure: Exposure?,
    val latitude: Double?, val longitude: Double?, val placeName: String?, val placeNameEl: String?,
    val flags: Int,
    /** True when the photo exists only on this phone (not backed up yet). */
    val phoneOnly: Boolean = false,
    /** True when a copy is on this phone (the app then shows that copy and does not download). */
    val onPhone: Boolean = false,
    /** Device folder when the photo is on this phone. */
    val folder: String? = null,
    /** True when the file was added to Nextcloud after the index was made. */
    val notIndexed: Boolean = false,
) {
    val fileName: String get() = path.substringAfterLast('/')
    val isVideo: Boolean get() = flags and LibraryIndex.FLAG_VIDEO != 0
    fun place(greek: Boolean): String? = if (greek) (placeNameEl ?: placeName) else (placeName ?: placeNameEl)
}

/** Camera settings as the indexer stored them (JSON with ExposureTime, FNumber, ISO, FocalLength). Missing values stay null. */
data class Exposure(val exposureTime: Double?, val fNumber: Double?, val iso: Double?, val focalLength: Double?) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String?): Exposure? {
            if (text.isNullOrBlank()) return null
            val o: JsonObject = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            fun num(k: String): Double? = (o[k] as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.content.toDoubleOrNull() }
            return Exposure(num("ExposureTime"), num("FNumber"), num("ISO"), num("FocalLength"))
        }
    }
}

object Formats {
    /** "26 December 2018" in the phone's language, in the photo's own clock (its recorded UTC offset). Null when the date is unknown. */
    fun dateLong(takenUtc: Long?, offsetMin: Int?, locale: Locale): String? {
        val t = at(takenUtc, offsetMin) ?: return null
        return t.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))
    }

    /** A full date and time line for the details sheet, for example "Wednesday, 11 November 2026 at 19:56". */
    fun dateTimeFull(takenUtc: Long?, offsetMin: Int?, locale: Locale): String? {
        val t = at(takenUtc, offsetMin) ?: return null
        val date = t.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
        return "$date · ${t.format(DateTimeFormatter.ofPattern("HH:mm", locale))}"
    }

    fun time(takenUtc: Long?, offsetMin: Int?, locale: Locale): String? =
        at(takenUtc, offsetMin)?.format(DateTimeFormatter.ofPattern("HH:mm", locale))

    private fun at(takenUtc: Long?, offsetMin: Int?) = takenUtc?.let {
        Instant.ofEpochSecond(it).atOffset(ZoneOffset.ofTotalSeconds((offsetMin ?: 0) * 60))
    }

    /** "12 MP", "3.0 MP" */
    fun megapixels(w: Int?, h: Int?): String? {
        if (w == null || h == null || w <= 0 || h <= 0) return null
        val mp = w.toLong() * h / 1_000_000.0
        return if (mp >= 10) "%.0f MP".format(Locale.ROOT, mp) else "%.1f MP".format(Locale.ROOT, mp)
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(Locale.ROOT, bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(Locale.ROOT, bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(Locale.ROOT, bytes / 1024.0)
        else -> "$bytes B"
    }

    fun shutter(seconds: Double?): String? = when {
        seconds == null || seconds <= 0 -> null
        seconds >= 1 -> "%.1f s".format(Locale.ROOT, seconds).replace(".0 s", " s")
        else -> "1/${Math.round(1 / seconds)} s"
    }

    fun aperture(f: Double?): String? = f?.takeIf { it > 0 }?.let { "ƒ/" + trimZero(it) }
    fun focal(mm: Double?): String? = mm?.takeIf { it > 0 }?.let { trimZero(it) + " mm" }
    fun iso(v: Double?): String? = v?.takeIf { it > 0 }?.let { "ISO " + trimZero(it) }
    private fun trimZero(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(Locale.ROOT, v)

    /** Duration of a video: "0:05", "1:02:03". */
    fun duration(ms: Int): String {
        val s = (ms + 500) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}

object DetailsReader {
    fun read(file: File, id: Long): AssetDetails? = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val c = db.rawQuery(
                "SELECT a.id, a.path, a.mime_type, a.size, a.width, a.height, a.duration_ms, a.taken_utc, a.taken_offset_min, a.taken_zone_assumed, " +
                    "a.camera, a.lens, a.exposure, a.latitude, a.longitude, p.name, p.name_el, a.flags " +
                    "FROM assets a LEFT JOIN places p ON p.id = a.place_id WHERE a.id = ?", arrayOf(id.toString()),
            )
            try {
                if (!c.moveToFirst()) return@use null
                fun i(n: Int): Int? = if (c.isNull(n)) null else c.getInt(n)
                fun d(n: Int): Double? = if (c.isNull(n)) null else c.getDouble(n)
                AssetDetails(
                    id = c.getLong(0), path = c.getString(1), mime = c.getString(2) ?: "", size = c.getLong(3),
                    width = i(4), height = i(5), durationMs = i(6),
                    takenUtc = if (c.isNull(7)) null else c.getLong(7), offsetMin = i(8), zoneAssumed = c.getInt(9) == 1,
                    camera = c.getString(10), lens = c.getString(11), exposure = Exposure.parse(c.getString(12)),
                    latitude = d(13), longitude = d(14), placeName = c.getString(15), placeNameEl = c.getString(16), flags = c.getInt(17),
                )
            } finally {
                c.close()
            }
        }
    }.getOrNull()
}
