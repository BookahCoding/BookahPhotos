package dev.bookah.photos.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One snapshot row, only the columns the grid needs. [day] is yyyymmdd of the local calendar day, 0 when the date is unknown. */
class AssetRow(
    val id: Long,
    val day: Int,
    val takenUtc: Long,
    val flags: Int,
    val thumbhash: ByteArray?,
    val etagHash: Int,
    val isRaw: Boolean,
    val durationMs: Int = 0,
    /** content:// address of the copy on this phone, if there is one (then the app never downloads this photo). */
    val localUri: String? = null,
    /** Name of the device folder (Camera, Screenshots...) when the photo is on this phone. */
    val folder: String? = null,
    /** True for files found in Nextcloud after the index was made: shown, but without people, places or search yet. */
    val notIndexed: Boolean = false,
    /** File name and size, used to recognise the same photo on the phone and in Nextcloud. */
    val name: String? = null,
    val size: Long = -1,
)

/** Which copies of the photos to show. */
enum class Source { All, Nextcloud, Phone }

/** A run of consecutive items of the library, [from] until [toExclusive]. */
data class DayGroup(val day: Int, val from: Int, val toExclusive: Int) { val count get() = toExclusive - from }
data class MonthGroup(val year: Int, val month: Int, val from: Int, val toExclusive: Int, val days: List<DayGroup>) { val count get() = toExclusive - from }
data class YearGroup(val year: Int, val from: Int, val toExclusive: Int) { val count get() = toExclusive - from }

/**
 * The whole library in memory, in grid order: undated first (the oldest end), then by day and time, newest last.
 * Arrays instead of objects keep 50,000 items small and let the grid and the labels read them without waiting for a database.
 */
class LibraryIndex private constructor(rows: List<AssetRow>) {
    val size = rows.size
    val ids = LongArray(size) { rows[it].id }
    val day = IntArray(size) { rows[it].day }
    val takenUtc = LongArray(size) { rows[it].takenUtc }
    val flags = IntArray(size) { rows[it].flags }
    val thumbhash: Array<ByteArray?> = Array(size) { rows[it].thumbhash }
    val etagHash = IntArray(size) { rows[it].etagHash }
    val isRaw = BooleanArray(size) { rows[it].isRaw }
    val durationMs = IntArray(size) { rows[it].durationMs }
    val localUri: Array<String?> = Array(size) { rows[it].localUri }
    val folder: Array<String?> = Array(size) { rows[it].folder }
    val notIndexed = BooleanArray(size) { rows[it].notIndexed }
    private val names: Array<String?> = Array(size) { rows[it].name }
    private val sizes = LongArray(size) { rows[it].size }

    /** Items without a date; they are the first [undatedCount] items. */
    val undatedCount: Int = day.count { it == 0 }
    val days: List<DayGroup>
    val months: List<MonthGroup>
    val years: List<YearGroup>

    init {
        val d = ArrayList<DayGroup>()
        var i = undatedCount
        while (i < size) {
            var j = i
            while (j < size && day[j] == day[i]) j++
            d.add(DayGroup(day[i], i, j)); i = j
        }
        days = d
        val m = ArrayList<MonthGroup>()
        var k = 0
        while (k < d.size) {
            val ym = d[k].day / 100
            var e = k
            while (e < d.size && d[e].day / 100 == ym) e++
            m.add(MonthGroup(ym / 100, ym % 100, d[k].from, d[e - 1].toExclusive, d.subList(k, e).toList())); k = e
        }
        months = m
        val y = ArrayList<YearGroup>()
        var a = 0
        while (a < m.size) {
            var e = a
            while (e < m.size && m[e].year == m[a].year) e++
            y.add(YearGroup(m[a].year, m[a].from, m[e - 1].toExclusive)); a = e
        }
        years = y
    }

    private val idToIndex: HashMap<Long, Int> by lazy { HashMap<Long, Int>(size * 2).also { m -> for (i in 0 until size) m[ids[i]] = i } }

    /** Position of a Nextcloud file id in the library, or null when the index does not hold it (yet). */
    fun indexOfId(id: Long): Int? = idToIndex[id]

    /** Library positions of the given file ids, in the order given; ids not in the index are left out. */
    fun indicesOfIds(fileIds: List<Long>): IntArray = fileIds.mapNotNull { indexOfId(it) }.toIntArray()

    /** Positions of the items for which [test] is true, in library order. */
    fun indicesWhere(test: (Int) -> Boolean): IntArray = (0 until size).filter(test).toIntArray()

    fun isPanorama(i: Int) = flags[i] and FLAG_PANORAMA != 0
    fun isSelfie(i: Int) = flags[i] and FLAG_SELFIE != 0
    fun isMotionPhoto(i: Int) = flags[i] and FLAG_MOTION_PHOTO != 0

    /**
     * The newest uploads: Nextcloud file ids grow as files are added, so the highest ids are the latest additions.
     * Returned newest first.
     */
    fun recentlyAdded(limit: Int): IntArray = (0 until size).sortedByDescending { ids[it] }.take(limit).toIntArray()

