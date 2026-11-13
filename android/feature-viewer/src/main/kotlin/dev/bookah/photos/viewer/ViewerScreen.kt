package dev.bookah.photos.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.bookah.photos.data.AssetDetails
import dev.bookah.photos.data.Formats
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.library.LibraryController
import dev.bookah.photos.library.PhotoImage
import dev.bookah.photos.library.Previews
import dev.bookah.photos.library.RoundButton
import kotlinx.coroutines.launch
import okhttp3.Call
import kotlin.math.abs

/** What the viewer needs from the app: pictures, the network client for video, where originals live, and details from the snapshot. */
class ViewerEnv(
    val previews: Previews,
    val loader: ImageLoader,
    val okHttp: Call.Factory,
    val originalUrl: (path: String) -> String,
    val details: suspend (fileId: Long) -> AssetDetails?,
    /** Hands a photo that exists only on this phone to the share sheet, so the Nextcloud app can back it up. */
    val onBackUp: (fileId: Long) -> Unit = {},
    /** Asks the app to delete this photo (the app shows the confirmation). */
    val onDelete: (fileId: Long) -> Unit = {},
    /** Hides this photo (a lock button), or in the Hidden photos viewer ([hideIsUnhide]) shows it again. Null: no such button. */
    val onHide: ((fileId: Long) -> Unit)? = null,
    val hideIsUnhide: Boolean = false,
    /** Opens the "add to album" choice for this photo; null: no such button. */
    val onAddToAlbum: ((fileId: Long) -> Unit)? = null,
    /** In an album: takes this photo out of the album (the photo itself stays); null: no such button. */
    val onRemoveFromAlbum: ((fileId: Long) -> Unit)? = null,
    /** Sets (true) or clears (false) the favourite flag of a photo; null: the heart is only a display. */
    val onFavourite: ((fileId: Long, on: Boolean) -> Unit)? = null,
    /** False in the Hidden photos viewer: sharing and deleting need the normal library, which does not hold these photos. */
    val allowShare: Boolean = true,
    val allowDelete: Boolean = true,
)

/**
 * The full-screen viewer, drawn over the Library. It grows out of the tapped cell and shrinks back into it (swipe down or Back);
 * left and right swipes move through the whole library; swipe up or the info button opens the details; a tap hides the controls.
 */
