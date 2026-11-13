package dev.bookah.photos.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.bookah.photos.data.Album
import dev.bookah.photos.data.PeopleIndex
import dev.bookah.photos.design.PhotoTheme
import dev.bookah.photos.library.RoundButton

/** The first tile of the Albums row: makes a new album. */
@Composable
fun NewAlbumTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(PhotoTheme.colors.surface).clickable(role = Role.Button, onClick = onClick).testTag("new-album-tile"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Add, null, Modifier.size(32.dp), tint = PhotoTheme.colors.accent)
        Text(stringResource(R.string.new_album), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium, color = PhotoTheme.colors.textPrimary)
    }
}

/** Asks for an album name (new album or rename). A name that exists already (ignoring case and accents) or is not usable cannot be saved. */
@Composable
fun AlbumNameDialog(title: String, initial: String, taken: Collection<String>, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val typed = text.trim()
    val usable = dev.bookah.photos.data.AlbumsApi.validName(typed) != null
    val clash = typed.isNotEmpty() && taken.any { PeopleIndex.foldName(it) == PeopleIndex.foldName(typed) && it.trim() != initial.trim() }
    fun save() { if (usable && !clash) onDone(dev.bookah.photos.data.AlbumsApi.validName(typed)!!) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(100) }, singleLine = true, label = { Text(stringResource(R.string.album_name_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("album-name-field"),
                )
                if (clash) Text(stringResource(R.string.album_exists), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = PhotoTheme.colors.textSecondary)
                else if (typed.isNotEmpty() && !usable) Text(stringResource(R.string.album_name_bad), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = PhotoTheme.colors.textSecondary)
            }
        },
        confirmButton = { TextButton(onClick = { save() }, enabled = usable && !clash, modifier = Modifier.testTag("album-name-save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Where to add the chosen photos: one of the albums, or a new one. */
@Composable
fun AlbumPickerDialog(albums: List<Album>, onPick: (Album) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_album)) },
        text = {
            Column {
                TextButton(onClick = onNew, modifier = Modifier.testTag("picker-new")) { Text(stringResource(R.string.new_album)) }
                LazyColumn(Modifier.height(280.dp).testTag("picker-list")) {
                    items(albums, key = { it.name }) { a ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 12.dp).testTag("picker-${a.name}"), verticalAlignment = Alignment.CenterVertically) {
                            Text(a.name, Modifier.weight(1f), color = PhotoTheme.colors.textPrimary, maxLines = 1)
                            Text(a.count.toString(), color = PhotoTheme.colors.textSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The menu of an album page: rename and delete the album. */
@Composable
fun AlbumMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        RoundButton(Icons.Filled.MoreVert, stringResource(R.string.menu), { open = true }, modifier = Modifier.testTag("album-menu"))
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.rename_album)) }, onClick = { open = false; onRename() }, modifier = Modifier.testTag("album-rename"))
            DropdownMenuItem({ Text(stringResource(R.string.delete_album)) }, onClick = { open = false; onDelete() }, modifier = Modifier.testTag("album-delete"))
        }
    }
}

@Composable
fun DeleteAlbumDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_album_title, name)) },
        text = { Text(stringResource(R.string.delete_album_text)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("album-delete-yes")) { Text(stringResource(R.string.delete_album)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
