package dev.bookah.photos.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.FavouritesOverlay
import dev.bookah.photos.data.Source
import dev.bookah.photos.design.PhotoTheme
import java.util.Locale

/**
 * Lets the viewer ask the Library where a photo's cell is (to grow from it and shrink back into it) and to scroll a photo into view.
 * Filled in by [LibraryScreen]; cell rectangles are in the Library's own coordinates, which fill the screen.
 */
class LibraryController {
    internal var rectOf: (Int) -> androidx.compose.ui.geometry.Rect? = { null }
    internal var revealImpl: suspend (Int) -> Unit = {}
    internal var scrollByImpl: suspend (Float) -> Unit = {}
    /** The cell of item [index] if it is on screen in the All view, else null. */
    fun cellRect(index: Int): androidx.compose.ui.geometry.Rect? = rectOf(index)
    /** Scrolls the All view so that item [index] is visible (and switches to All). */
    suspend fun reveal(index: Int) = revealImpl(index)
    /** Scrolls the All view by [pixels] (positive = towards newer photos). Used by the scrolling test in Settings. */
    suspend fun scrollBy(pixels: Float) = scrollByImpl(pixels)
}

/**
 * The Library: header, the three views (Years, Months, All) and the bottom bar. Select mode keeps a set of item positions and offers Share only.
 * [onShare] gets the Nextcloud file ids of the selected items; [onOpen] is the viewer (the next build).
 */