@Composable
fun ViewerOverlay(
    lib: LibraryIndex,
    startIndex: Int,
    env: ViewerEnv,
    controller: LibraryController,
    onShare: (Long) -> Unit,
    onClose: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Library positions to page through (an album, favourites...). Null means the whole library. */
    order: IntArray? = null,
    /** File ids that are favourites; shown as a filled heart (state only until favourites can be changed). */
    favourites: Set<Long> = emptySet(),
) {
    val scope = rememberCoroutineScope()
    val locale = LocalConfiguration.current.locales[0]
    fun ix(page: Int): Int = order?.get(page) ?: page
    val pager = rememberPagerState(initialPage = order?.indexOf(startIndex)?.coerceAtLeast(0) ?: startIndex) { order?.size ?: lib.size }
    val currentPage = pager.currentPage
    val current = ix(currentPage)
    var zoomed by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var showDetails by remember { mutableStateOf(false) }
    // How "open" the viewer is: 0 is shrunk into the grid cell (or invisible), 1 is full screen. Opening, closing and the swipe-down
    // all move this one number, so a release continues from wherever the finger left it (no snapping).
    val openness = remember { Animatable(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var anchor by remember { mutableStateOf<Rect?>(controller.cellRect(startIndex)) }
    var closing by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(Unit) {
        openness.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f))
        opened = true
    }

    /** Shrinks into the cell of the photo that is showing now and calls [onClose] when it has arrived. */
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            // the grid may have to scroll so that this photo has a cell to shrink into; the black backdrop hides that scroll
            controller.reveal(current)
            withFrameNanos { }
            anchor = controller.cellRect(current)
            val startDrag = dragY
            launch { animate(startDrag, 0f, animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)) { v, _ -> dragY = v } }
            openness.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f))
            onClose(current)
        }
    }
    BackHandler(enabled = !showDetails) { close() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        // swipe-down: the picture follows the finger and shrinks a little; swipe-up (details) only follows with resistance
        val downFraction = (dragY.coerceAtLeast(0f) / (screenH * 0.6f)).coerceIn(0f, 1f)
        val p = (openness.value * (1f - downFraction * 0.5f)).coerceIn(0f, 1f)
        // black backdrop
        Box(Modifier.fillMaxSize().background(Color.Black).alpha(p))

        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val a = anchor
                    val ty = if (dragY < 0f) dragY * 0.35f else dragY
                    if (a != null) {
                        val s0 = (a.width / screenW).coerceIn(0.05f, 1f)
                        val s = s0 + (1f - s0) * p
                        scaleX = s; scaleY = s
                        translationX = (a.center.x - screenW / 2f) * (1f - p)
                        translationY = (a.center.y - screenH / 2f) * (1f - p) + ty
                        alpha = (p * 2f).coerceIn(0f, 1f)
                    } else {
                        val s = 0.85f + 0.15f * p
                        scaleX = s; scaleY = s
                        translationY = ty
                        alpha = p
                    }
                }
                .pointerInput(zoomed, closing) {
                    if (zoomed || closing) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, over -> change.consume(); dragY += over }
                        if (slop != null) {
                            val tracker = VelocityTracker()
                            tracker.addPointerInputChange(down)
                            verticalDrag(slop.id) { change ->
                                tracker.addPointerInputChange(change)
                                dragY += change.positionChange().y
                                change.consume()
                            }
                            val vy = tracker.calculateVelocity().y
                            when {
                                dragY > screenH * 0.1f || vy > 1_400f -> close()
                                dragY < -screenH * 0.08f || vy < -1_200f -> { showDetails = true; scope.launch { animate(dragY, 0f) { v, _ -> dragY = v } } }
                                else -> scope.launch { animate(dragY, 0f, animationSpec = spring(stiffness = 500f)) { v, _ -> dragY = v } }
                            }
                        }
                    }
                },
        ) {
            HorizontalPager(
                state = pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = !zoomed, beyondViewportPageCount = 1,
                key = { lib.ids[ix(it)] },
            ) { page ->
                val i = ix(page)
                val active = page == currentPage
                if (lib.isVideo(i)) {
                    VideoPage(i, lib, env, active, onTap = { controls = !controls })
                } else {
                    PhotoPage(i, lib, env, active, onZoomed = { zoomed = it }, onTap = { controls = !controls })
                }
            }
            LaunchedEffect(currentPage) { zoomed = false }

            val barAlpha by animateFloatAsState(if (controls && !zoomed && opened && !closing && dragY == 0f) 1f else 0f, tween(180), label = "bars")
            // top: back, the date pill
            Row(
                Modifier.fillMaxWidth().padding(top = topInset + 8.dp, start = 16.dp, end = 16.dp).alpha(barAlpha),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RoundButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), { close() }, dark = true)
                DatePill(lib, current, env, locale)
                Box(Modifier.size(44.dp))
            }
            // bottom: filmstrip and the action bar
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 16.dp).alpha(barAlpha), horizontalAlignment = Alignment.CenterHorizontally) {
                if (opened) Filmstrip(lib, order, currentPage, env) { target -> scope.launch { pager.scrollToPage(target) } } else Box(Modifier.height(52.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (env.allowShare) RoundButton(Icons.Filled.Share, stringResource(R.string.share), { onShare(lib.ids[current]) }, size = 48, dark = true) else Spacer(Modifier.size(48.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Row(
                            Modifier.height(48.dp).clip(CircleShape).background(Color(0xB31C1C1F)).border(1.dp, Color(0x1FFFFFFF), CircleShape).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val fav = lib.ids[current] in favourites
                            val toggle = env.onFavourite
                            val canToggle = toggle != null && lib.ids[current] > 0                       // a photo only on this phone is not in Nextcloud
                            PillIcon(
                                if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, stringResource(if (fav) R.string.unfavourite else R.string.favourite), "viewer-favourite",
                                alpha = if (fav) 1f else 0.6f, onClick = if (canToggle) ({ toggle!!(lib.ids[current], !fav) }) else null,
                            )
                            env.onAddToAlbum?.let { add -> if (lib.ids[current] > 0) PillIcon(Icons.Filled.Add, stringResource(R.string.add_to_album), "viewer-add-album", onClick = { add(lib.ids[current]) }) }
                            env.onRemoveFromAlbum?.let { remove -> PillIcon(Icons.Filled.Clear, stringResource(R.string.remove_from_album), "viewer-remove-album", onClick = { remove(lib.ids[current]) }) }
                            env.onHide?.let { hide ->
                                if (env.hideIsUnhide) Box(
                                    Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button) { hide(lib.ids[current]) }.padding(horizontal = 12.dp).testTag("viewer-unhide"),
                                    contentAlignment = Alignment.Center,
                                ) { Text(stringResource(R.string.unhide), color = Color.White, style = MaterialTheme.typography.titleMedium) }
                                else PillIcon(Icons.Filled.Lock, stringResource(R.string.hide), "viewer-hide", onClick = { hide(lib.ids[current]) })
                            }
                            PillIcon(Icons.Filled.Info, stringResource(R.string.info), "viewer-info", onClick = { showDetails = true })
                        }
                    }
                    if (env.allowDelete) RoundButton(Icons.Filled.Delete, stringResource(R.string.delete), { env.onDelete(lib.ids[current]) }, size = 48, dark = true) else Spacer(Modifier.size(48.dp))
                }
            }
        }

        if (showDetails) DetailsSheet(lib.ids[current], env, locale, onDismiss = { showDetails = false })
    }
}

