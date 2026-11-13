package dev.bookah.photos.collections

import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.NextcloudLists
import dev.bookah.photos.data.PlacesIndex
import dev.bookah.photos.data.SectionId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One card of a Collections row: a title, a cover (a position in the library) and the items it opens, as library positions. */
class CollectionCard(val key: String, val title: String, val coverIndex: Int?, val indices: IntArray) {
    val count: Int get() = indices.size
}

/** The words used on the cards; the app fills them from its string resources. */
class CollectionLabels(
    val favourites: String, val recentlyAdded: String, val videos: String, val screenshots: String, val panoramas: String, val raw: String,
    val notBackedUp: String = "Not backed up", val selfies: String = "Selfies", val motionPhotos: String = "Motion photos",
)

/** Everything the Collections page shows, worked out from the library and the cached Nextcloud lists. */
class CollectionsModel(val cards: Map<SectionId, List<CollectionCard>>) {
    fun of(section: SectionId): List<CollectionCard> = cards[section].orEmpty()

    companion object {
        const val RECENT_LIMIT = 300
        const val RECENT_DAYS = 14

        fun build(lib: LibraryIndex, lists: NextcloudLists?, labels: CollectionLabels, locale: Locale, places: PlacesIndex = PlacesIndex.EMPTY): CollectionsModel {
            fun card(key: String, title: String, indices: IntArray) = CollectionCard(key, title, if (indices.isEmpty()) null else lib.coverIn0(indices), indices)

            val videos = lib.indicesWhere { lib.isVideo(it) }
            val screenshots = lib.indicesWhere { lib.isScreenshot(it) }
            val panoramas = lib.indicesWhere { lib.isPanorama(it) }
            val raws = lib.indicesWhere { lib.isRaw[it] }
            val selfies = lib.indicesWhere { lib.isSelfie(it) }
            val motions = lib.indicesWhere { lib.isMotionPhoto(it) }
            val favourites = lib.indicesOfIds(lists?.favouriteIds.orEmpty())

            val pinned = buildList {
                if (favourites.isNotEmpty()) add(card("favourites", labels.favourites, favourites))
                if (lib.size > 0) add(card("recent", labels.recentlyAdded, lib.recentlyAdded(RECENT_LIMIT)))
                if (videos.isNotEmpty()) add(card("videos", labels.videos, videos))
                if (screenshots.isNotEmpty()) add(card("screenshots", labels.screenshots, screenshots))
                // photos that exist only on this phone: the quickest way to see what would be lost with the phone
                val notBacked = lib.indicesWhere { lib.isLocalOnly(it) }
                if (notBacked.isNotEmpty()) add(card("notbacked", labels.notBackedUp, notBacked))
            }
            val phoneFolders = lib.folders().map { (name, items) -> card("folder:" + name, name, items) }
            val albums = lists?.albums.orEmpty().mapNotNull { a ->
                val items = lib.indicesOfIds(a.itemIds)
                if (items.isEmpty()) null else CollectionCard("album:" + a.name, a.name, a.coverId?.let { lib.indexOfId(it) } ?: items.first(), items)
            }
            val dayFmt = DateTimeFormatter.ofPattern("d MMM", locale)
            val recentDays = lib.recentDays(RECENT_DAYS).asReversed().map { d ->
                val idx = IntArray(d.count) { d.from + it }
                card("day:" + d.day, LibraryIndex.toDate(d.day).format(dayFmt), idx)
            }
            val media = buildList {
                if (videos.isNotEmpty()) add(card("m-videos", labels.videos, videos))
                if (screenshots.isNotEmpty()) add(card("m-screenshots", labels.screenshots, screenshots))
                if (selfies.isNotEmpty()) add(card("m-selfies", labels.selfies, selfies))
                if (panoramas.isNotEmpty()) add(card("m-panoramas", labels.panoramas, panoramas))
                if (motions.isNotEmpty()) add(card("m-motion", labels.motionPhotos, motions))
                if (raws.isNotEmpty()) add(card("m-raw", labels.raw, raws))
            }
            // places: the photos taken there, newest first; names in Greek when the phone is in Greek
            val greek = locale.language == "el"
            val placeCards = places.places.mapNotNull { pl ->
                val idx = lib.indicesOfIds(pl.assetIds.asList()).also { it.sortDescending() }
                if (idx.isEmpty()) null else card("place:" + pl.id, pl.title(greek), idx)
            }
            return CollectionsModel(
                mapOf(SectionId.Places to placeCards, SectionId.Pinned to pinned, SectionId.Albums to albums, SectionId.RecentDays to recentDays, SectionId.MediaTypes to media, SectionId.OnThisPhone to phoneFolders),
            )
        }

        /** A cover for a set of positions: the middle one that is not a video, screenshot or RAW if there is one. */
        private fun LibraryIndex.coverIn0(indices: IntArray): Int {
            val mid = indices.size / 2
            for (step in indices.indices) {
                val at = mid + (step + 1) / 2 * (if (step % 2 == 0) 1 else -1)
                if (at in indices.indices) {
                    val i = indices[at]
                    if (!isVideo(i) && !isScreenshot(i) && !isRaw[i]) return i
                }
            }
            return indices[mid.coerceIn(0, indices.lastIndex)]
        }
    }
}
