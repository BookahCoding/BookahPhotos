package dev.bookah.photos.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** One photo or video of this phone, as MediaStore reports it. */
class LocalRow(
    val mediaId: Long,
    val uri: String,
    val name: String,
    val size: Long,
    /** Capture time in UTC milliseconds, 0 when MediaStore has none. */
    val dateTakenMs: Long,
    val mime: String,
    val width: Int,
    val height: Int,
    val durationMs: Int,
    val folder: String,
    val isVideo: Boolean,
)

/** What the merge found, for the counts in Settings. */
class MergeStats(val local: Int, val matched: Int, val phoneOnly: Int, val perFolder: Map<String, Int>)

/** The library rows after merging the index with the phone, and what was found. */
/** [rows] are the photos to show; [hiddenRows] the ones the reader hid (they take part in the merge so their phone copies are hidden too, and never come back as phone-only photos). */
class MergeResult(val rows: List<AssetRow>, val stats: MergeStats, val hiddenRows: List<AssetRow> = emptyList())

/** Date from a file name such as IMG_20261110_192750.jpg, Screenshot_2026-11-10-19-27-50.png or IMG-20241105-WA0012.jpg. Same rule as the indexer. */
object DateFromName {
    private val RX = Regex("(?<!\\d)((?:19|20)\\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\\d|3[01])[-_. T]?([01]\\d|2[0-3])?[-_.:]?([0-5]\\d)?[-_.:]?([0-5]\\d)?(?!\\d)")

    fun parse(name: String): LocalDateTime? {
        val m = RX.find(name) ?: return null
        val g = m.groupValues
        return try {
            LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].ifEmpty { "12" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt())
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Merges the index (Nextcloud) with the photos of this phone into the rows of one library.
 *
 * - The same photo on both sides becomes one row: a copy in the index gets the phone address, so the app shows the local file and never downloads it.
 * - Matching is strict: same file name (any case) and same size, and, when both sides know a capture time, within 36 hours. A wrong "backed up" is
 *   worse than a wrong "not backed up", so when in doubt a photo stays phone-only.
 * - A photo only on the phone gets the negative of its MediaStore id (so ids stay unique and a negative id can never be mistaken for a Nextcloud file id).
 * - Phone dates: the capture time MediaStore knows, else the date in the file name, else unknown. File times (added, modified) are never used.
 */
object LocalMerge {
    fun merge(indexRows: List<AssetRow>, locals: List<LocalRow>, zone: ZoneId = ZoneId.systemDefault(), hidden: Set<Long> = emptySet()): MergeResult {
        val byKey = HashMap<String, ArrayList<Int>>()
        indexRows.forEachIndexed { i, r ->
            val key = keyOf(r.name, r.size)
            if (key != null) byKey.getOrPut(key) { ArrayList() }.add(i)
        }
        val taken = HashSet<Int>()
        val out = ArrayList<AssetRow>(indexRows.size + locals.size)
        val patched = arrayOfNulls<AssetRow>(indexRows.size)
        val folders = HashMap<String, Int>()
        var matched = 0
        var phoneOnly = 0
        for (l in locals) {
            folders.merge(l.folder, 1, Int::plus)
            val localTime = captureSeconds(l, zone)
            val candidate = byKey[keyOf(l.name, l.size)]?.firstOrNull { i ->
                i !in taken && timesAgree(indexRows[i].takenUtc.takeIf { indexRows[i].day != 0 }, localTime)
            }
            if (candidate != null) {
                taken.add(candidate)
                val r = indexRows[candidate]
                // a copy in the index (or a file not indexed yet) with no known date takes the capture time the phone knows, so it is not stuck at "Unknown date"
                val useLocalDate = r.day == 0 && localTime != null
                val day = if (useLocalDate) dayKey(Instant.ofEpochSecond(localTime!!).atZone(zone).toLocalDate()) else r.day
                val taken = if (useLocalDate) localTime!! else r.takenUtc
                patched[candidate] = AssetRow(r.id, day, taken, r.flags, r.thumbhash, r.etagHash, r.isRaw, if (r.durationMs > 0) r.durationMs else l.durationMs, l.uri, l.folder, r.notIndexed, r.name, r.size)
                matched++
            } else {
                phoneOnly++
                out.add(localOnlyRow(l, localTime, zone))
            }
        }
        val hiddenRows = ArrayList<AssetRow>()
        indexRows.forEachIndexed { i, r ->
            val row = patched[i] ?: r
            if (row.id in hidden) hiddenRows.add(row) else out.add(row)
        }
        return MergeResult(out, MergeStats(locals.size, matched, phoneOnly, folders), hiddenRows)
    }

    private fun keyOf(name: String?, size: Long): String? = if (name == null || size < 0) null else name.lowercase() + "|" + size

    /** Both times known and more than 36 hours apart means these are different photos that happen to share a name and size. */
    private fun timesAgree(indexTime: Long?, localTime: Long?): Boolean =
        indexTime == null || localTime == null || kotlin.math.abs(indexTime - localTime) <= 36 * 3600

    private fun captureSeconds(l: LocalRow, zone: ZoneId): Long? {
        if (l.dateTakenMs > 0) return l.dateTakenMs / 1000
        return DateFromName.parse(l.name)?.atZone(zone)?.toEpochSecond()
    }

    private fun localOnlyRow(l: LocalRow, seconds: Long?, zone: ZoneId): AssetRow {
        val day = seconds?.let { dayKey(Instant.ofEpochSecond(it).atZone(zone).toLocalDate()) } ?: 0
        val flags = (if (l.isVideo) LibraryIndex.FLAG_VIDEO else 0) or
            (if (Regex("screenshot|screen[ _-]?shot|στιγμιότυπο", RegexOption.IGNORE_CASE).containsMatchIn(l.name) || l.folder.equals("Screenshots", true)) LibraryIndex.FLAG_SCREENSHOT else 0) or
            (if (l.width > 0 && l.height > 0 && !l.isVideo && maxOf(l.width, l.height) >= 3 * minOf(l.width, l.height)) LibraryIndex.FLAG_PANORAMA else 0)
        return AssetRow(
            id = -l.mediaId, day = day, takenUtc = seconds ?: 0L, flags = flags, thumbhash = null, etagHash = l.mediaId.hashCode(),
            isRaw = false, durationMs = l.durationMs, localUri = l.uri, folder = l.folder, name = l.name, size = l.size,
        )
    }

    private fun dayKey(d: LocalDate) = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth

}

/** Details of a photo that exists only on this phone, from what MediaStore knows (no camera data, no place: those come with the index). */
object LocalDetails {
    fun from(l: LocalRow, zone: ZoneId = ZoneId.systemDefault()): AssetDetails {
        val seconds = if (l.dateTakenMs > 0) l.dateTakenMs / 1000 else DateFromName.parse(l.name)?.atZone(zone)?.toEpochSecond()
        val offsetMin = seconds?.let { zone.rules.getOffset(Instant.ofEpochSecond(it)).totalSeconds / 60 }
        return AssetDetails(
            id = -l.mediaId, path = l.folder + "/" + l.name, mime = l.mime, size = l.size,
            width = l.width.takeIf { it > 0 }, height = l.height.takeIf { it > 0 }, durationMs = l.durationMs.takeIf { it > 0 },
            takenUtc = seconds, offsetMin = offsetMin, zoneAssumed = false, camera = null, lens = null, exposure = null,
            latitude = null, longitude = null, placeName = null, placeNameEl = null,
            flags = if (l.isVideo) LibraryIndex.FLAG_VIDEO else 0,
            phoneOnly = true, onPhone = true, folder = l.folder,
        )
    }
}