/** A button of the pill at the bottom: 48 dp, spoken name on the button itself (an icon alone has none), dimmed when it does nothing. */
@Composable
private fun PillIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, tag: String, alpha: Float = 1f, onClick: (() -> Unit)?) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(22.dp).alpha(alpha), tint = Color.White) }
}

/** The pill at the top: the date on one line and the time under it, in the photo's own clock. */
@Composable
private fun DatePill(lib: LibraryIndex, index: Int, env: ViewerEnv, locale: java.util.Locale) {
    val id = lib.ids[index]
    var details by remember(id) { mutableStateOf<AssetDetails?>(null) }
    LaunchedEffect(id) { details = env.details(id) }
    val d = details
    val date = d?.let { Formats.dateLong(it.takenUtc, it.offsetMin, locale) }
    val time = d?.let { Formats.time(it.takenUtc, it.offsetMin, locale) }
    Column(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xB31C1C1F)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp).semantics { contentDescription = listOfNotNull(date, time).joinToString(" ") },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (d == null) {
            Text("…", color = Color.White, style = MaterialTheme.typography.titleMedium)
        } else {
            Text(date ?: stringResource(R.string.unknown_date), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (time != null) Text(time, color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** A thin strip of neighbours for quick scrubbing; the current one is larger. Pages are positions in [order] (or in the whole library). */
@Composable
private fun Filmstrip(lib: LibraryIndex, order: IntArray?, currentPage: Int, env: ViewerEnv, onPick: (Int) -> Unit) {
    val state = rememberLazyListState()
    val labeller = dev.bookah.photos.library.rememberPhotoLabeller()
    LaunchedEffect(currentPage) { state.scrollToItem((currentPage - 4).coerceAtLeast(0)) }
    LazyRow(state = state, modifier = Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        items(order?.size ?: lib.size, key = { lib.ids[order?.get(it) ?: it] }) { page ->
            val i = order?.get(page) ?: page
            val selected = page == currentPage
            // the picture keeps its size; the part that can be touched is at least 48 dp wide and has a spoken name (a picture alone has none)
            val label = labeller(lib, i)
            Box(
                Modifier.height(52.dp).width(if (selected) 52.dp else 48.dp).clickable(role = Role.Button) { onPick(page) }.semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                PhotoImage(i, lib, 128, env.previews, env.loader, Modifier.height(if (selected) 52.dp else 40.dp).width(if (selected) 52.dp else 40.dp).clip(RoundedCornerShape(4.dp)))
            }
        }
    }
}
