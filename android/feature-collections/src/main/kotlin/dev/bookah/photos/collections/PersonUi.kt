package dev.bookah.photos.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.ImageLoader
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.PeopleIndex
import dev.bookah.photos.data.Person
import dev.bookah.photos.design.PhotoTheme
import dev.bookah.photos.library.PhotoImage
import dev.bookah.photos.library.Previews

/** What the reader can do to a person; each one is a correction that is saved on the phone, shown at once and sent to BookahPC. */
class PersonActions(
    val onName: (Person, String) -> Unit,
    val onMerge: (from: Person, into: Person) -> Unit,
    val onHide: (Person) -> Unit,
    val onUnhide: (Person) -> Unit,
)

/**
 * A person's page: their face, their name (or "Not named" with a button to name them), how many photos, and the photos in the usual grid, newest first.
 * Naming a person with the name of somebody who is already named asks "Same person?" and joins them if the reader says yes.
 */
@Composable
fun PersonPage(
    person: Person, people: PeopleIndex, lib: LibraryIndex, indices: IntArray, previews: Previews, loader: ImageLoader, actions: PersonActions,
    onOpen: (index: Int) -> Unit, onBack: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier, onFaces: () -> Unit = {},
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val labeller = dev.bookah.photos.library.rememberPhotoLabeller()
    var naming by rememberSaveable(person.id) { mutableStateOf(false) }
    var hiding by rememberSaveable(person.id) { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(PhotoTheme.colors.background)) {
        LazyVerticalGrid(
            GridCells.Fixed(4), Modifier.fillMaxSize().testTag("person-page"),
            contentPadding = PaddingValues(top = top + 72.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PersonAvatar(person, previews, loader, Modifier.size(88.dp), outPx = 256)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(personTitle(person), style = MaterialTheme.typography.headlineSmall, color = if (person.name.isNullOrBlank()) PhotoTheme.colors.textSecondary else PhotoTheme.colors.textPrimary, modifier = Modifier.testTag("person-name"))
                            Text(stringResource(R.string.photos_n, person.photoCount), style = MaterialTheme.typography.bodyMedium, color = PhotoTheme.colors.textSecondary)
                        }
                    }
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (person.name.isNullOrBlank()) FilledTonalButton(onClick = { naming = true }, modifier = Modifier.testTag("name-button")) { Text(stringResource(R.string.name_person)) }
                        else OutlinedButton(onClick = { naming = true }, modifier = Modifier.testTag("name-button")) { Text(stringResource(R.string.rename_person)) }
                        OutlinedButton(onClick = onFaces, modifier = Modifier.testTag("faces-button")) { Text(stringResource(R.string.faces_title)) }
                        OutlinedButton(onClick = { hiding = true }, modifier = Modifier.testTag("hide-button")) { Text(stringResource(R.string.hide_person)) }
                    }
                }
            }
            items(indices.size) { n ->
                val i = indices[n]
                PhotoImage(i, lib, 256, previews, loader, Modifier.aspectRatio(1f).semantics { contentDescription = labeller(lib, i) }.clickable { onOpen(i) })
            }
        }
        PageHeader(personTitle(person), onBack, Modifier.align(Alignment.TopStart).padding(top = top))
    }
    if (naming) NameDialog(person, people, onDismiss = { naming = false }, onName = { actions.onName(person, it); naming = false }, onMerge = { into -> actions.onMerge(person, into); naming = false })
    if (hiding) {
        AlertDialog(
            onDismissRequest = { hiding = false },
            title = { Text(stringResource(R.string.hide_person_title)) },
            text = { Text(stringResource(R.string.hide_person_text)) },
            confirmButton = { TextButton(onClick = { actions.onHide(person); hiding = false; onBack() }, modifier = Modifier.testTag("hide-confirm")) { Text(stringResource(R.string.hide_person)) } },
            dismissButton = { TextButton(onClick = { hiding = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Asks for a name. Names that exist already are offered while typing; saving a name that belongs to somebody else asks "Same person?":
 * yes joins this person into that one (they keep their name), no keeps both with the same name.
 */
@Composable
fun NameDialog(person: Person, people: PeopleIndex, onDismiss: () -> Unit, onName: (String) -> Unit, onMerge: (into: Person) -> Unit) {
    var text by rememberSaveable(person.id) { mutableStateOf(person.name.orEmpty()) }
    var confirm by remember { mutableStateOf<Person?>(null) }
    val focus = remember { FocusRequester() }
    val typed = text.trim()
    val clash = if (typed.isNotEmpty()) people.namedLike(typed, except = person.id).firstOrNull() else null
    val suggestions = if (typed.isNotEmpty() && clash == null) people.suggestions(typed, except = person.id) else emptyList()
    fun save() {
        if (typed.isEmpty()) return
        if (clash != null) confirm = clash else onName(typed)
    }
    val asked = confirm
    if (asked != null) {
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.same_person_title, asked.name.orEmpty())) },
            text = { Text(stringResource(R.string.same_person_text, asked.name.orEmpty())) },
            confirmButton = { TextButton(onClick = { onMerge(asked) }, modifier = Modifier.testTag("merge-yes")) { Text(stringResource(R.string.same_person_yes)) } },
            dismissButton = { TextButton(onClick = { onName(typed) }, modifier = Modifier.testTag("merge-no")) { Text(stringResource(R.string.same_person_no)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (person.name.isNullOrBlank()) R.string.name_person else R.string.rename_person)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(100) }, singleLine = true,
                    label = { Text(stringResource(R.string.name_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("name-field"),
                )
                for (s in suggestions) {
                    TextButton(onClick = { text = s.name.orEmpty() }, modifier = Modifier.testTag("suggest-${s.id}")) { Text(s.name.orEmpty()) }
                }
                if (clash != null) Text(stringResource(R.string.name_exists, clash.name.orEmpty()), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = PhotoTheme.colors.textSecondary)
            }
        },
        confirmButton = { TextButton(onClick = { save() }, enabled = typed.isNotEmpty(), modifier = Modifier.testTag("name-save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The people the reader has hidden, each with a button to show them again. */
@Composable
fun HiddenPeoplePage(people: PeopleIndex, previews: Previews, loader: ImageLoader, onShow: (Person) -> Unit, onBack: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier.fillMaxSize().background(PhotoTheme.colors.background)) {
        if (people.hiddenPeople.isEmpty()) {
            Text(stringResource(R.string.no_hidden_people), Modifier.align(Alignment.Center), color = PhotoTheme.colors.textSecondary)
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("hidden-people"), contentPadding = PaddingValues(top = top + 72.dp, bottom = bottomPadding, start = 20.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(people.hiddenPeople, key = { it.id }) { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PersonAvatar(p, previews, loader, Modifier.size(56.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(personTitle(p), style = MaterialTheme.typography.titleMedium, color = PhotoTheme.colors.textPrimary)
                            Text(stringResource(R.string.photos_n, p.photoCount), style = MaterialTheme.typography.bodyMedium, color = PhotoTheme.colors.textSecondary)
                        }
                        OutlinedButton(onClick = { onShow(p) }, modifier = Modifier.testTag("show-${p.id}")) { Text(stringResource(R.string.show_person)) }
                    }
                }
            }
        }
        PageHeader(stringResource(R.string.hidden_people), onBack, Modifier.align(Alignment.TopStart).padding(top = top))
    }
}
