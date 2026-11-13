package dev.bookah.photos.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import dev.bookah.photos.data.FavouritesOverlay
import dev.bookah.photos.library.PillButton
import dev.bookah.photos.library.SelectBottomBar
import dev.bookah.photos.library.SelectMark
import dev.bookah.photos.library.SelectionActions
import dev.bookah.photos.library.dragSelect
import androidx.compose.ui.semantics.selected
import coil3.ImageLoader
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.Memory
import dev.bookah.photos.data.PeopleIndex
import dev.bookah.photos.data.Person
import androidx.compose.ui.platform.testTag
import dev.bookah.photos.data.SectionId
import dev.bookah.photos.data.SectionPrefs
import dev.bookah.photos.design.PhotoTheme
import dev.bookah.photos.library.PhotoImage
import dev.bookah.photos.library.Previews
import dev.bookah.photos.library.RoundButton

/** The entry to the hidden photos: a lock, no count and no picture. Opening it asks for the phone's unlock. */
@Composable
fun HiddenTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(PhotoTheme.colors.surface).clickable(role = Role.Button, onClick = onClick).testTag("hidden-tile"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, null, Modifier.size(32.dp), tint = PhotoTheme.colors.textSecondary)
        Text(stringResource(R.string.hidden_photos), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium, color = PhotoTheme.colors.textPrimary)
    }
}

/** The Duplicates tile: photos that are the same picture saved more than once. */
@Composable
fun DuplicatesTile(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(PhotoTheme.colors.surface).clickable(role = Role.Button, onClick = onClick).testTag("duplicates-tile"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.duplicates), style = MaterialTheme.typography.titleMedium, color = PhotoTheme.colors.textPrimary)
        Text(stringResource(R.string.duplicates_count, count), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, color = PhotoTheme.colors.textSecondary)
    }
}

/** The first tile of the Places row: opens the map. */
@Composable
fun MapTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(PhotoTheme.colors.surface).clickable(role = Role.Button, onClick = onClick).testTag("map-tile"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Place, null, Modifier.size(32.dp), tint = PhotoTheme.colors.accent)
        Text(stringResource(R.string.map), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium, color = PhotoTheme.colors.textPrimary)
    }
}

/** The People row shows the first people; the rest are on the People page. */
const val PEOPLE_IN_ROW = 20

@Composable
fun sectionTitle(section: SectionId): String = stringResource(
    when (section) {
        SectionId.Pinned -> R.string.pinned
        SectionId.People -> R.string.people
        SectionId.Memories -> R.string.memories
        SectionId.Places -> R.string.places
        SectionId.Utilities -> R.string.utilities
        SectionId.Trips -> R.string.trips
        SectionId.Albums -> R.string.albums
        SectionId.RecentDays -> R.string.recent_days
        SectionId.MediaTypes -> R.string.media_types
        SectionId.OnThisPhone -> R.string.on_this_phone
    },
)

