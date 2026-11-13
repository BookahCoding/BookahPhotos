package dev.bookah.photos.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.bookah.photos.data.FaceBox
import dev.bookah.photos.data.PeopleIndex
import dev.bookah.photos.data.Person
import dev.bookah.photos.design.PhotoTheme
import dev.bookah.photos.library.PillButton
import dev.bookah.photos.library.Previews
import dev.bookah.photos.library.SelectMark
import dev.bookah.photos.library.dragSelect

/** What can be done with faces of nobody. */
class WhoIsActions(
    /** Gives these faces to a person who exists. */
    val onAssign: (List<FaceBox>, Person) -> Unit,
    /** Makes a new person with this name from these faces. */
    val onNewPerson: (String, List<FaceBox>) -> Unit,
    /** These faces are not people (a statue, a picture on a wall). */
    val onNotAPerson: (List<FaceBox>) -> Unit,
)

/**
 * "Who is this?": the faces that belong to nobody yet, clearest first. Select some (tap, or press and hold and slide), then say who they are: one of the people
 * you have, or a new person (a name). A new person shows in People once the indexer has found 5 faces for them; the faces you give it count as certain.
 */
@Composable
fun WhoIsThisPage(people: PeopleIndex, previews: Previews, loader: ImageLoader, actions: WhoIsActions, onBack: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val faces = people.unassigned
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val state = rememberLazyGridState()
    val facesNow = rememberUpdatedState(faces)
    val selectedNow = rememberUpdatedState(selected)
    val alwaysSelecting = rememberUpdatedState(true)
    val countNow = rememberUpdatedState(faces.size)
    val idAtNow = rememberUpdatedState<(Int) -> Long>({ n -> facesNow.value[n].id })
    val zeroPad = rememberUpdatedState(0)
    val onStartNow = rememberUpdatedState<(Int) -> Unit>({ n -> selected = setOf(facesNow.value[n].id) })
    val onRangeNow = rememberUpdatedState<(Int, Int, Boolean) -> Unit>({ from, to, select ->
        val ids = (minOf(from, to)..maxOf(from, to)).mapNotNull { facesNow.value.getOrNull(it)?.id }
        selected = if (select) selected + ids else selected - ids.toSet()
    })
    val chosen = remember(selected, faces) { faces.filter { it.id in selected } }
    LaunchedEffect(faces) { selected = selected.filter { id -> faces.any { it.id == id } }.toSet() }       // faces that were just given away leave the selection
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    val unknownLabel = stringResource(R.string.face_unknown)
    Box(modifier.fillMaxSize().background(PhotoTheme.colors.background)) {
        if (faces.isEmpty()) {
            Text(stringResource(R.string.who_is_empty), Modifier.align(Alignment.Center).padding(32.dp), color = PhotoTheme.colors.textSecondary)
        } else {
            LazyVerticalGrid(
                GridCells.Fixed(4), Modifier.fillMaxSize().dragSelect(state, countNow, idAtNow, zeroPad, alwaysSelecting, selectedNow, onStartNow, onRangeNow).testTag("who-grid"), state = state,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = top + 72.dp + 28.dp, bottom = bottomPadding + 90.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(faces.size, key = { faces[it].id }) { n ->
                    val f = faces[n]
                    val isSel = f.id in selected
                    Box(
                        Modifier.testTag("who-face-${f.id}").semantics { contentDescription = unknownLabel; this.selected = isSel }
                            .clickable { selected = if (isSel) selected - f.id else selected + f.id },
                    ) {
                        FaceImage(f.id, f.assetId, floatArrayOf(f.x.toFloat(), f.y.toFloat(), f.w.toFloat(), f.h.toFloat()), previews, loader, Modifier.fillMaxWidth())
                        SelectMark(isSel)
                    }
                }
            }
        }
        PageHeader(stringResource(R.string.who_is_this), onBack, Modifier.align(Alignment.TopStart).padding(top = top))
        if (faces.isNotEmpty()) Text(
            stringResource(R.string.who_is_hint, faces.size), Modifier.align(Alignment.TopStart).padding(top = top + 64.dp, start = 20.dp, end = 20.dp),
            style = MaterialTheme.typography.bodyMedium, color = PhotoTheme.colors.textSecondary,
        )
        if (selected.isNotEmpty()) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 22.dp, start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(stringResource(R.string.who_clear, selected.size), { selected = emptySet() }, Modifier.testTag("who-clear"))
                PillButton(stringResource(R.string.who_assign), { picking = true }, Modifier.testTag("who-assign"))
                PillButton(stringResource(R.string.who_not_person), { actions.onNotAPerson(chosen); selected = emptySet() }, Modifier.testTag("who-not-person"))
            }
        }
    }
    if (picking) {
        var query by rememberSaveable { mutableStateOf("") }
        val key = PeopleIndex.foldName(query)
        val candidates = remember(people, query) { people.people.filter { key.isEmpty() || (it.name?.let { n -> PeopleIndex.foldName(n).contains(key) } == true) }.take(60) }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.who_pick_title, chosen.size)) },
            text = {
                Column {
                    TextButton(onClick = { picking = false; naming = true }, modifier = Modifier.testTag("who-new")) { Text(stringResource(R.string.who_new_person)) }
                    OutlinedTextField(query, { query = it.take(100) }, singleLine = true, label = { Text(stringResource(R.string.name_hint)) }, modifier = Modifier.fillMaxWidth().testTag("who-search"))
                    LazyColumn(Modifier.padding(top = 8.dp).height(280.dp)) {
                        items(candidates, key = { it.id }) { p ->
                            Row(
                                Modifier.fillMaxWidth().clickable { actions.onAssign(chosen, p); selected = emptySet(); picking = false }.padding(vertical = 6.dp).testTag("who-pick-${p.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PersonAvatar(p, previews, loader, Modifier.size(44.dp))
                                Text(personTitle(p) + " · " + stringResource(R.string.photos_n, p.photoCount), Modifier.padding(start = 12.dp), color = PhotoTheme.colors.textPrimary)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (naming) {
        // the same name dialog as for a person: it also asks "Same person as X?" when the name exists (yes = give the faces to X, no = a new person with the same name)
        val placeholder = remember { Person(Long.MIN_VALUE, null, -1, -1, floatArrayOf(0f, 0f, 0f, 0f), longArrayOf()) }
        NameDialog(
            placeholder, people, onDismiss = { naming = false },
            onName = { name -> actions.onNewPerson(name, chosen); selected = emptySet(); naming = false },
            onMerge = { into -> actions.onAssign(chosen, into); selected = emptySet(); naming = false },
        )
    }
}
