package dev.bookah.photos.collections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.Memory
import dev.bookah.photos.design.PhotoTheme
import dev.bookah.photos.library.PhotoImage
import dev.bookah.photos.library.Previews
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Seconds each picture stays on screen in the playback. */
const val MEMORY_SECONDS = 4

/** A memory in a row: a tall card with the cover and the title at the bottom (spec: large cards, radius 24). */
@Composable
fun MemoryCard(lib: LibraryIndex, memory: Memory, greek: Boolean, previews: Previews, loader: ImageLoader, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cover = lib.indexOfId(memory.coverAsset) ?: memory.assetIds.asList().firstNotNullOfOrNull { lib.indexOfId(it) }
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(PhotoTheme.colors.surface).clickable(role = Role.Button, onClick = onClick)) {
        cover?.let { PhotoImage(it, lib, 512, previews, loader, Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xB3000000))))
        Text(
            memory.title(greek), Modifier.align(Alignment.BottomStart).padding(14.dp), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** All memories in a two-column grid. */
@Composable
fun MemoriesPage(lib: LibraryIndex, memories: List<Memory>, greek: Boolean, previews: Previews, loader: ImageLoader, onMemory: (Memory) -> Unit, onBack: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier, title: String? = null) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier.fillMaxSize().background(PhotoTheme.colors.background)) {
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize().testTag("memories-grid"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 72.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(memories, key = { it.key }) { m -> MemoryCard(lib, m, greek, previews, loader, { onMemory(m) }, Modifier.height(210.dp).testTag("memory-${m.key}")) }
        }
        PageHeader(title ?: stringResource(R.string.memories), onBack, Modifier.align(Alignment.TopStart).padding(top = top))
    }
}

/**
 * Full-screen playback of one memory: the pictures one after the other (every [MEMORY_SECONDS] seconds), progress bars at the top, tap the right or left
 * side to go forward or back, hold to pause. The menu opens all the photos or says "don't show this memory" (it asks first; Undo is offered right after).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MemoryPlayer(
    lib: LibraryIndex, memory: Memory, greek: Boolean, previews: Previews, loader: ImageLoader,
    onClose: () -> Unit, onSeeAll: () -> Unit, onUnwanted: () -> Unit, modifier: Modifier = Modifier,
) {
    val positions = remember(lib, memory) { memory.assetIds.asList().mapNotNull { lib.indexOfId(it) }.toIntArray() }
    if (positions.isEmpty()) { LaunchedEffect(memory.key) { onClose() }; return }
    val pager = rememberPagerState { positions.size }
    var paused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // advance by itself; the clock restarts for every page, and stops while the finger is down or a dialog is open
    LaunchedEffect(pager.currentPage, paused, asking, menu) {
        if (!paused && !asking && !menu && pager.currentPage < positions.lastIndex) {
            delay(MEMORY_SECONDS * 1000L)
            pager.animateScrollToPage(pager.currentPage + 1)
        }
    }
    Box(modifier.fillMaxSize().background(Color.Black).testTag("memory-player").semantics { stateDescription = "${pager.currentPage + 1}/${positions.size}" }) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val i = positions[page]
            Box(Modifier.fillMaxSize()) {
                PhotoImage(i, lib, 256, previews, loader, Modifier.fillMaxSize())             // the placeholder and a small picture, at once
                if (lib.localUri[i] == null && !lib.isRaw[i]) {
                    val id = lib.ids[i]
                    val request = remember(id) { ImageRequest.Builder(context).data(previews.fitUrl(id, 1024)).crossfade(200).build() }
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.0f))) {
                        AsyncImage(request, null, loader, Modifier.fillMaxSize().background(Color.Black), contentScale = ContentScale.Fit)
                    }
                }
            }
        }
        // taps on the two halves and the hold to pause sit over the pictures
        Row(Modifier.fillMaxSize().padding(top = top + 64.dp)) {
            val half = { forward: Boolean ->
                Modifier.weight(1f).fillMaxHeight().pointerInput(forward) {
                    detectTapGestures(
                        onPress = { paused = true; tryAwaitRelease(); paused = false },
                        onTap = { scope.launch { pager.scrollToPage((pager.currentPage + if (forward) 1 else -1).coerceIn(0, positions.lastIndex)) } },
                    )
                }
            }
            Box(half(false).testTag("memory-back"))
            Box(half(true).testTag("memory-next"))
        }
        // progress bars and the buttons
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))).padding(top = top + 8.dp, start = 12.dp, end = 12.dp, bottom = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth().height(3.dp)) {
                for (k in positions.indices) Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(if (k <= pager.currentPage) Color.White else Color(0x55FFFFFF)))
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(memory.title(greek), Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Box {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x55000000)).clickable(role = Role.Button) { menu = true }.testTag("memory-menu"), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.menu), tint = Color.White)
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.memory_see_all)) }, onClick = { menu = false; onSeeAll() }, modifier = Modifier.testTag("memory-see-all"))
                        DropdownMenuItem({ Text(stringResource(R.string.memory_unwanted)) }, onClick = { menu = false; asking = true }, modifier = Modifier.testTag("memory-unwanted"))
                    }
                }
                Box(Modifier.padding(start = 8.dp).size(40.dp).clip(CircleShape).background(Color(0x55000000)).clickable(role = Role.Button, onClick = onClose).testTag("memory-close"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, stringResource(R.string.back), tint = Color.White)
                }
            }
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.memory_unwanted_title)) },
            text = { Text(stringResource(R.string.memory_unwanted_text)) },
            confirmButton = { TextButton(onClick = { asking = false; onUnwanted() }, modifier = Modifier.testTag("memory-unwanted-yes")) { Text(stringResource(R.string.memory_unwanted_yes)) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

