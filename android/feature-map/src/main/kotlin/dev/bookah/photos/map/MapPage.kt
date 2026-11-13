package dev.bookah.photos.map

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.ImageLoader
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.design.PhotoTheme
import dev.bookah.photos.library.PhotoImage
import dev.bookah.photos.library.Previews
import dev.bookah.photos.library.RoundButton
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import kotlin.math.roundToInt

private var configured = false

/** Sets up the map library once: our own HTTP client for tiles (own User-Agent, no login) and a cache of at most 50 MB. Nothing is ever downloaded ahead. */
private fun configureMap(context: Context, appVersion: String) {
    if (configured) return
    MapLibre.getInstance(context.applicationContext)                       // first: the HTTP part of the library needs it
    HttpRequestUtil.setOkHttpClient(MapHttp.newClient(appVersion))
    configured = true
    runCatching {
        OfflineManager.getInstance(context.applicationContext).setMaximumAmbientCacheSize(MapHttp.CACHE_BYTES, object : OfflineManager.FileSourceCallback {
            override fun onSuccess() {}
            override fun onError(message: String) {}
        })
    }
}

/**
 * The map: OpenStreetMap tiles, and the photos of the library that have a position as round markers (a small picture of the newest photo, and a count when
 * there are several). Markers are drawn by the app, not by the map library, so the counts and pictures need no map fonts. Tap a marker with several photos to
 * zoom in on them, tap one that cannot be separated (or a single photo) to open its photos. "© OpenStreetMap contributors" is always shown on the map.
 */
@Composable
fun MapPage(
    points: List<MapPoint>, lib: LibraryIndex, previews: Previews, loader: ImageLoader, appVersion: String,
    onOpen: (List<MapPoint>) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier,
    /** Where to look at first (when the map comes back after the reader looked at photos); null fits all the photos. */
    initialCamera: MapCamera? = null, onCamera: (MapCamera) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val mapView = remember { configureMap(context, appVersion); MapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val onOpenNow by rememberUpdatedState(onOpen)
    val onCameraNow by rememberUpdatedState(onCamera)
    var zoomNow by remember { mutableStateOf(0.0) }

    // the map view needs every life cycle step of the screen, and must be destroyed when the screen goes
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, mapView) {
        mapView.onCreate(null as Bundle?)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> {}
                Lifecycle.Event.ON_PAUSE -> {}
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            mapView.onStop(); mapView.onDestroy()
        }
    }

    Box(modifier.fillMaxSize().background(PhotoTheme.colors.background).testTag("map-page").semantics { stateDescription = "zoom %.1f".format(java.util.Locale.ROOT, zoomNow) }) {
        AndroidView(factory = {
            mapView.apply {
                getMapAsync { m ->
                    m.setStyle(Style.Builder().fromJson(MapHttp.styleJson()))
                    m.uiSettings.apply { isRotateGesturesEnabled = false; isTiltGesturesEnabled = false; isAttributionEnabled = false; isLogoEnabled = false }
                    m.setMinZoomPreference(2.0); m.setMaxZoomPreference(18.0)
                    if (initialCamera != null) {
                        m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(initialCamera.lat, initialCamera.lon), initialCamera.zoom))
                    } else if (points.isNotEmpty()) {
                        val b = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
                        runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 120)) }
                        if (m.cameraPosition.zoom > 14) m.moveCamera(CameraUpdateFactory.zoomTo(12.0))
                    }
                    m.addOnCameraMoveListener { tick++ }
                    m.addOnCameraIdleListener { tick++; m.cameraPosition.target?.let { t -> onCameraNow(MapCamera(t.latitude, t.longitude, m.cameraPosition.zoom)) } }
                    map = m
                    tick++
                }
            }
        }, modifier = Modifier.fillMaxSize())

        val m = map
        if (m != null) {
            @Suppress("UNUSED_EXPRESSION") tick                                    // redraw the markers whenever the map moves
            val zoom = m.cameraPosition.zoom
            zoomNow = zoom
            val clusters = remember(points, (zoom * 2).toInt()) { MapClusters.cluster(points, (zoom * 2).toInt() / 2.0) }
            val region = m.projection.visibleRegion.latLngBounds
            val padLat = (region.latitudeNorth - region.latitudeSouth) * 0.15
            val padLon = (region.longitudeEast - region.longitudeWest) * 0.15
            val half = with(density) { 26.dp.roundToPx() }
            for (c in clusters) {
                if (c.lat > region.latitudeNorth + padLat || c.lat < region.latitudeSouth - padLat || c.lon > region.longitudeEast + padLon || c.lon < region.longitudeWest - padLon) continue
                val pt = m.projection.toScreenLocation(LatLng(c.lat, c.lon))
                Box(
                    Modifier.offset { IntOffset(pt.x.roundToInt() - half, pt.y.roundToInt() - half) }.size(52.dp)
                        .semantics { contentDescription = "map-marker ${c.count}" }
                        .clickable {
                            if (c.count == 1 || c.isOneSpot || zoom >= 16.5) onOpenNow(c.points)
                            else {
                                val b = LatLngBounds.Builder().include(LatLng(c.minLat, c.minLon)).include(LatLng(c.maxLat, c.maxLon)).build()
                                m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, with(density) { 90.dp.roundToPx() }), 450)
                            }
                        },
                ) {
                    Box(Modifier.size(52.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape)) {
                        PhotoImage(c.cover.libIndex, lib, 256, previews, loader, Modifier.fillMaxSize())
                    }
                    if (c.count > 1) {
                        Box(Modifier.align(Alignment.TopEnd).offset(6.dp, (-4).dp).clip(RoundedCornerShape(10.dp)).background(PhotoTheme.colors.accent).padding(horizontal = 6.dp, vertical = 1.dp)) {
                            Text(if (c.count > 999) "999+" else c.count.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        Row(Modifier.padding(top = top + 8.dp, start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onBack)
        }
        if (points.isEmpty()) Text(stringResource(R.string.map_empty), Modifier.align(Alignment.Center).background(PhotoTheme.colors.surface, RoundedCornerShape(16.dp)).padding(16.dp), color = PhotoTheme.colors.textSecondary)
        val uri = LocalUriHandler.current
        Text(
            MapHttp.ATTRIBUTION, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = bottom + 8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCCFFFFFF))
                .clickable { runCatching { uri.openUri(MapHttp.COPYRIGHT_URL) } }.padding(horizontal = 8.dp, vertical = 3.dp).testTag("map-attribution"),
            color = Color(0xFF222222), style = MaterialTheme.typography.labelSmall,
        )
    }
}