/** A square card of a Collections row: the cover with the title over a gradient at the bottom left (spec 3.7: 105 dp, radius 20). */
@Composable
fun CardView(lib: LibraryIndex, card: CollectionCard, previews: Previews, loader: ImageLoader, onClick: () -> Unit, modifier: Modifier = Modifier, showCount: Boolean = false) {
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(PhotoTheme.colors.surface).clickable(role = Role.Button, onClick = onClick)) {
        card.coverIndex?.let { PhotoImage(it, lib, 256, previews, loader, Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xA6000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(card.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (showCount) Text(stringResource(R.string.items_n, card.count), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * The Collections page: a title, the account and menu buttons, and the sections in the reader's order, each a row of cards with a
 * title, a "see all" chevron where there is a longer list, and a collapse button. Sections can be reordered, hidden and collapsed.
 */
@Composable
fun CollectionsScreen(
    lib: LibraryIndex,
    model: CollectionsModel,
    prefs: SectionPrefs,
    onPrefs: (SectionPrefs) -> Unit,
    previews: Previews,
    loader: ImageLoader,
    onCard: (CollectionCard) -> Unit,
    onSeeAll: (SectionId) -> Unit,
    onSettings: () -> Unit,
    onReorder: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    banner: (@Composable () -> Unit)? = null,
    people: PeopleIndex = PeopleIndex.EMPTY,
    onPerson: (Person) -> Unit = {},
    memories: List<Memory> = emptyList(),
    greek: Boolean = false,
    onMemory: (Memory) -> Unit = {},
    onMap: () -> Unit = {},
    onHidden: () -> Unit = {},
    trips: List<Memory> = emptyList(),
    onNewAlbum: () -> Unit = {},
    duplicates: Int = 0,
    onDuplicates: () -> Unit = {},
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var menu by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top + 76.dp, bottom = bottomPadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (banner != null) item("banner") { banner() }
            for (section in prefs.visible()) {
                if (section == SectionId.Utilities) {
                    item(key = section.name) {
                        val collapsed = section in prefs.collapsed
                        Column {
                            SectionHeader(sectionTitle(section), collapsed, onToggle = { onPrefs(prefs.toggleCollapsed(section)) }, onOpen = null)
                            // no count and no picture: what is hidden is not shown here either
                            if (!collapsed) LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                item(key = "hidden") { HiddenTile(onHidden, Modifier.width(105.dp)) }
                                if (duplicates > 0) item(key = "duplicates") { DuplicatesTile(duplicates, onDuplicates, Modifier.width(105.dp)) }
                            }
                        }
                    }
                    continue
                }
                if (section == SectionId.Trips) {
                    if (trips.isEmpty()) continue
                    item(key = section.name) {
                        val collapsed = section in prefs.collapsed
                        Column {
                            SectionHeader(sectionTitle(section), collapsed, onToggle = { onPrefs(prefs.toggleCollapsed(section)) }, onOpen = { onSeeAll(section) })
                            if (!collapsed) {
                                LazyRow(Modifier.testTag("trips-row"), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(trips, key = { it.key }) { m -> MemoryCard(lib, m, greek, previews, loader, { onMemory(m) }, Modifier.width(150.dp).height(200.dp).testTag("row-trip-${m.key}")) }
                                }
                            }
                        }
                    }
                    continue
                }
                if (section == SectionId.Memories) {
                    if (memories.isEmpty()) continue
                    item(key = section.name) {
                        val collapsed = section in prefs.collapsed
                        Column {
                            SectionHeader(sectionTitle(section), collapsed, onToggle = { onPrefs(prefs.toggleCollapsed(section)) }, onOpen = { onSeeAll(section) })
                            if (!collapsed) {
                                LazyRow(Modifier.testTag("memories-row"), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(memories, key = { it.key }) { m -> MemoryCard(lib, m, greek, previews, loader, { onMemory(m) }, Modifier.width(150.dp).height(200.dp).testTag("row-memory-${m.key}")) }
                                }
                            }
                        }
                    }
                    continue
                }
                if (section == SectionId.People) {
                    if (people.isEmpty) continue
                    item(key = section.name) {
                        val collapsed = section in prefs.collapsed
                        Column {
                            SectionHeader(sectionTitle(section), collapsed, onToggle = { onPrefs(prefs.toggleCollapsed(section)) }, onOpen = { onSeeAll(section) })
                            if (!collapsed) {
                                LazyRow(Modifier.testTag("people-row"), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    items(people.people.take(PEOPLE_IN_ROW), key = { it.id }) { p -> PersonItem(p, previews, loader, { onPerson(p) }, Modifier.width(76.dp).testTag("row-person-${p.id}")) }
                                }
                            }
                        }
                    }
                    continue
                }
                val cards = model.of(section)
                if (cards.isEmpty() && section != SectionId.Albums) continue
                item(key = section.name) {
                    val collapsed = section in prefs.collapsed
                    Column {
                        SectionHeader(
                            sectionTitle(section), collapsed,
                            onToggle = { onPrefs(prefs.toggleCollapsed(section)) },
                            onOpen = if (section == SectionId.Albums || section == SectionId.RecentDays || section == SectionId.MediaTypes || section == SectionId.OnThisPhone || section == SectionId.Places) ({ onSeeAll(section) }) else null,
                        )
                        if (!collapsed) {
                            if (cards.isEmpty()) {
                                if (section == SectionId.Albums) LazyRow(contentPadding = PaddingValues(horizontal = 20.dp)) { item(key = "new") { NewAlbumTile(onNewAlbum, Modifier.width(105.dp)) } }
                                Text(stringResource(R.string.albums_empty), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = PhotoTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                            } else {
                                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (section == SectionId.Places) item(key = "map") { MapTile(onMap, Modifier.width(105.dp)) }
                                    if (section == SectionId.Albums) item(key = "new") { NewAlbumTile(onNewAlbum, Modifier.width(105.dp)) }
                                    items(cards, key = { it.key }) { card -> CardView(lib, card, previews, loader, { onCard(card) }, Modifier.width(105.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        // header over the list
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(PhotoTheme.colors.background.copy(alpha = 0.96f), PhotoTheme.colors.background.copy(alpha = 0.85f), Color.Transparent)))
                .padding(top = top + 8.dp, start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.collections), Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, color = PhotoTheme.colors.textPrimary)
            RoundButton(Icons.Filled.AccountCircle, stringResource(R.string.settings), onSettings)
            Box(Modifier.padding(start = 10.dp)) {
                RoundButton(Icons.Filled.MoreVert, stringResource(R.string.menu), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.show_all)) }, onClick = { menu = false; onPrefs(prefs.showAll()) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.collapse_all)) }, onClick = { menu = false; onPrefs(prefs.collapseAll()) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.reorder)) }, onClick = { menu = false; onReorder() })
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, collapsed: Boolean, onToggle: () -> Unit, onOpen: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = PhotoTheme.colors.textPrimary)
            if (onOpen != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.see_all), tint = PhotoTheme.colors.textSecondary)
        }
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(PhotoTheme.colors.surface).clickable(role = Role.Button, onClick = onToggle)
                .semantics { contentDescription = title },
            contentAlignment = Alignment.Center,
        ) { Icon(if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, stringResource(if (collapsed) R.string.expand_section else R.string.collapse_section), Modifier.size(20.dp), tint = PhotoTheme.colors.accent) }
    }
}