    /** The latest [n] days that have items, newest last (the order of the library). */
    fun recentDays(n: Int): List<DayGroup> = days.takeLast(n)

    /** True for a photo that exists only on this phone (its id is the negative MediaStore id). */
    fun isLocalOnly(i: Int) = ids[i] < 0
    fun hasLocalCopy(i: Int) = localUri[i] != null

    fun rowAt(i: Int) = AssetRow(ids[i], day[i], takenUtc[i], flags[i], thumbhash[i], etagHash[i], isRaw[i], durationMs[i], localUri[i], folder[i], notIndexed[i], names[i], sizes[i])

    /** A new index holding only the items at [positions] (library order is kept). */
    fun subset(positions: IntArray): LibraryIndex = LibraryIndex(positions.sorted().map { rowAt(it) })

    /** The library as seen through the source filter: everything, only what is in Nextcloud, or only what is on this phone. */
    fun bySource(source: Source): LibraryIndex = when (source) {
        Source.All -> this
        Source.Nextcloud -> subset(indicesWhere { ids[it] > 0 })
        Source.Phone -> subset(indicesWhere { localUri[it] != null })
    }

    /** Device folders with their items, for "On this phone". Sorted by size, biggest first. */
    fun folders(): List<Pair<String, IntArray>> =
        (0 until size).filter { folder[it] != null }.groupBy { folder[it]!! }.map { (k, v) -> k to v.toIntArray() }.sortedByDescending { it.second.size }

    fun isVideo(i: Int) = flags[i] and FLAG_VIDEO != 0
    fun isScreenshot(i: Int) = flags[i] and FLAG_SCREENSHOT != 0

    /** First item of a given local day, or the first item after it. */
    fun indexOfDay(dayKey: Int): Int {
        var lo = undatedCount; var hi = size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (day[mid] < dayKey) lo = mid + 1 else hi = mid }
        return lo
    }

    /**
     * A cover for a range: the middle photo that is not a video or screenshot (a stable, simple choice; a smarter one comes with the
     * content index in a later phase). Falls back to the middle item.
     */
    fun coverIn(from: Int, toExclusive: Int): Int {
        if (toExclusive <= from) return from
        val mid = (from + toExclusive) / 2
        for (step in 0 until (toExclusive - from)) {
            val up = mid + (step + 1) / 2 * (if (step % 2 == 0) 1 else -1)
            if (up in from until toExclusive && !isVideo(up) && !isScreenshot(up) && !isRaw[up]) return up
        }
        return mid.coerceIn(from, toExclusive - 1)
    }

    /** The label for the items from [from] to [to] (inclusive): "10 Nov 2026", "3 - 10 Nov 2026", "2 Sep - 7 Oct 2026", or "Unknown date". */
    fun rangeLabel(from: Int, to: Int, locale: Locale, unknownLabel: String): String {
        if (size == 0) return ""
        val a = from.coerceIn(0, size - 1); val b = to.coerceIn(a, size - 1)
        val da = day[a]; val db = day[b]
        if (da == 0 && db == 0) return unknownLabel
        val start = toDate(if (da == 0) day[undatedCount.coerceAtMost(size - 1)] else da)
        val end = toDate(db)
        val full = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
        return when {
            start == end -> start.format(full)
            start.year == end.year && start.month == end.month ->
                "${start.dayOfMonth} – ${end.format(full)}"
            start.year == end.year ->
                "${start.format(DateTimeFormatter.ofPattern("d MMM", locale))} – ${end.format(full)}"
            else -> "${start.format(full)} – ${end.format(full)}"
        }
    }

    /** "Nov 2026" for the fast-scroll bubble, or the unknown label. */
    fun monthYearLabel(i: Int, locale: Locale, unknownLabel: String): String {
        if (size == 0) return ""
        val d = day[i.coerceIn(0, size - 1)]
        if (d == 0) return unknownLabel
        return toDate(d).format(DateTimeFormatter.ofPattern("LLL yyyy", locale))
    }

    companion object {
        const val FLAG_VIDEO = 1
        const val FLAG_SCREENSHOT = 2
        const val FLAG_PANORAMA = 8
        const val FLAG_SELFIE = 4
        const val FLAG_MOTION_PHOTO = 16

        /** Sorts the rows into grid order and builds the index. */
        fun build(rows: List<AssetRow>): LibraryIndex = LibraryIndex(rows.sortedWith(compareBy<AssetRow>({ it.day }, { it.takenUtc }, { it.id })))

        fun toDate(dayKey: Int): LocalDate = LocalDate.of(dayKey / 10000, dayKey / 100 % 100, dayKey % 100)

        /** "2024-08-24" -> 20240824, anything else -> 0. */
        fun parseDayKey(s: String?): Int {
            if (s == null || s.length != 10 || s[4] != '-' || s[7] != '-') return 0
            val y = s.substring(0, 4).toIntOrNull() ?: return 0
            val m = s.substring(5, 7).toIntOrNull() ?: return 0
            val d = s.substring(8, 10).toIntOrNull() ?: return 0
            return if (y in 1..9999 && m in 1..12 && d in 1..31) y * 10000 + m * 100 + d else 0
        }
    }
}
