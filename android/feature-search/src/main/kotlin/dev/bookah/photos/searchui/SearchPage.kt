package dev.bookah.photos.searchui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.design.PhotoTheme
import dev.bookah.photos.library.PhotoImage
import dev.bookah.photos.library.Previews
import dev.bookah.photos.library.RoundButton
import dev.bookah.photos.search.ModelUi
import dev.bookah.photos.search.SearchActions
import dev.bookah.photos.search.SearchBlock
import dev.bookah.photos.search.SearchUi
import dev.bookah.photos.search.TagLabel

/**
 * The search page: a field, the category chips, recent searches and, when the search model is not on the phone yet, a card that offers the one-time
 * download. A question shows the matching photos in the usual square grid; tapping one opens the viewer through exactly these results.
 * The first Back clears the results, the second leaves the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchPage(
    ui: SearchUi, lib: LibraryIndex, previews: Previews, loader: ImageLoader, actions: SearchActions, greek: Boolean,
    onOpen: (index: Int, orderIds: LongArray) -> Unit, onBack: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier,
) {
    val c = PhotoTheme.colors
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val results = ui.results
    LaunchedEffect(Unit) { if (ui.results == null) runCatching { focus.requestFocus() } }
    BackHandler(enabled = results != null) { actions.clearResults() }

    val indices = remember(results, lib) { results?.let { lib.indicesOfIds(it.ids.asList()) } }
    fun submit(q: String) {
        text = q
        keyboard?.hide()
        if (ui.canSearch) actions.submit(q)
    }

    Box(modifier.fillMaxSize().background(c.background)) {
        LazyVerticalGrid(
            GridCells.Fixed(4), Modifier.fillMaxSize().testTag("search-grid"),
            contentPadding = PaddingValues(top = top + 76.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (indices != null && results != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(results.title, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.search_results_n, indices.size), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    }
                }
                if (indices.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.search_nothing), Modifier.padding(20.dp), color = c.textSecondary) }
                } else {
                    items(indices.size) { n ->
                        val i = indices[n]
                        PhotoImage(i, lib, 256, previews, loader, Modifier.aspectRatio(1f).testTag("search-tile-$n").clickable { onOpen(i, results.ids) })
                    }
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        ModelCard(ui, actions)
                        if (ui.searching) Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.search_searching), color = c.textSecondary)
                        }
                        if (ui.recents.isNotEmpty()) Section(stringResource(R.string.search_recent), action = stringResource(R.string.search_clear), onAction = actions::clearRecents) {
                            Column {
                                for (r in ui.recents) Text(r, Modifier.fillMaxWidth().clickable { submit(r) }.padding(vertical = 10.dp), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (ui.canSearch) Section(stringResource(R.string.search_try)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (e in listOf(R.string.search_example_1, R.string.search_example_2, R.string.search_example_3)) {
                                    val q = stringResource(e)
                                    Chip(q, null) { submit(q) }
                                }
                            }
                        }
                        if (ui.labels.isNotEmpty()) Section(stringResource(R.string.search_categories)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (l in ui.labels) Chip(l.name(greek), l.count) { text = ""; keyboard?.hide(); actions.pickCategory(l, greek) }
                            }
                        }
                    }
                }
            }
        }

        // the field on top
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = top).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.search_back), { if (results != null) actions.clearResults() else onBack() })
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier.weight(1f).height(48.dp).clip(CircleShape).background(c.surface).padding(start = 14.dp, end = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = c.textSecondary)
                Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(stringResource(R.string.search_hint), color = c.textSecondary, maxLines = 1)
                    BasicTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textPrimary), cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit(text) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("search-field"),
                    )
                }
                if (text.isNotEmpty()) {
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable { text = ""; actions.clearResults() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, stringResource(R.string.search_clear), Modifier.size(18.dp), tint = c.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, action: String? = null, onAction: () -> Unit = {}, content: @Composable () -> Unit) {
    val c = PhotoTheme.colors
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            if (action != null) TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 56.dp)) { Text(action) }
        }
        content()
    }
}

@Composable
private fun Chip(label: String, count: Int?, onClick: () -> Unit) {
    val c = PhotoTheme.colors
    Row(
        Modifier.heightIn(min = 48.dp).clip(CircleShape).background(c.surface).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
        if (count != null) { Spacer(Modifier.width(6.dp)); Text(count.toString(), style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
    }
}

/** The one-time download, its progress, or why content search is not available (in words). The chips below it keep working without the model. */
@Composable
private fun ModelCard(ui: SearchUi, actions: SearchActions) {
    val c = PhotoTheme.colors
    val context = LocalContext.current
    fun size(b: Long) = android.text.format.Formatter.formatFileSize(context, b)
    val text: String?
    var button: Pair<String, () -> Unit>? = null
    var progress: Float? = null
    var busy = false
    when (val m = ui.model) {
        ModelUi.Checking -> { text = null }
        ModelUi.NotPublished -> text = stringResource(R.string.model_not_published)
        is ModelUi.Available -> { text = stringResource(R.string.model_available, size(m.sizeBytes)); button = stringResource(R.string.model_download) to actions::downloadModel }
        is ModelUi.Downloading -> { text = stringResource(R.string.model_downloading, size(m.done), size(m.total)); progress = if (m.total > 0) m.done.toFloat() / m.total else 0f }
        ModelUi.Loading -> { text = stringResource(R.string.model_loading); busy = true }
        ModelUi.Ready -> text = when (ui.blocked) {
            SearchBlock.NoVectors -> stringResource(R.string.blocked_no_vectors)
            SearchBlock.WrongModel -> stringResource(R.string.blocked_wrong_model)
            else -> null
        }
        is ModelUi.Failed -> { text = stringResource(R.string.model_failed, m.reason); button = stringResource(R.string.model_retry) to actions::retryModel }
    }
    if (text == null) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(16.dp).testTag("model-card"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ui.model is ModelUi.Available || ui.model is ModelUi.Downloading) Text(stringResource(R.string.model_title), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (button != null) TextButton(onClick = button.second, modifier = Modifier.heightIn(min = 48.dp)) { Text(button.first) }
    }
}
