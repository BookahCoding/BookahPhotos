package dev.bookah.photos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.bookah.photos.collections.CardGridPage
import dev.bookah.photos.collections.CollectionCard
import dev.bookah.photos.collections.CollectionLabels
import dev.bookah.photos.collections.CollectionsModel
import dev.bookah.photos.collections.CollectionsScreen
import dev.bookah.photos.collections.PhotoSetPage
import dev.bookah.photos.collections.ReorderScreen
import dev.bookah.photos.collections.sectionTitle
import dev.bookah.photos.data.DeletePlan
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.SectionId
import dev.bookah.photos.data.Source
import dev.bookah.photos.data.SyncResult
import dev.bookah.photos.design.PhotoTheme
import dev.bookah.photos.library.LibraryController
import dev.bookah.photos.library.LibraryScreen
import dev.bookah.photos.library.Previews
import dev.bookah.photos.library.SelectionActions
import dev.bookah.photos.library.Tab
import dev.bookah.photos.library.TabBar
import dev.bookah.photos.searchui.SearchPage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bookah.photos.viewer.ViewerEnv
import dev.bookah.photos.viewer.ViewerOverlay

/** Screens pushed on top of the Library or Collections. Photo sets hold file ids, never positions: the library can change under them. */
private sealed interface Page {
    data object Settings : Page
    data object Search : Page
    data object Reorder : Page
    data object People : Page
    data object HiddenPeople : Page
    data object WhoIsThis : Page
    data object Memories : Page
    data object Trips : Page
    data object Hidden : Page
    data object Duplicates : Page
    class Album(val name: String) : Page
    data object Map : Page
    class Memory(val key: String) : Page
    data class Person(val id: Long) : Page
    data class Faces(val id: Long) : Page
    data class SeeAll(val section: SectionId) : Page
    class Photos(val title: String, val ids: LongArray) : Page
}

/** A viewer opened from the Library (through the source filter) or from a set of photos (only that set). Identified by file ids. */
private class ViewerRequest(val startId: Long, val orderIds: LongArray?, val fromLibrary: Boolean, val hidden: Boolean = false, val album: String? = null)

/**
 * The signed-in app: Library and Collections tabs, pushed pages, the viewer over everything, and the notices (offline, phone access).
 *
 * The library can be rebuilt at any moment (a new screenshot, a new index). While the viewer, select mode or a pushed page is open the
 * rebuilt library is held back and applied when the reader is back on the Library or Collections, so nothing under their finger moves.
 */
