package dev.bookah.photos.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import coil3.ImageLoader
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.design.PhotoTheme
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** Column counts of the pinch steps (plan 6.3): big cells to the overview. */
val ColumnSteps = intArrayOf(3, 5, 9, 16)

/**
 * The continuous grid. Items are padded at the very start so the last row is full: the newest photo sits at the bottom right.
 * The grid opens scrolled to the newest. Pinch changes the number of columns in steps; the item at the top stays at the top.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AllGrid(
    lib: LibraryIndex,
    previews: Previews,
    loader: ImageLoader,
    columns: Int,
    onColumns: (Int) -> Unit,
    selecting: Boolean,
    selected: Set<Long>,
    onToggle: (Int) -> Unit,
    onStartSelect: (Int) -> Unit,
    /** Selects or deselects every tile from position [from] to [to] (either order), as the finger moves across them. */
    onRangeSelect: (from: Int, to: Int, select: Boolean) -> Unit,
    onOpen: (Int) -> Unit,
    favourites: Set<Long>,
    topPadding: Dp,
    bottomPadding: Dp,
    state: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val pad = (columns - lib.size % columns) % columns
    val total = lib.size + pad
    // the drag-select handler keeps running across recompositions, so it must read the CURRENT values, never the ones from the first composition
    val libNow = rememberUpdatedState(lib)
    val padNowState = rememberUpdatedState(pad)
    val selectingNow = rememberUpdatedState(selecting)
    val selectedNow = rememberUpdatedState(selected)
    val onStartNow = rememberUpdatedState(onStartSelect)
    val onRangeNow = rememberUpdatedState(onRangeSelect)
    val countNow = rememberUpdatedState(lib.size)
    val idAtNow = rememberUpdatedState<(Int) -> Long>({ i -> libNow.value.ids[i] })
    val sizePx = if (columns <= 3) 512 else 256
    val labeller = rememberPhotoLabeller()
    Box(
        modifier.fillMaxSize().pinchColumns(columns, onColumns).dragSelect(state, countNow, idAtNow, padNowState, selectingNow, selectedNow, onStartNow, onRangeNow).semantics { stateDescription = "columns=$columns" }.testTag("grid"),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(total, contentType = { 0 }) { cell ->
                val i = cell - pad
                if (i < 0) {
                    Box(Modifier.aspectRatio(1f))
                } else {
                    val isSel = lib.ids[i] in selected
                    Box(
                        Modifier.aspectRatio(1f)
                            .testTag("cell-${lib.ids[i]}")
                            .semantics { contentDescription = labeller(lib, i); this.selected = isSel }
                            .combinedClickable(onClick = { if (selecting) onToggle(i) else onOpen(i) }),
                    ) {
                        PhotoImage(i, lib, sizePx, previews, loader, Modifier.fillMaxSize())
                        CellBadges(lib, i, columns, lib.ids[i] in favourites)
                        if (selecting) SelectMark(isSel)
                    }
                }
            }
        }
    }
}

