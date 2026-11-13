package dev.bookah.photos.map

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * The OpenStreetMap tile server is a public service with a usage policy (https://operations.osmfoundation.org/policies/tiles/): an own User-Agent that
 * names the app (never a person's email), no bulk downloading, a cache that keeps tiles, and visible attribution. This client is used ONLY for map tiles:
 * it is not the client with the Nextcloud login, and it removes any Authorization or Cookie header as a second guard.
 */
object MapHttp {
    const val TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    const val ATTRIBUTION = "© OpenStreetMap contributors"
    const val COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
    const val CACHE_BYTES = 50L * 1024 * 1024

    fun userAgent(appVersion: String) = "BookahPhotos/$appVersion (private family photo app; Android)"

    fun newClient(appVersion: String): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent(appVersion)).removeHeader("Authorization").removeHeader("Cookie").build())
        })
        .build()

    /** The map style: one raster layer of OpenStreetMap tiles, with the attribution in the source (and shown by the app on the map). */
    fun styleJson(): String = """{"version":8,"sources":{"osm":{"type":"raster","tiles":["$TILE_URL"],"tileSize":256,"maxzoom":19,"attribution":"$ATTRIBUTION"}},"layers":[{"id":"osm","type":"raster","source":"osm"}]}"""
}

/** Where the map was looking (centre and zoom), kept by the caller so the map comes back where it was after the reader looked at some photos. */
class MapCamera(val lat: Double, val lon: Double, val zoom: Double)

/** A photo with a position, as a library position (to show its picture) and a Nextcloud file id. */
class MapPoint(val libIndex: Int, val assetId: Long, val lat: Double, val lon: Double)

/** Photos close together on the screen, drawn as one marker: the newest photo is the cover. */
class MapCluster(val lat: Double, val lon: Double, val points: List<MapPoint>) {
    val count: Int get() = points.size
    val cover: MapPoint get() = points.maxByOrNull { it.libIndex }!!
    val minLat get() = points.minOf { it.lat }
    val maxLat get() = points.maxOf { it.lat }
    val minLon get() = points.minOf { it.lon }
    val maxLon get() = points.maxOf { it.lon }
    /** All photos at the same spot (zooming in would never separate them). */
    val isOneSpot: Boolean get() = (maxLat - minLat) < 1e-5 && (maxLon - minLon) < 1e-5
}

object MapClusters {
    /** The world is 512 dp wide at zoom 0 and doubles with every zoom level (the map library's convention). */
    fun worldDp(zoom: Double) = 512.0 * 2.0.pow(zoom)

    private fun mercatorY(lat: Double): Double {
        val l = lat.coerceIn(-85.0, 85.0) * PI / 180
        return (1 - ln(tan(l) + 1 / kotlin.math.cos(l)) / PI) / 2
    }

    /** Groups points that fall into the same square of [cellDp] dp at this zoom. */
    fun cluster(points: List<MapPoint>, zoom: Double, cellDp: Double = 64.0): List<MapCluster> {
        val cell = cellDp / worldDp(zoom)
        val groups = LinkedHashMap<Long, ArrayList<MapPoint>>()
        for (p in points) {
            val cx = floor(((p.lon + 180) / 360) / cell).toLong()
            val cy = floor(mercatorY(p.lat) / cell).toLong()
            groups.getOrPut(cx * 4_000_003L + cy) { ArrayList() }.add(p)
        }
        return groups.values.map { g -> MapCluster(g.sumOf { it.lat } / g.size, g.sumOf { it.lon } / g.size, g) }
    }
}
