package dev.bookah.photos.data

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** A place of the snapshot (a town or village from GeoNames) with the photos taken there. */
class Place(val id: Long, val name: String, val nameEl: String?, val region: String?, val latitude: Double, val longitude: Double, val assetIds: LongArray) {
    fun title(greek: Boolean) = if (greek && !nameEl.isNullOrBlank()) nameEl else name
    val photoCount: Int get() = assetIds.size
}

/** One photo with a position, for the map. */
class GeoPhoto(val assetId: Long, val latitude: Double, val longitude: Double)

/** The places (most photos first) and every photo that has a position. */
class PlacesIndex(places: List<Place>, val photos: List<GeoPhoto>) {
    val places: List<Place> = places.sortedWith(compareByDescending<Place> { it.photoCount }.thenBy { it.id })
    val isEmpty: Boolean get() = places.isEmpty()
    fun byId(id: Long): Place? = places.firstOrNull { it.id == id }

    companion object { val EMPTY = PlacesIndex(emptyList(), emptyList()) }
}

object PlacesReader {
    fun load(file: File): PlacesIndex = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val byPlace = HashMap<Long, ArrayList<Long>>()
            val photos = ArrayList<GeoPhoto>()
            db.rawQuery("SELECT id, place_id, latitude, longitude FROM assets WHERE latitude IS NOT NULL AND longitude IS NOT NULL", null).use { c ->
                while (c.moveToNext()) {
                    photos.add(GeoPhoto(c.getLong(0), c.getDouble(2), c.getDouble(3)))
                    if (!c.isNull(1)) byPlace.getOrPut(c.getLong(1)) { ArrayList() }.add(c.getLong(0))
                }
            }
            val places = ArrayList<Place>()
            db.rawQuery("SELECT id, name, name_el, region, latitude, longitude FROM places", null).use { c ->
                while (c.moveToNext()) {
                    val ids = byPlace[c.getLong(0)] ?: continue
                    places.add(Place(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getDouble(4), c.getDouble(5), ids.toLongArray()))
                }
            }
            PlacesIndex(places, photos)
        }
    }.getOrDefault(PlacesIndex.EMPTY)
}
