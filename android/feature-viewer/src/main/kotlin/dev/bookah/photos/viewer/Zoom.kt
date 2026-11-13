package dev.bookah.photos.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.max

/** Pinch-zoom state of one page: scale 1 is "fit to the screen". The offset is the pan in pixels, kept inside the zoomed picture. */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)

    val zoomed: Boolean get() = scale > 1.02f

    fun reset() { scale = 1f; offset = Offset.Zero }

    /** Keeps the picture from being dragged away from the screen edges. */
    fun clamp(o: Offset): Offset {
        val maxX = max(0f, size.width * (scale - 1f) / 2f)
        val maxY = max(0f, size.height * (scale - 1f) / 2f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    /** Zooms by [factor] around the screen point [focus] and pans by [pan]. */
    fun transform(focus: Offset, pan: Offset, factor: Float) {
        val newScale = (scale * factor).coerceIn(1f, MAX_SCALE)
        val ratio = newScale / scale
        val centre = Offset(size.width / 2f, size.height / 2f)
        val rel = focus - centre
        offset = clamp(rel * (1f - ratio) + offset * ratio + pan)
        scale = newScale
        if (scale <= 1.001f) offset = Offset.Zero
    }

    companion object { const val MAX_SCALE = 6f }
}

/**
 * Tap, double-tap to zoom, pinch and pan. Single-finger moves are only taken while zoomed in (so the pager can still swipe when
 * the picture fits). Everything is consumed while two fingers are down.
 */
fun Modifier.zoomable(state: ZoomState, onTap: () -> Unit): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { pos ->
                if (state.zoomed) state.reset() else state.transform(pos, Offset.Zero, 2.5f)
            },
        )
    }
    .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val multi = event.changes.count { it.pressed } >= 2
                if (multi || state.zoomed) {
                    val zoom = if (multi) event.calculateZoom() else 1f
                    val pan = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (zoom != 1f || pan != Offset.Zero) {
                        state.transform(if (centroid.isUnspecified) Offset.Zero else centroid, pan, zoom)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
            if (state.scale < 1.05f) state.reset()
        }
    }
    .graphicsLayer {
        scaleX = state.scale; scaleY = state.scale
        translationX = state.offset.x; translationY = state.offset.y
    }