@Composable
fun LibraryScreen(
    lib: LibraryIndex,
    previews: Previews,
    loader: ImageLoader,
    onShare: (LongArray) -> Unit,
    onOpen: (Int) -> Unit,
    onTitleLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    /** Tests only: open the grid at this item instead of the newest. */
    startAtItem: Int? = null,
    controller: LibraryController = remember { LibraryController() },
    /** Nextcloud file ids that are favourites (a small heart on the tile). */
    favourites: Set<Long> = emptySet(),
    /** Shown under the header: for example the offline notice. */
    banner: (@Composable () -> Unit)? = null,
    /** What the bottom bar does when the round Library button is pressed (the app shows the tab bar). Default: scroll to the newest. */
    onLibraryButton: (() -> Unit)? = null,
    /** The app shows its own tab bar in place of the Library bar (after the round Library button is pressed). */
    hideBottomBar: Boolean = false,
    source: Source = Source.All,
    onSource: (Source) -> Unit = {},
    showSource: Boolean = false,
    /** Tells the app when select mode starts or ends, so it can hold back library changes while a selection is open. */
    onSelectingChange: (Boolean) -> Unit = {},
    /** Asks the app to delete these files (the app shows the confirmation). */
    onDelete: (LongArray) -> Unit = {},
    onHide: ((LongArray) -> Unit)? = null,
    /** Sets (true) or clears (false) the favourite flag of the selected photos. */
    onFavourite: ((LongArray, Boolean) -> Unit)? = null,
    onAddToAlbum: ((LongArray) -> Unit)? = null,
    /** Changes when a delete has finished: select mode ends. */
    clearSelectionTick: Int = 0,
    /** The round Search button of the bottom bar; null keeps it inactive. */
    onSearch: (() -> Unit)? = null,
) {
    val locale: Locale = LocalConfiguration.current.locales[0]
    var mode by rememberSaveable { mutableStateOf(LibraryMode.All) }
    var columns by rememberSaveable { mutableIntStateOf(5) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    // the selection is kept by file id, not by position: the library can change under it (a new screenshot arrives)
    var selected by remember { mutableStateOf(setOf<Long>()) }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val headerH = topInset + 84.dp
    val barClearance = bottomInset + 22.dp + 48.dp + 24.dp

    // the grid opens at the newest item (the last one); the padded first row keeps the last row full
    val pad0 = (columns - lib.size % columns) % columns
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = if (startAtItem != null) startAtItem + pad0 else (lib.size + pad0 - 1).coerceAtLeast(0))
    val yearsState = rememberLazyListState(initialFirstVisibleItemIndex = (lib.years.size + (if (lib.undatedCount > 0) 1 else 0) - 1).coerceAtLeast(0))
    val monthsState = rememberLazyListState(initialFirstVisibleItemIndex = (lib.months.size + (if (lib.undatedCount > 0) 1 else 0) - 1).coerceAtLeast(0))

    // when the library is rebuilt (a new screenshot, a new index) keep the reader at the same photo, or at the end if they were at the end
    val anchor = remember { longArrayOf(Long.MIN_VALUE, 1L) }       // [first visible id, 1 = was at the end]
    val anchorId = remember(lib) { anchor[0] }
    val anchorAtEnd = remember(lib) { anchor[1] == 1L }
    var jumpTo by remember { mutableStateOf<Int?>(null) }
    var monthJump by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(lib) {
        if (anchorId != Long.MIN_VALUE && lib.size > 0) {
            val padNow = (columns - lib.size % columns) % columns
            if (anchorAtEnd) gridState.scrollToItem(lib.size + padNow - 1)
            else lib.indexOfId(anchorId)?.let { gridState.scrollToItem(it + padNow) }
        }
    }
    androidx.compose.runtime.SideEffect {
        val padNow = (columns - lib.size % columns) % columns
        val first = (gridState.firstVisibleItemIndex - padNow).coerceIn(0, (lib.size - 1).coerceAtLeast(0))
        if (lib.size > 0) anchor[0] = lib.ids[first]
        val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        anchor[1] = if (last >= lib.size + padNow - 1) 1L else 0L
    }
    val unknown = stringResource(R.string.unknown_date)

    // jumping from Years or Months into All
    LaunchedEffect(jumpTo, mode, columns) {
        val target = jumpTo ?: return@LaunchedEffect
        if (mode == LibraryMode.All) {
            val padNow = (columns - lib.size % columns) % columns
            gridState.scrollToItem(target + padNow)
            jumpTo = null
        }
    }

    LaunchedEffect(monthJump, mode) {
        val target = monthJump ?: return@LaunchedEffect
        if (mode == LibraryMode.Months) { monthsState.scrollToItem(target); monthJump = null }
    }

    androidx.compose.runtime.SideEffect {
        controller.rectOf = { i ->
            if (mode != LibraryMode.All) null else {
                val padNow = (columns - lib.size % columns) % columns
                val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i + padNow }
                info?.let { androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset(it.offset.x.toFloat(), it.offset.y.toFloat() + gridState.layoutInfo.beforeContentPadding), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }
            }
        }
        controller.scrollByImpl = { px -> gridState.scrollBy(px) }
        controller.revealImpl = { i ->
            mode = LibraryMode.All
            val padNow = (columns - lib.size % columns) % columns
            val visible = gridState.layoutInfo.visibleItemsInfo
            val inView = visible.isNotEmpty() && (i + padNow) in (visible.first().index + columns)..(visible.last().index - columns)
            if (!inView) gridState.scrollToItem((i + padNow - columns).coerceAtLeast(0))
        }
    }

    LaunchedEffect(selecting) { onSelectingChange(selecting) }
    LaunchedEffect(clearSelectionTick) { if (clearSelectionTick > 0) { selecting = false; selected = emptySet() } }

    BackHandler(enabled = selecting) { selecting = false; selected = emptySet() }

    val subtitle by remember(lib, mode, columns) {
        derivedStateOf {
            when (mode) {
                LibraryMode.All -> {
                    val padNow = (columns - lib.size % columns) % columns
                    val first = gridState.firstVisibleItemIndex - padNow
                    val last = (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: gridState.firstVisibleItemIndex) - padNow
                    if (lib.size == 0) "" else if (last >= lib.size - 1) "" else lib.rangeLabel(first.coerceAtLeast(0), last, locale, unknown)
                }
                else -> ""
            }
        }
    }
    val atEnd by remember(lib, columns) {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= lib.size + (columns - lib.size % columns) % columns - 1
        }
    }
    val headerSubtitle = when {
        mode != LibraryMode.All -> stringResource(R.string.items_n, lib.size)
        atEnd -> stringResource(R.string.items_n, lib.size)
        else -> subtitle
    }

    Box(modifier.fillMaxSize()) {
        when (mode) {
            LibraryMode.All -> {
                if (lib.size == 0) {
                    Text(stringResource(R.string.empty_library), Modifier.align(Alignment.Center), color = PhotoTheme.colors.textSecondary)
                } else {
                    AllGrid(
                        lib, previews, loader, columns,
                        onColumns = { newCols ->
                            val firstItem = (gridState.firstVisibleItemIndex - (columns - lib.size % columns) % columns).coerceAtLeast(0)
                            columns = newCols
                            jumpTo = firstItem
                        },
                        selecting = selecting, selected = selected,
                        onToggle = { i -> val id = lib.ids[i]; selected = if (id in selected) selected - id else selected + id },
                        onStartSelect = { i -> selecting = true; selected = setOf(lib.ids[i]) },
                        onRangeSelect = { from, to, select ->
                            val ids = (minOf(from, to)..maxOf(from, to)).map { lib.ids[it] }
                            selected = if (select) selected + ids else selected - ids.toSet()
                        },
                        onOpen = onOpen, favourites = favourites, topPadding = headerH, bottomPadding = barClearance, state = gridState,
                    )
                    FastScroller(lib, gridState, columns, headerH, barClearance, locale, Modifier.align(Alignment.CenterEnd))
                }
            }
            LibraryMode.Years -> YearsView(
                lib, previews, loader, yearsState, headerH, barClearance,
                onYear = { first ->
                    // Months list: the undated card (if any), then one block per month; open at the first month of that year
                    monthJump = lib.months.indexOfFirst { it.from >= first }.coerceAtLeast(0) + (if (lib.undatedCount > 0) 1 else 0)
                    mode = LibraryMode.Months
                },
                onUnknown = { jumpTo = 0; mode = LibraryMode.All },
            )
            LibraryMode.Months -> MonthsView(
                lib, previews, loader, monthsState, locale, headerH, barClearance,
                onDay = { first -> jumpTo = first; mode = LibraryMode.All },
                onUnknown = { jumpTo = 0; mode = LibraryMode.All },
            )
        }

        LibraryHeader(
            title = stringResource(R.string.library), subtitle = headerSubtitle, selecting = selecting,
            onSelect = { selecting = true; selected = emptySet() },
            onDone = { selecting = false; selected = emptySet() },
            onTitleLongPress = onTitleLongPress, source = source, onSource = onSource, showSource = showSource,
            modifier = Modifier.align(Alignment.TopStart).padding(top = topInset),
        )

        if (banner != null) Box(Modifier.align(Alignment.TopCenter).padding(top = headerH)) { banner() }

        // a soft scrim under the bottom bar: its buttons stay readable over bright photos
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(barClearance + 16.dp).background(Brush.verticalGradient(listOf(Color.Transparent, PhotoTheme.colors.background.copy(alpha = 0.94f)))))

        val barModifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 22.dp)
        if (hideBottomBar && !selecting) {
            // the tab bar is drawn by the app
        } else if (selecting) {
            SelectBottomBar(selected.size, onShare = { onShare(selected.toLongArray()) }, onDelete = { onDelete(selected.toLongArray()) }, modifier = barModifier, onHide = onHide?.let { h -> { h(selected.toLongArray()) } },
                onFavourite = onFavourite?.let { f -> { f(selected.toLongArray(), FavouritesOverlay.wantedState(selected.toLongArray(), favourites)) } },
                favouriteClears = !FavouritesOverlay.wantedState(selected.toLongArray(), favourites) && selected.any { it > 0 },
                onAddToAlbum = onAddToAlbum?.let { a -> { a(selected.toLongArray()) } },
            )
        } else {
            LibraryBottomBar(
                mode, onMode = { mode = it }, modifier = barModifier,
                onLibraryButton = onLibraryButton ?: { if (mode == LibraryMode.All) jumpTo = (lib.size - 1).coerceAtLeast(0) else mode = LibraryMode.All },
                onSearch = onSearch,
            )
        }
    }
}