@Composable
fun SignedInHome(
    state: UiState, latest: LibraryIndex, previews: Previews, loader: ImageLoader, env: ViewerEnv, vm: AppViewModel,
    onTitleLongPress: () -> Unit, onAllowLocal: () -> Unit, onOpenAppSettings: () -> Unit, onOpenUrl: (String) -> Unit = {},
    unlock: Unlocker = Unlocker { _, _, r -> r(UnlockResult.NoLock) },
) {
    val locale = LocalConfiguration.current.locales[0]
    val searchUi by vm.search.ui.collectAsStateWithLifecycle()
    val peopleState by vm.people.state.collectAsStateWithLifecycle()
    val people = peopleState.index
    val greek = locale.language == "el"
    var today by remember { mutableStateOf(java.time.LocalDate.now()) }            // brought up to date every time the app comes to the front
    var hideRequest by remember { mutableStateOf<LongArray?>(null) }
    var hideDoneTick by remember { mutableStateOf(0) }
    var albumTick by remember { mutableStateOf(0) }
    var albumPicker by remember { mutableStateOf<LongArray?>(null) }
    var albumName by remember { mutableStateOf<AlbumNameRequest?>(null) }
    var albumDelete by remember { mutableStateOf<String?>(null) }
    val albumBusy by vm.albums.busy.collectAsStateWithLifecycle()
    val albumMessage by vm.albums.message.collectAsStateWithLifecycle()
    var noLockDialog by remember { mutableStateOf(false) }
    var mapCamera by remember { mutableStateOf<dev.bookah.photos.map.MapCamera?>(null) }      // kept while the reader looks at photos from the map
    var memoryUndo by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(Tab.Library) }
    var showTabs by rememberSaveable { mutableStateOf(false) }
    var source by rememberSaveable { mutableStateOf(Source.All) }
    var stack by remember { mutableStateOf(listOf<Page>()) }
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
    var bench by remember { mutableStateOf<BenchResult?>(null) }
    var benchRequested by remember { mutableStateOf(0) }
    val refreshHz = androidx.compose.ui.platform.LocalContext.current.display?.refreshRate ?: 60f
    var selecting by remember { mutableStateOf(false) }
    val libraryController = remember { LibraryController() }
    val noController = remember { LibraryController() }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barClearance = bottomInset + 22.dp + 60.dp + 24.dp

    // hold back library changes while something refers to positions in the library
    val busy = viewer != null || selecting || stack.isNotEmpty()
    var shown by remember { mutableStateOf(latest) }
    LaunchedEffect(latest, busy) { if (!busy) shown = latest }
    val effective = remember(shown, source) { shown.bySource(source) }

    val labels = CollectionLabels(
        stringResource(dev.bookah.photos.collections.R.string.favourites), stringResource(dev.bookah.photos.collections.R.string.recently_added),
        stringResource(dev.bookah.photos.collections.R.string.videos), stringResource(dev.bookah.photos.collections.R.string.screenshots),
        stringResource(dev.bookah.photos.collections.R.string.panoramas), stringResource(dev.bookah.photos.collections.R.string.raw),
        stringResource(dev.bookah.photos.collections.R.string.not_backed_up),
        stringResource(dev.bookah.photos.collections.R.string.selfies), stringResource(dev.bookah.photos.collections.R.string.motion_photos),
    )
    val pendingFavs by vm.favourites.pending.collectAsStateWithLifecycle()
    val listsNow = remember(state.lists, pendingFavs) { state.lists?.copy(favouriteIds = dev.bookah.photos.data.FavouritesOverlay.effective(state.lists.favouriteIds, pendingFavs).toList()) }
    val model = remember(shown, listsNow, locale, labels.videos, state.places) { CollectionsModel.build(shown, listsNow, labels, locale, state.places) }
    val pendingFavourites by vm.favourites.pending.collectAsStateWithLifecycle()
    val favourites = remember(state.lists, pendingFavourites) { dev.bookah.photos.data.FavouritesOverlay.effective(state.lists?.favouriteIds.orEmpty(), pendingFavourites) }

    LaunchedEffect(tab) { if (tab == Tab.Collections) vm.refreshLists() }
    // the scrolling test: from the newest photo, scroll towards the oldest for six seconds and time every frame
    LaunchedEffect(benchRequested) {
        if (benchRequested == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        libraryController.reveal(effective.size - 1)
        kotlinx.coroutines.delay(300)
        val intervals = ArrayList<Float>(1000)
        var last = 0L
        val end = android.os.SystemClock.elapsedRealtime() + 6_000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            val t = androidx.compose.runtime.withFrameNanos { it }
            if (last != 0L) intervals.add((t - last) / 1_000_000f)
            last = t
            libraryController.scrollBy(-34f)
        }
        if (intervals.size > 10) {
            val sorted = intervals.sorted()
            val limit = 1.5f * 1000f / refreshHz
            bench = BenchResult(intervals.size, refreshHz, intervals.count { it > limit } * 100f / intervals.size, sorted.last(), sorted[sorted.size / 2])
        }
        stack = listOf(Page.Settings)
    }
    LaunchedEffect(state.deleteDoneTick, hideDoneTick) { if (state.deleteDoneTick > 0 || hideDoneTick > 0) viewer = null }
    BackHandler(enabled = viewer == null && stack.isNotEmpty()) { stack = stack.dropLast(1) }
    BackHandler(enabled = viewer == null && stack.isEmpty() && (tab == Tab.Collections || showTabs)) { tab = Tab.Library; showTabs = false }

    // the viewer of the normal library can hide a photo; the viewer of the hidden photos can show it again and can neither share nor delete (they are not in the library)
    val viewerEnv = remember(env) { ViewerEnv(env.previews, env.loader, env.okHttp, env.originalUrl, env.details, env.onBackUp, env.onDelete, onHide = { hideRequest = longArrayOf(it) }, onFavourite = { id, on -> vm.favourites.set(longArrayOf(id), on) }, onAddToAlbum = { albumPicker = longArrayOf(it) }) }
    val albumEnvs = remember(env) { HashMap<String, ViewerEnv>() }
    fun albumEnv(name: String) = albumEnvs.getOrPut(name) {
        ViewerEnv(
            env.previews, env.loader, env.okHttp, env.originalUrl, env.details, env.onBackUp, env.onDelete, onHide = { hideRequest = longArrayOf(it) },
            onFavourite = { id, on -> vm.favourites.set(longArrayOf(id), on) }, onAddToAlbum = { albumPicker = longArrayOf(it) },
            onRemoveFromAlbum = { id -> vm.albums.remove(name, longArrayOf(id)); viewer = null },
        )
    }
    val hiddenEnv = remember(env) {
        ViewerEnv(env.previews, env.loader, env.okHttp, env.originalUrl, env.details, onHide = { id -> vm.people.unhidePhotos(longArrayOf(id)); viewer = null }, hideIsUnhide = true, allowShare = false, allowDelete = false)
    }
    // hidden photos lock again when the app is left, and are kept out of screenshots and the recent apps list while they are on screen
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) today = java.time.LocalDate.now()
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                stack = stack.filterNot { it is Page.Hidden }
                if (viewer?.hidden == true) viewer = null
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val secure = stack.any { it is Page.Hidden } || viewer?.hidden == true
    SecureWindow(secure)
    val unlockTitle = stringResource(R.string.unlock_title)
    val unlockSubtitle = stringResource(R.string.unlock_subtitle)
    val openHidden: () -> Unit = {
        unlock.unlock(unlockTitle, unlockSubtitle) { r ->
            when (r) {
                UnlockResult.Success -> stack = stack + Page.Hidden
                UnlockResult.NoLock -> noLockDialog = true
                UnlockResult.Cancelled -> Unit
            }
        }
    }

    val waiting = (state.sync as? SyncUi.Done)?.result as? SyncResult.WaitingForWifi
    val offline = (state.sync as? SyncUi.Done)?.result is SyncResult.Offline
    val banner: (@Composable () -> Unit)? =
        if (offline || waiting != null || state.localAccess != LocalAccess.Full) ({
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.localAccess != LocalAccess.Full) LocalAccessBanner(state.localAccess, onAllowLocal, onOpenAppSettings)
                if (offline) OfflineBanner(onRetry = vm::sync)
                if (waiting != null) Notice(stringResource(R.string.waiting_for_wifi, (waiting.sizeBytes / 1_000_000).toInt()), stringResource(R.string.download_now_mobile)) { vm.sync(overrideMobile = true) }
            }
        }) else null
    val tabLabels = Triple(stringResource(R.string.library), stringResource(R.string.collections), stringResource(R.string.search))
    // a person's page (their photos, newest first, are worked out when the page is drawn)
    val openPerson: (dev.bookah.photos.data.Person) -> Unit = { p -> stack = stack + Page.Person(p.id) }
    val personActions = remember(vm) {
        dev.bookah.photos.collections.PersonActions(onName = vm.people::namePerson, onMerge = vm.people::mergeInto, onHide = vm.people::hide, onUnhide = vm.people::unhide)
    }
    val whoActions = remember(vm) { dev.bookah.photos.collections.WhoIsActions(onAssign = vm.people::assignFaces, onNewPerson = vm.people::newPerson, onNotAPerson = vm.people::notAPerson) }
    val faceActions = remember(vm) { dev.bookah.photos.collections.FaceActions(onCover = vm.people::setCover, onMove = vm.people::moveFace, onNotAPerson = vm.people::notAPerson) }
    val greekOrEnglishMap = stringResource(dev.bookah.photos.map.R.string.map)
    val memoryRow = remember(state.memories, people, peopleState.unwantedMemories, shown) { state.memories.forRow(today, peopleState.unwantedMemories, people, { shown.indexOfId(it) != null }) }
    val memoryAll = remember(state.memories, people, peopleState.unwantedMemories, shown) { state.memories.forAll(today, peopleState.unwantedMemories, people, { shown.indexOfId(it) != null }) }
    val tripList = remember(memoryAll) { memoryAll.filter { it.type == dev.bookah.photos.data.Memory.TYPE_TRIP } }
    val setSelection = remember(vm) {
        SelectionActions(
            onShare = vm::share, onDelete = vm::askDelete, onHide = { hideRequest = it }, onFavourite = { ids, on -> vm.favourites.set(ids, on) }, onAddToAlbum = { albumPicker = it },
        )
    }
    val setTick = state.deleteDoneTick + hideDoneTick + albumTick
    // photos that are the same picture saved more than once, a group after another (newest group last, as in the library)
    val dupIndices = remember(shown, state.duplicates) {
        dev.bookah.photos.data.DuplicateGroups.visible(state.duplicates) { shown.indexOfId(it) != null }.mapNotNull { shown.indexOfId(it) }.toIntArray()
    }
    val dupCount = dupIndices.size
    val openCard: (CollectionCard) -> Unit = { card ->
        stack = stack + if (card.key.startsWith("album:")) Page.Album(card.title) else Page.Photos(card.title, LongArray(card.indices.size) { shown.ids[card.indices[it]] })
    }

    Box(Modifier.fillMaxSize()) {
        when (tab) {
            Tab.Library -> LibraryScreen(
                effective, previews, loader, onShare = vm::share, onOpen = { viewer = ViewerRequest(effective.ids[it], null, true) },
                onTitleLongPress = onTitleLongPress, controller = libraryController, favourites = favourites, banner = banner,
                onLibraryButton = { showTabs = true }, hideBottomBar = showTabs,
                source = source, onSource = { source = it }, showSource = state.localAccess != LocalAccess.None || source != Source.All,
                onSelectingChange = { selecting = it },
                onDelete = vm::askDelete, clearSelectionTick = state.deleteDoneTick + hideDoneTick + albumTick, onHide = { hideRequest = it }, onFavourite = { ids, on -> vm.favourites.set(ids, on) }, onAddToAlbum = { albumPicker = it },
                onSearch = { stack = stack + Page.Search },
            )
            Tab.Collections -> CollectionsScreen(
                shown, model, state.prefs, vm::updatePrefs, previews, loader, onCard = openCard,
                onSeeAll = { stack = stack + when (it) { SectionId.People -> Page.People; SectionId.Memories -> Page.Memories; SectionId.Trips -> Page.Trips; else -> Page.SeeAll(it) } }, onSettings = { stack = stack + Page.Settings },
                onReorder = { stack = stack + Page.Reorder }, bottomPadding = barClearance, banner = banner,
                people = people, onPerson = openPerson,
                memories = memoryRow, greek = greek, onMemory = { stack = stack + Page.Memory(it.key) }, onMap = { mapCamera = null; stack = stack + Page.Map }, onHidden = openHidden, trips = tripList, onNewAlbum = { albumName = AlbumNameRequest.New(null) },
                duplicates = dupCount, onDuplicates = { stack = stack + Page.Duplicates },
            )
        }
        val barModifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 22.dp)
        if (stack.isEmpty() && (tab == Tab.Collections || showTabs)) {
            TabBar(
                selected = tab, onSelect = { t -> tab = t; showTabs = false }, modifier = barModifier,
                libraryLabel = tabLabels.first, collectionsLabel = tabLabels.second, searchLabel = tabLabels.third,
                onSearch = { stack = stack + Page.Search },
            )
        }

        // a pushed page on top
        when (val page = stack.lastOrNull()) {
            Page.Settings -> SettingsScreen(
                state, loader, onBack = { stack = stack.dropLast(1) }, onRefresh = vm::sync, onSignOut = vm::signOut, onAllowLocal = onAllowLocal,
                onCheckUpdates = vm::checkUpdates, onOpenUrl = onOpenUrl, bench = bench,
                onMeasureScrolling = { stack = emptyList(); tab = Tab.Library; showTabs = false; source = Source.All; benchRequested++ },
                searchUi = searchUi, modelBytes = vm.search.modelBytesOnPhone(), people = peopleState, onAllowMobileData = vm::setAllowMobileData, onDownloadNow = { vm.sync(overrideMobile = true) }, onDownloadModel = vm.search::downloadModel, onDeleteModel = vm.search::deleteModel,
            )
            Page.Search -> SearchPage(
                searchUi, shown, previews, loader, vm.search, greek = locale.language == "el",
                onOpen = { index, ids -> viewer = ViewerRequest(shown.ids[index], ids, false) },
                onBack = { stack = stack.dropLast(1) }, bottomPadding = bottomInset + 24.dp,
            )
            Page.Reorder -> ReorderScreen(state.prefs, vm::updatePrefs, onDone = { stack = stack.dropLast(1) })
            is Page.SeeAll -> CardGridPage(
                sectionTitle(page.section), shown, model.of(page.section), previews, loader, onCard = openCard, onBack = { stack = stack.dropLast(1) }, bottomPadding = barClearance,
            )
            Page.People -> dev.bookah.photos.collections.PeoplePage(
                people, previews, loader, onPerson = openPerson, onBack = { stack = stack.dropLast(1) }, bottomPadding = barClearance,
                onHidden = { stack = stack + Page.HiddenPeople }, onWhoIsThis = { stack = stack + Page.WhoIsThis },
            )
            Page.WhoIsThis -> dev.bookah.photos.collections.WhoIsThisPage(
                people, previews, loader, whoActions, onBack = { stack = stack.dropLast(1) }, bottomPadding = barClearance,
            )
            Page.Map -> {
                val points = remember(state.places, shown) {
                    state.places.photos.mapNotNull { g -> shown.indexOfId(g.assetId)?.let { dev.bookah.photos.map.MapPoint(it, g.assetId, g.latitude, g.longitude) } }
                }
                dev.bookah.photos.map.MapPage(
                    points, shown, previews, loader, BuildConfig.VERSION_NAME,
                    onOpen = { pts -> val newest = pts.sortedByDescending { it.libIndex }; stack = stack + Page.Photos(greekOrEnglishMap, LongArray(newest.size) { n -> newest[n].assetId }) },
                    onBack = { stack = stack.dropLast(1) },
                    initialCamera = mapCamera, onCamera = { mapCamera = it },
                )
            }
            is Page.Album -> {
                val album = state.lists?.albums?.firstOrNull { it.name == page.name }
                if (album == null) {
                    LaunchedEffect(page, albumBusy) { if (albumBusy == null) stack = stack.dropLast(1) }                       // deleted or renamed away
                } else {
                    val indices = remember(shown, album) { shown.indicesOfIds(album.itemIds) }
                    dev.bookah.photos.collections.PhotoSetPage(
                        album.name, shown, indices, previews, loader, favourites,
                        onOpen = { viewer = ViewerRequest(shown.ids[it], LongArray(indices.size) { n -> shown.ids[indices[n]] }, false, album = album.name) },
                        onBack = { stack = stack.dropLast(1) }, bottomPadding = bottomInset + 24.dp,
                        selection = setSelection, clearSelectionTick = setTick,
                        actions = { dev.bookah.photos.collections.AlbumMenu(onRename = { albumName = AlbumNameRequest.Rename(album.name) }, onDelete = { albumDelete = album.name }) },
                    )
                }
            }
            Page.Duplicates -> dev.bookah.photos.collections.PhotoSetPage(
                stringResource(dev.bookah.photos.collections.R.string.duplicates), shown, dupIndices, previews, loader, favourites,
                onOpen = { viewer = ViewerRequest(shown.ids[it], LongArray(dupIndices.size) { n -> shown.ids[dupIndices[n]] }, false) },
                onBack = { stack = stack.dropLast(1) }, bottomPadding = bottomInset + 24.dp,
                selection = setSelection, clearSelectionTick = setTick,
            )
            Page.Hidden -> {
                val hl = state.hiddenLibrary
                val order = remember(hl) { IntArray(hl.size) { hl.size - 1 - it } }                          // newest first
                dev.bookah.photos.collections.PhotoSetPage(
                    stringResource(dev.bookah.photos.collections.R.string.hidden_photos), hl, order, previews, loader, emptySet(),
                    onOpen = { viewer = ViewerRequest(hl.ids[it], LongArray(order.size) { n -> hl.ids[order[n]] }, false, hidden = true) },
                    onBack = { stack = stack.dropLast(1) }, bottomPadding = bottomInset + 24.dp,
                    selection = SelectionActions(onShare = null, onDelete = null, onUnhide = { ids -> vm.people.unhidePhotos(ids); hideDoneTick++ }), clearSelectionTick = hideDoneTick,
                )
            }
            Page.Trips -> dev.bookah.photos.collections.MemoriesPage(
                shown, tripList, greek, previews, loader, onMemory = { stack = stack + Page.Memory(it.key) }, onBack = { stack = stack.dropLast(1) }, bottomPadding = barClearance,
                title = stringResource(dev.bookah.photos.collections.R.string.trips),
            )
            Page.Memories -> dev.bookah.photos.collections.MemoriesPage(
                shown, memoryAll, greek, previews, loader, onMemory = { stack = stack + Page.Memory(it.key) }, onBack = { stack = stack.dropLast(1) }, bottomPadding = barClearance,
            )
            is Page.Memory -> {
                val memory = state.memories.byKey(page.key)
                if (memory == null) LaunchedEffect(page) { stack = stack.dropLast(1) }
                else dev.bookah.photos.collections.MemoryPlayer(
                    shown, memory, greek, previews, loader,
                    onClose = { stack = stack.dropLast(1) },
                    onSeeAll = { stack = stack.dropLast(1) + Page.Photos(memory.title(greek), LongArray(memory.assetIds.size) { memory.assetIds[it] }) },
                    onUnwanted = { vm.people.unwantMemory(memory.key); memoryUndo = memory.key; stack = stack.dropLast(1) },
                )
            }
            Page.HiddenPeople -> dev.bookah.photos.collections.HiddenPeoplePage(
                people, previews, loader, onShow = vm.people::unhide, onBack = { stack = stack.dropLast(1) }, bottomPadding = barClearance,
            )
            is Page.Person -> {
                val person = people.byId(page.id)
                if (person == null || person.hidden) {
                    LaunchedEffect(page) { stack = stack.dropLast(1) }                // merged into somebody, hidden or gone: the page closes
                } else {
                    val positions = remember(shown, person) { shown.indicesOfIds(person.assetIds.asList()).also { it.sortDescending() } }
                    dev.bookah.photos.collections.PersonPage(
                        person, people, shown, positions, previews, loader, personActions,
                        onOpen = { viewer = ViewerRequest(shown.ids[it], LongArray(positions.size) { n -> shown.ids[positions[n]] }, false) },
                        onBack = { stack = stack.dropLast(1) }, bottomPadding = bottomInset + 24.dp, onFaces = { stack = stack + Page.Faces(person.id) },
                    )
                }
            }
            is Page.Faces -> {
                val person = people.byId(page.id)
                if (person == null) LaunchedEffect(page) { stack = stack.dropLast(1) }
                else dev.bookah.photos.collections.FacesPage(person, people, previews, loader, faceActions, onBack = { stack = stack.dropLast(1) }, bottomPadding = bottomInset + 24.dp)
            }
            is Page.Photos -> PhotoSetPage(
                page.title, shown, remember(shown, page) { shown.indicesOfIds(page.ids.asList()) }, previews, loader, favourites,
                onOpen = { viewer = ViewerRequest(shown.ids[it], page.ids, false) },
                onBack = { stack = stack.dropLast(1) }, bottomPadding = bottomInset + 24.dp,
                selection = setSelection, clearSelectionTick = setTick,
            )
            null -> Unit
        }

        memoryUndo?.let { key ->
            LaunchedEffect(key) { kotlinx.coroutines.delay(8_000); if (memoryUndo == key) memoryUndo = null }
            if (stack.isEmpty()) Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 100.dp, start = 20.dp, end = 20.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(PhotoTheme.colors.surface).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(dev.bookah.photos.collections.R.string.memory_removed), Modifier.weight(1f), color = PhotoTheme.colors.textPrimary)
                TextButton(onClick = { vm.people.wantMemory(key); memoryUndo = null }) { Text(stringResource(dev.bookah.photos.collections.R.string.undo)) }
            }
        }
        albumPicker?.let { ids ->
            dev.bookah.photos.collections.AlbumPickerDialog(
                state.lists?.albums.orEmpty(), onPick = { a -> vm.albums.add(a.name, ids); albumPicker = null; albumTick++ },
                onNew = { albumName = AlbumNameRequest.New(ids); albumPicker = null }, onDismiss = { albumPicker = null },
            )
        }
        albumName?.let { req ->
            val existing = state.lists?.albums.orEmpty().map { it.name }
            dev.bookah.photos.collections.AlbumNameDialog(
                title = stringResource(if (req is AlbumNameRequest.New) dev.bookah.photos.collections.R.string.new_album else dev.bookah.photos.collections.R.string.rename_album),
                initial = (req as? AlbumNameRequest.Rename)?.old.orEmpty(), taken = existing,
                onDone = { n ->
                    when (req) {
                        is AlbumNameRequest.New -> { vm.albums.create(n, req.thenAdd); albumTick++ }
                        is AlbumNameRequest.Rename -> { vm.albums.rename(req.old, n); stack = stack.map { if (it is Page.Album && it.name == req.old) Page.Album(n) else it } }
                    }
                    albumName = null
                },
                onDismiss = { albumName = null },
            )
        }
        albumDelete?.let { name ->
            dev.bookah.photos.collections.DeleteAlbumDialog(name, onConfirm = { vm.albums.delete(name); albumDelete = null }, onDismiss = { albumDelete = null })
        }
        albumBusy?.let { b -> AlbumBusyOverlay(b) }
        albumMessage?.let { m ->
            LaunchedEffect(m) { kotlinx.coroutines.delay(4_000); vm.albums.clearMessage() }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 100.dp, start = 20.dp, end = 20.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(PhotoTheme.colors.surface).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(albumMessageText(m), Modifier.weight(1f).padding(vertical = 12.dp), color = PhotoTheme.colors.textPrimary)
                TextButton(onClick = { vm.albums.clearMessage() }) { Text(stringResource(R.string.ok)) }
            }
        }
        hideRequest?.let { ids ->
            val real = ids.count { it > 0 }
            val phoneOnly = ids.size - real
            AlertDialog(
                onDismissRequest = { hideRequest = null },
                title = { Text(if (real == 0) stringResource(R.string.hide_none_title) else stringResource(R.string.hide_title, real)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (real == 0) Text(stringResource(R.string.hide_none_text)) else Text(stringResource(R.string.hide_text))
                        if (real > 0 && phoneOnly > 0) Text(stringResource(R.string.hide_phone_only, phoneOnly), color = PhotoTheme.colors.textSecondary)
                    }
                },
                confirmButton = {
                    if (real > 0) TextButton(onClick = { vm.people.hidePhotos(ids); hideRequest = null; hideDoneTick++ }) { Text(stringResource(R.string.hide_confirm)) }
                    else TextButton(onClick = { hideRequest = null }) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = { if (real > 0) TextButton(onClick = { hideRequest = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        if (noLockDialog) {
            AlertDialog(
                onDismissRequest = { noLockDialog = false },
                title = { Text(stringResource(R.string.no_lock_title)) },
                text = { Text(stringResource(R.string.no_lock_text)) },
                confirmButton = { TextButton(onClick = { noLockDialog = false }) { Text(stringResource(R.string.ok)) } },
            )
        }
        state.deleteIds?.let { ids -> DeleteDialog(DeletePlan.of(shown, ids), onChoose = vm::delete, onCancel = vm::cancelDelete) }

        // the viewer over everything
        viewer?.let { req ->
            val vlib = if (req.hidden) state.hiddenLibrary else if (req.fromLibrary) effective else shown
            val start = vlib.indexOfId(req.startId)
            val order = req.orderIds?.let { vlib.indicesOfIds(it.asList()) }
            if (start == null || (order != null && order.isEmpty())) {
                LaunchedEffect(req) { viewer = null }
            } else {
                ViewerOverlay(
                    vlib, start, if (req.hidden) hiddenEnv else if (req.album != null) albumEnv(req.album) else viewerEnv, if (req.fromLibrary) libraryController else noController,
                    onShare = { if (!req.hidden) vm.share(longArrayOf(it)) }, onClose = { viewer = null }, order = order, favourites = favourites,
                )
            }
        }

        // last, so it is also over the viewer: deleting can take a while and the reader must see that it is working
        state.deleteProgress?.let { DeleteProgressOverlay(it) }
    }
}

/** A slim notice under the header when Nextcloud cannot be reached; everything already on the phone keeps working. */
@Composable
fun OfflineBanner(onRetry: () -> Unit) {
    Notice(stringResource(R.string.offline), stringResource(R.string.retry), onRetry)
}

/** Asks for permission to show the photos on this phone, or explains that only some are shared. */
@Composable
fun LocalAccessBanner(access: LocalAccess, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    if (access == LocalAccess.Partial) Notice(stringResource(R.string.local_access_partial), stringResource(R.string.local_access_partial_fix), onOpenSettings)
    else Notice(stringResource(R.string.local_access_none), stringResource(R.string.local_access_allow), onAllow)
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(16.dp)).background(PhotoTheme.colors.surface).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium, color = PhotoTheme.colors.textPrimary)
        TextButton(onClick = onAction) { Text(action) }
    }
}


/**
 * The confirmation before anything is deleted. It says how many files go and where, and that everything goes to a trash that can be
 * restored from (Android's trash on the phone, the trash bin of Nextcloud). The reader chooses where each time.
 */
@Composable
fun DeleteDialog(plan: DeletePlan, onChoose: (DeleteScope) -> Unit, onCancel: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.delete_title, plan.total)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (plan.onPhone > 0) Text(stringResource(R.string.delete_line_phone, plan.onPhone))
                if (plan.inNextcloud > 0) Text(stringResource(R.string.delete_line_nextcloud, plan.inNextcloud))
                if (plan.bothSides > 0) Text(stringResource(R.string.delete_line_both, plan.bothSides), color = PhotoTheme.colors.textSecondary)
                Text(stringResource(R.string.delete_note), color = PhotoTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (plan.onPhone > 0) TextButton(onClick = { onChoose(DeleteScope.Phone) }) { Text(stringResource(R.string.delete_phone, plan.onPhone)) }
                if (plan.inNextcloud > 0) TextButton(onClick = { onChoose(DeleteScope.Nextcloud) }) { Text(stringResource(R.string.delete_nextcloud, plan.inNextcloud)) }
                if (plan.onPhone > 0 && plan.inNextcloud > 0) TextButton(onClick = { onChoose(DeleteScope.Everywhere) }) { Text(stringResource(R.string.delete_everywhere)) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}


/** Shown while a delete runs: it covers the screen so nothing else is touched, and counts the files as they are moved. */
@Composable
fun DeleteProgressOverlay(progress: DeleteProgress) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(PhotoTheme.colors.surface).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (progress.total > 0) {
                androidx.compose.material3.CircularProgressIndicator(progress = { progress.done.toFloat() / progress.total })
                Text(stringResource(R.string.delete_progress, progress.done, progress.total), color = PhotoTheme.colors.textPrimary, style = MaterialTheme.typography.titleMedium)
            } else {
                androidx.compose.material3.CircularProgressIndicator()
                Text(stringResource(R.string.delete_working), color = PhotoTheme.colors.textPrimary, style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.delete_takes_a_moment), color = PhotoTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}


/** Result of the scrolling test: how many frames, how many took more than one and a half refresh intervals, the worst and the typical frame. */
class BenchResult(val frames: Int, val refreshHz: Float, val slowPercent: Float, val worstMs: Float, val medianMs: Float)


/** What the album name dialog is for: a new album (and photos to put in it at once) or a new name for an album. */
private sealed interface AlbumNameRequest {
    class New(val thenAdd: LongArray?) : AlbumNameRequest
    class Rename(val old: String) : AlbumNameRequest
}

@Composable
private fun albumMessageText(m: AlbumsMessage): String = when (m) {
    is AlbumsMessage.Added -> if (m.skipped > 0) stringResource(R.string.album_added_skipped, m.count, m.album, m.skipped) else stringResource(R.string.album_added, m.count, m.album)
    is AlbumsMessage.Removed -> stringResource(R.string.album_removed, m.count)
    is AlbumsMessage.Created -> stringResource(R.string.album_created, m.name)
    is AlbumsMessage.Renamed -> stringResource(R.string.album_renamed, m.name)
    is AlbumsMessage.Deleted -> stringResource(R.string.album_deleted, m.name)
    is AlbumsMessage.NameTaken -> stringResource(R.string.album_name_taken, m.name)
    is AlbumsMessage.Failed -> if (m.done > 0) stringResource(R.string.album_failed_partly, m.done) else stringResource(R.string.album_failed)
}

/** Shown while an album action runs (it talks to Nextcloud, which can take a moment). */
@Composable
private fun AlbumBusyOverlay(b: AlbumsBusy) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x80000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(PhotoTheme.colors.surface).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (b.total > 1) {
                androidx.compose.material3.CircularProgressIndicator(progress = { b.done.toFloat() / b.total })
                Text(stringResource(R.string.album_progress, b.done, b.total), color = PhotoTheme.colors.textPrimary, style = MaterialTheme.typography.titleMedium)
            } else {
                androidx.compose.material3.CircularProgressIndicator()
                Text(stringResource(R.string.album_working), color = PhotoTheme.colors.textPrimary, style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.delete_takes_a_moment), color = PhotoTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