@Composable
private fun CellBadges(lib: LibraryIndex, i: Int, columns: Int, favourite: Boolean) {
    if (columns > 9) return          // too small to read
    Box(Modifier.fillMaxSize()) {
        if (lib.isLocalOnly(i)) {
            // only on this phone: not backed up yet
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { PhoneGlyph(Color.White) }
        }
        if (favourite) {
            Icon(Icons.Filled.Favorite, null, Modifier.align(Alignment.BottomEnd).padding(6.dp).size(14.dp), tint = Color.White)
        }
        if (lib.isRaw[i]) {
            Text(stringResource(R.string.raw_tile), Modifier.align(Alignment.Center), color = PhotoTheme.colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (lib.isVideo(i)) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxSize()
                    .background(Brush.verticalGradient(0.7f to Color.Transparent, 1f to Color(0x80000000))),
            )
            androidx.compose.foundation.layout.Row(Modifier.align(Alignment.BottomStart).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(14.dp), tint = Color.White)
                val ms = lib.durationMs[i]
                if (ms > 0 && columns <= 5) Text(durationText(ms), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

internal fun durationText(ms: Int): String {
    val s = (ms + 500) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun SelectMark(selected: Boolean) {
    val c = PhotoTheme.colors
    Box(Modifier.fillMaxSize().background(if (selected) Color(0x33FFFFFF) else Color.Transparent)) {
        Box(
            Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                .background(if (selected) c.accent else Color(0x66000000)),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Icons.Filled.Check, null, Modifier.size(15.dp), tint = Color.White) }
    }
}

/**
 * Two-finger pinch changes the column step. It watches the events first and takes over only while two fingers are down, so the grid
 * does not scroll during the pinch. After a step it keeps consuming until every finger is lifted: otherwise the fingers that are still
 * moving would scroll the grid and throw the reader up or down the timeline. The pointer block never restarts (the current column
 * count is read through State), because a restart in the middle of a gesture would drop the fingers.
 */
private fun Modifier.pinchColumns(columns: Int, onColumns: (Int) -> Unit): Modifier = composed {
    val currentColumns by rememberUpdatedState(columns)
    val currentOnColumns by rememberUpdatedState(onColumns)
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var scale = 1f
            var stepped = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (stepped || event.changes.size >= 2) {
                    event.changes.forEach { it.consume() }
                    if (!stepped) {
                        scale *= event.calculateZoom()
                        val step = ColumnSteps.indexOf(currentColumns).coerceAtLeast(0)
                        if (scale > 1.35f && step > 0) { stepped = true; currentOnColumns(ColumnSteps[step - 1]) }          // spread: bigger cells
                        else if (scale < 0.74f && step < ColumnSteps.lastIndex) { stepped = true; currentOnColumns(ColumnSteps[step + 1]) }  // pinch: more columns
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

/** A thin handle at the right edge; dragging it scrolls through the whole library and shows the month in a bubble. */
@Composable
fun FastScroller(
    lib: LibraryIndex,
    state: LazyGridState,
    columns: Int,
    topPadding: Dp,
    bottomPadding: Dp,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }
    val unknown = stringResource(R.string.unknown_date)
    val pad = (columns - lib.size % columns) % columns
    val total = lib.size + pad
    val current by remember { derivedStateOf { if (total <= 1) 0f else state.firstVisibleItemIndex.toFloat() / (total - 1) } }
    val shown = if (dragging) fraction else current
    val itemIndex = ((shown * (total - 1)).roundToInt() - pad).coerceIn(0, (lib.size - 1).coerceAtLeast(0))
    val density = LocalDensity.current
    val thumbH = 44.dp
    BoxWithConstraints(
        modifier.fillMaxHeight().width(36.dp).padding(top = topPadding, bottom = bottomPadding)
            .pointerInput(total) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true; fraction = (it.y / size.height).coerceIn(0f, 1f) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    fraction = (change.position.y / size.height).coerceIn(0f, 1f)
                    scope.launch { state.scrollToItem((fraction * (total - 1)).roundToInt()) }
                }
            },
    ) {
        val travel = (maxHeight - thumbH).coerceAtLeast(0.dp)
        val y = travel * shown
        // the visible handle: a slim rounded bar, wider while dragging
        Box(
            Modifier.align(Alignment.TopEnd).offset(y = y).padding(end = 4.dp).width(if (dragging) 6.dp else 4.dp).height(thumbH)
                .clip(RoundedCornerShape(3.dp)).background(PhotoTheme.colors.textSecondary.copy(alpha = if (dragging) 0.9f else 0.5f)),
        )
        if (dragging && lib.size > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = (-24).dp, y = y).clip(RoundedCornerShape(16.dp))
                    .background(PhotoTheme.colors.barFill).padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text(lib.monthYearLabel(itemIndex, locale, unknown), style = MaterialTheme.typography.titleMedium, color = PhotoTheme.colors.textPrimary) }
        }
    }
}


/**
 * Select by dragging. Two ways, both without lifting the finger:
 *  - press and hold a tile (not selecting yet): select mode starts with that tile, and sliding over more tiles adds them;
 *  - already in select mode: drag sideways over tiles: the first tile decides whether the drag selects or deselects.
 * A mostly vertical drag is left alone so the grid scrolls. Near the top or bottom edge the grid scrolls by itself while dragging.
 * The handler watches events first and never restarts mid-gesture (values are read through lambdas).
 */
fun Modifier.dragSelect(
    state: LazyGridState, countS: State<Int>, idAtS: State<(Int) -> Long>, padS: State<Int>, selectingS: State<Boolean>, selectedS: State<Set<Long>>,
    onStartS: State<(Int) -> Unit>, onRangeS: State<(Int, Int, Boolean) -> Unit>,
): Modifier = pointerInput(Unit) {
    // every value is read through State at the moment it is needed (this block lives as long as the grid does)
    fun count() = countS.value
    fun idAt(i: Int) = idAtS.value(i)
    fun pad() = padS.value
    fun selecting() = selectingS.value
    fun selected() = selectedS.value
    fun onStartSelect(i: Int) = onStartS.value(i)
    fun onRange(a: Int, b: Int, select: Boolean) = onRangeS.value(a, b, select)
    val edge = 90.dp.toPx()
    val longPress = viewConfiguration.longPressTimeoutMillis
    val slop = viewConfiguration.touchSlop

    fun cellAt(p: Offset): Int? {
        // item offsets are measured from the start of the content, after the top padding (the header), so the padding is added back
        val top = state.layoutInfo.beforeContentPadding
        val hit = state.layoutInfo.visibleItemsInfo.firstOrNull {
            p.x >= it.offset.x && p.x < it.offset.x + it.size.width && p.y >= it.offset.y + top && p.y < it.offset.y + top + it.size.height
        } ?: return null
        return (hit.index - pad()).takeIf { it in 0 until count() }
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val startCell = cellAt(down.position) ?: return@awaitEachGesture
        val t0 = System.currentTimeMillis()
        var mode = 0            // 0 undecided, 1 selecting by dragging, 2 left to the grid (scroll, tap, pinch)
        var total = Offset.Zero
        var target = true
        var last = startCell
        var autoDy = 0f
        val scrollJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
            while (true) { if (autoDy != 0f) state.scrollBy(autoDy); kotlinx.coroutines.delay(16) }
        }
        try {
            while (true) {
                val event = if (mode == 0 && !selecting()) {
                    val remaining = longPress - (System.currentTimeMillis() - t0)
                    if (remaining <= 0) null else withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Initial) }
                } else awaitPointerEvent(PointerEventPass.Initial)
                if (event == null) {
                    // held still long enough: select mode starts with this tile
                    if (mode == 0 && total.getDistance() < slop) { mode = 1; target = true; onStartSelect(startCell); last = startCell }
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.size > 1 && mode != 1) mode = 2
                if (change.changedToUpIgnoreConsumed()) {
                    // the release that ends a drag-select is ours: without this the tile's own click fires and opens it or toggles it back off
                    if (mode == 1) change.consume()
                    break
                }
                if (mode == 0) {
                    total += change.positionChange()
                    if (total.getDistance() > slop) {
                        if (selecting() && kotlin.math.abs(total.x) * 1.4f > kotlin.math.abs(total.y)) {
                            mode = 1
                            target = idAt(startCell) !in selected()      // the first tile decides: select or deselect
                            onRange(startCell, startCell, target)
                            last = startCell
                        } else mode = 2
                    }
                }
                if (mode == 1) {
                    change.consume()
                    val idx = cellAt(change.position)
                    if (idx != null && idx != last) { onRange(last, idx, target); last = idx }
                    val y = change.position.y
                    autoDy = when {
                        y < edge -> -((edge - y) / edge) * 40f
                        y > size.height - edge -> ((y - (size.height - edge)) / edge) * 40f
                        else -> 0f
                    }
                }
            }
        } finally {
            scrollJob.cancel()
        }
    }
}