/** Reorder: each section has a show/hide switch and up and down buttons. Chosen over drag handles because it is exact and works with TalkBack. */
@Composable
fun ReorderScreen(prefs: SectionPrefs, onPrefs: (SectionPrefs) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier.fillMaxSize().background(PhotoTheme.colors.background).padding(top = top + 8.dp, bottom = bottom + 16.dp, start = 20.dp, end = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reorder), Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, color = PhotoTheme.colors.textPrimary)
            Box(Modifier.height(44.dp).clip(CircleShape).background(PhotoTheme.colors.surface).clickable(role = Role.Button, onClick = onDone).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.done), style = MaterialTheme.typography.titleMedium, color = PhotoTheme.colors.accent)
            }
        }
        Spacer(Modifier.height(16.dp))
        prefs.order.forEachIndexed { n, section ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(PhotoTheme.colors.surface).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(sectionTitle(section), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = if (section in prefs.hidden) PhotoTheme.colors.textSecondary else PhotoTheme.colors.textPrimary)
                RoundButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up), { onPrefs(prefs.move(section, -1)) }, size = 40, enabled = n > 0)
                Spacer(Modifier.width(8.dp))
                RoundButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down), { onPrefs(prefs.move(section, 1)) }, size = 40, enabled = n < prefs.order.lastIndex)
                Spacer(Modifier.width(12.dp))
                Switch(checked = section !in prefs.hidden, onCheckedChange = { onPrefs(prefs.toggleHidden(section)) }, modifier = Modifier.semantics { contentDescription = "show " + section.name })
            }
        }
    }
}

/** The "see all" page of a section (albums, recent days, media types): a two-column grid of cards. */
@Composable
fun CardGridPage(title: String, lib: LibraryIndex, cards: List<CollectionCard>, previews: Previews, loader: ImageLoader, onCard: (CollectionCard) -> Unit, onBack: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier.fillMaxSize().background(PhotoTheme.colors.background)) {
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 72.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cards, key = { it.key }) { card -> CardView(lib, card, previews, loader, { onCard(card) }, showCount = true) }
        }
        PageHeader(title, onBack, Modifier.align(Alignment.TopStart).padding(top = top))
    }
}

