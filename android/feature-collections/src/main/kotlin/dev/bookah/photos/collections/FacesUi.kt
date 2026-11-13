package dev.bookah.photos.collections

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.bookah.photos.data.FaceBox
import dev.bookah.photos.data.PeopleIndex
import dev.bookah.photos.data.Person
import dev.bookah.photos.design.PhotoTheme
import dev.bookah.photos.library.Previews
import kotlinx.coroutines.delay

/** What the reader can do to one face of a person. */
class FaceActions(
    val onCover: (Person, FaceBox) -> Unit,
    /** Gives the face to [Person], or to nobody when null. */
    val onMove: (FaceBox, Person?) -> Unit,
    val onNotAPerson: (FaceBox) -> Unit,
)

/**
 * All the faces of a person, cut out. Tap one to use it as the cover, to say it is not this person (it goes to nobody), to give it to someone else,
 * or to say it is not a person at all (a statue, a poster). A bar offers Undo for a few seconds after each change.
 */
@Composable
fun FacesPage(person: Person, people: PeopleIndex, previews: Previews, loader: ImageLoader, actions: FaceActions, onBack: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var clearestFirst by rememberSaveable { mutableStateOf(true) }
    var selected by remember { mutableStateOf<FaceBox?>(null) }
    var picking by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(8_000)
            undo = null
        }
    }
    val faces = remember(person, clearestFirst) { if (clearestFirst) person.faces.sortedByDescending { it.quality } else person.faces.sortedBy { it.quality } }
    val previousCover = person.faces.firstOrNull { it.id == person.coverFaceId }
    Box(modifier.fillMaxSize().background(PhotoTheme.colors.background)) {
        LazyVerticalGrid(
            GridCells.Fixed(4), Modifier.fillMaxSize().testTag("faces-grid"),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = top + 72.dp, bottom = bottomPadding + 56.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { clearestFirst = !clearestFirst }, modifier = Modifier.testTag("faces-sort")) {
                    Text(stringResource(if (clearestFirst) R.string.faces_sort_least_clear else R.string.faces_sort_clearest))
                }
            }
            items(faces.size, key = { faces[it].id }) { n ->
                val f = faces[n]
                val faceLabel = stringResource(R.string.face_of, personTitle(person))
                FaceImage(f.id, f.assetId, floatArrayOf(f.x.toFloat(), f.y.toFloat(), f.w.toFloat(), f.h.toFloat()), previews, loader, Modifier.semantics { contentDescription = faceLabel }.clickable { selected = f }.testTag("face-${f.id}"))
            }
        }
        PageHeader(personTitle(person) + " · " + stringResource(R.string.faces_title), onBack, Modifier.align(Alignment.TopStart).padding(top = top))
        undo?.let { u ->
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding).padding(horizontal = 20.dp).fillMaxWidth()
                    .background(PhotoTheme.colors.surface, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.faces_done), Modifier.weight(1f), color = PhotoTheme.colors.textPrimary)
                TextButton(onClick = { u(); undo = null }, modifier = Modifier.testTag("faces-undo")) { Text(stringResource(R.string.undo)) }
            }
        }
    }
    val face = selected
    if (face != null && !picking) {
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(stringResource(R.string.face_what)) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            actions.onCover(person, face)
                            undo = previousCover?.takeIf { it.id != face.id }?.let { old -> { actions.onCover(person, old) } }
                            selected = null
                        },
                        modifier = Modifier.testTag("face-cover"),
                    ) { Text(stringResource(R.string.face_use_cover)) }
                    TextButton(
                        onClick = { actions.onMove(face, null); undo = { actions.onMove(face, person) }; selected = null },
                        modifier = Modifier.testTag("face-not-this"),
                    ) { Text(stringResource(R.string.face_not_this)) }
                    TextButton(onClick = { picking = true }, modifier = Modifier.testTag("face-someone")) { Text(stringResource(R.string.face_someone_else)) }
                    TextButton(
                        onClick = { actions.onNotAPerson(face); undo = { actions.onMove(face, person) }; selected = null },
                        modifier = Modifier.testTag("face-not-person"),
                    ) { Text(stringResource(R.string.face_not_person)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (face != null && picking) {
        var query by rememberSaveable { mutableStateOf("") }
        val key = PeopleIndex.foldName(query)
        val candidates = remember(people, query) {
            people.people.filter { it.id != person.id && (key.isEmpty() || (it.name?.let { n -> PeopleIndex.foldName(n).contains(key) } == true)) }.take(60)
        }
        AlertDialog(
            onDismissRequest = { picking = false; selected = null },
            title = { Text(stringResource(R.string.move_to_title)) },
            text = {
                Column {
                    OutlinedTextField(query, { query = it.take(100) }, singleLine = true, label = { Text(stringResource(R.string.name_hint)) }, modifier = Modifier.fillMaxWidth().testTag("move-search"))
                    LazyColumn(Modifier.padding(top = 8.dp).height(300.dp)) {
                        items(candidates, key = { it.id }) { p ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { actions.onMove(face, p); undo = { actions.onMove(face, person) }; picking = false; selected = null }
                                    .padding(vertical = 6.dp).testTag("move-to-${p.id}"),
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
            dismissButton = { TextButton(onClick = { picking = false; selected = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
