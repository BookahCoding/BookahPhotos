package dev.bookah.photos.map

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCoreTest {
    private fun p(i: Int, lat: Double, lon: Double) = MapPoint(i, 1000L + i, lat, lon)

    @Test fun `photos close together make one marker, far apart ones stay separate`() {
        val athens = listOf(p(1, 37.9838, 23.7275), p(2, 37.9860, 23.7330), p(3, 37.9880, 23.7220))        // a few hundred metres apart
        val corfu = listOf(p(4, 39.62, 19.92), p(5, 39.6240, 19.9260))
        val all = athens + corfu
        val far = MapClusters.cluster(all, 6.0)
        assertEquals(2, far.size); assertEquals(listOf(2, 3), far.map { it.count }.sorted())
        val near = MapClusters.cluster(all, 17.0)                                         // very zoomed in: every photo alone
        assertEquals(5, near.size)
    }

    @Test fun `the newest photo (highest library position) is the cover and the bounds hold every photo`() {
        val c = MapClusters.cluster(listOf(p(5, 37.0, 22.0), p(9, 37.0001, 22.0001), p(2, 37.0002, 22.0002)), 5.0).single()
        assertEquals(9, c.cover.libIndex)
        assertTrue(c.minLat <= 37.0 && c.maxLat >= 37.0002 && c.minLon <= 22.0 && c.maxLon >= 22.0002)
        assertFalse(c.isOneSpot)
        assertTrue(MapClusters.cluster(listOf(p(1, 37.0, 22.0), p(2, 37.0, 22.0)), 5.0).single().isOneSpot)   // the same spot can never be separated by zooming
    }

    @Test fun `every photo is in exactly one marker, at every zoom`() {
        val pts = (0 until 500).map { p(it, 36.0 + (it % 50) * 0.05, 20.0 + (it / 50) * 0.07) }
        for (z in listOf(2.0, 5.5, 9.0, 13.0, 16.5)) assertEquals(500, MapClusters.cluster(pts, z).sumOf { it.count })
    }

    @Test fun `map requests carry our own User-Agent and never a login or cookie`() {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setBody("tile"))
            val client = MapHttp.newClient("0.14.0")
            // even if some caller handed over an Authorization header or a cookie, they are removed before the request leaves the app
            client.newCall(Request.Builder().url(server.url("/1/2/3.png")).header("Authorization", "Basic c2VjcmV0").header("Cookie", "oc_sessionPassphrase=x").header("User-Agent", "MapLibre Android").build()).execute().close()
            val req = server.takeRequest()
            assertNull(req.getHeader("Authorization")); assertNull(req.getHeader("Cookie"))
            assertEquals(MapHttp.userAgent("0.14.0"), req.getHeader("User-Agent"))
            assertTrue(req.getHeader("User-Agent")!!.startsWith("BookahPhotos/0.14.0"))
            assertFalse("no email address in the User-Agent", req.getHeader("User-Agent")!!.contains("@"))
        } finally { server.shutdown() }
    }

    @Test fun `the style is one raster layer of OpenStreetMap tiles with the attribution and no map fonts`() {
        val s = MapHttp.styleJson()
        assertTrue(s.contains("https://tile.openstreetmap.org/{z}/{x}/{y}.png")); assertTrue(s.contains("© OpenStreetMap contributors"))
        assertFalse(s.contains("glyphs")); assertFalse(s.contains("sprite"))
        assertEquals(50L * 1024 * 1024, MapHttp.CACHE_BYTES)
    }
}