@Composable
fun PageHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(PhotoTheme.colors.background.copy(alpha = 0.96f), PhotoTheme.colors.background.copy(alpha = 0.8f), Color.Transparent)))
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onBack)
        Text(title, Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.titleLarge, color = PhotoTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

/** A set of photos (an album, favourites, a day...): a square grid, four columns. Tapping opens the viewer through this set only. */
@Composable
fun PhotoSetPage(
    title: String, lib: LibraryIndex, indices: IntArray, previews: Previews, loader: ImageLoader,
    favourites: Set<Long>, onOpen: (index: Int) -> Unit, onBack: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier, actions: (@Composable () -> Unit)? = null,
    /** With this, the page has the Library's selection: a Select button, press and hold (then slide), and the bar of buttons at the bottom. */
    selection: SelectionActions? = null,
    /** When this number goes up the selection ends (after a delete, hide, or adding to an album). */
    clearSelectionTick: Int = 0,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    LaunchedEffect(clearSelectionTick) { if (clearSelectionTick > 0) { selecting = false; selected = emptySet() } }
    BackHandler(enabled = selecting) { selecting = false; selected = emptySet() }
    val gridState = rememberLazyGridState()
    val labeller = dev.bookah.photos.library.rememberPhotoLabeller()
    val libNow = rememberUpdatedState(lib)
    val indicesNow = rememberUpdatedState(indices)
    val selectingNow = rememberUpdatedState(selecting)
    val selectedNow = rememberUpdatedState(selected)
    val countNow = rememberUpdatedState(indices.size)
    val idAtNow = rememberUpdatedState<(Int) -> Long>({ n -> libNow.value.ids[indicesNow.value[n]] })
    val zeroPad = rememberUpdatedState(0)
    val onStartNow = rememberUpdatedState<(Int) -> Unit>({ n -> selecting = true; selected = setOf(libNow.value.ids[indicesNow.value[n]]) })
    val onRangeNow = rememberUpdatedState<(Int, Int, Boolean) -> Unit>({ from, to, select ->
        val ids = (minOf(from, to)..maxOf(from, to)).map { libNow.value.ids[indicesNow.value[it]] }
        selected = if (select) selected + ids else selected - ids.toSet()
    })
    Box(modifier.fillMaxSize().background(PhotoTheme.colors.background)) {
        if (indices.isEmpty()) {
            Text(stringResource(R.string.nothing_here), Modifier.align(Alignment.Center), color = PhotoTheme.colors.textSecondary)
        } else {
            LazyVerticalGrid(
                GridCells.Fixed(4),
                Modifier.fillMaxSize().then(if (selection != null) Modifier.dragSelect(gridState, countNow, idAtNow, zeroPad, selectingNow, selectedNow, onStartNow, onRangeNow) else Modifier).testTag("set-grid"),
                state = gridState,
                contentPadding = PaddingValues(top = top + 72.dp, bottom = bottomPadding + if (selecting) 72.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(indices.size) { n ->
                    val i = indices[n]
                    val id = lib.ids[i]
                    Box(Modifier.aspectRatio(1f).testTag("set-cell-$id").semantics { contentDescription = labeller(lib, i); this.selected = id in selected }.clickable {
                        if (selecting) selected = if (id in selected) selected - id else selected + id else onOpen(i)
                    }) {
                        PhotoImage(i, lib, 256, previews, loader, Modifier.fillMaxSize())
                        if (selecting) SelectMark(id in selected)
                    }
                }
            }
        }
        PageHeader(
            title, if (selecting) ({ selecting = false; selected = emptySet() }) else onBack, Modifier.align(Alignment.TopStart).padding(top = top),
            trailing = {
                if (selection != null && indices.isNotEmpty()) {
                    PillButton(stringResource(if (selecting) R.string.done else R.string.select), { if (selecting) { selecting = false; selected = emptySet() } else { selecting = true; selected = emptySet() } }, Modifier.testTag("set-select"))
                    Spacer(Modifier.width(8.dp))
                }
                if (!selecting) actions?.invoke()
            },
        )
        if (selecting && selection != null) {
            val ids = selected.toLongArray()
            SelectBottomBar(
                selected.size, onShare = selection.onShare?.let { f -> { f(ids) } }, onDelete = selection.onDelete?.let { f -> { f(ids) } }, onUnhide = selection.onUnhide?.let { f -> { f(ids) } }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 22.dp),
                onHide = selection.onHide?.let { h -> { h(ids) } },
                onFavourite = selection.onFavourite?.let { f -> { f(ids, FavouritesOverlay.wantedState(ids, favourites)) } },
                favouriteClears = !FavouritesOverlay.wantedState(ids, favourites) && ids.any { it > 0 },
                onAddToAlbum = selection.onAddToAlbum?.let { a -> { a(ids) } },
            )
        }
    }
}
