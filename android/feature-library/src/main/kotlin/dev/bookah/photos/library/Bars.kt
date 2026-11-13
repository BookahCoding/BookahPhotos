package dev.bookah.photos.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.bookah.photos.data.Source
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import dev.bookah.photos.design.PhotoTheme

enum class LibraryMode { Years, Months, All }

enum class Tab { Library, Collections }

/** A floating round button, 44 dp (spec section 2). */
@Composable
fun RoundButton(icon: ImageVector?, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Int = 44, enabled: Boolean = true, dark: Boolean = false, tint: Color? = null, custom: (@Composable () -> Unit)? = null) {
    val c = PhotoTheme.colors
    val fill = if (dark) Color(0xB31C1C1F) else c.barFill
    val line = if (dark) Color(0x1FFFFFFF) else c.hairline
    val iconTint = tint ?: if (dark) Color.White else c.textPrimary
    Box(
        modifier.size(size.dp).shadow(if (dark) 0.dp else 6.dp, CircleShape).clip(CircleShape).background(fill).border(BorderStroke(1.dp, line), CircleShape)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = iconTint) else custom?.invoke()
    }
}

/** A floating pill with text (for example "Select"). */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = PhotoTheme.colors
    Box(
        modifier.testTag("pill-button").height(44.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(c.barFill).border(BorderStroke(1.dp, c.hairline), CircleShape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleMedium, color = c.textPrimary) }
}

/** Our own mark for the Library button: two stacked rounded frames (drawn, so no icon pack is needed). */
@Composable
fun LibraryGlyph(tint: Color = PhotoTheme.colors.accent) {
    Canvas(Modifier.size(24.dp)) {
        val s = Stroke(width = 2.dp.toPx())
        drawRoundRect(tint, Offset(5.dp.toPx(), 2.dp.toPx()), Size(15.dp.toPx(), 12.dp.toPx()), CornerRadius(3.dp.toPx()), style = s)
        drawRoundRect(tint, Offset(2.dp.toPx(), 7.dp.toPx()), Size(15.dp.toPx(), 12.dp.toPx()), CornerRadius(3.dp.toPx()), style = s)
    }
}

/** A small phone outline: marks a photo that exists only on this phone. */
@Composable
fun PhoneGlyph(tint: Color = PhotoTheme.colors.textSecondary, size: Int = 14) {
    Canvas(Modifier.size(size.dp)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(tint, Offset(w * 0.22f, 0f), Size(w * 0.56f, h), CornerRadius(w * 0.14f), style = Stroke(width = 1.6.dp.toPx()))
        drawCircle(tint, radius = 1.1.dp.toPx(), center = Offset(w / 2f, h * 0.84f))
    }
}

/** Library bottom bar: round Library button, the Years / Months / All pill, round Search (inactive until the search phase). */
@Composable
fun LibraryBottomBar(mode: LibraryMode, onMode: (LibraryMode) -> Unit, onLibraryButton: () -> Unit, modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null) {
    val c = PhotoTheme.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(null, stringResource(R.string.library), onLibraryButton, size = 48) { LibraryGlyph() }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(
                Modifier.testTag("library-pill").height(48.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(c.barFill).border(BorderStroke(1.dp, c.hairline), CircleShape).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((m, label) in listOf(LibraryMode.Years to R.string.years, LibraryMode.Months to R.string.months, LibraryMode.All to R.string.all)) {
                    val sel = m == mode
                    Box(
                        Modifier.height(40.dp).width(68.dp).clip(CircleShape).background(if (sel) c.surface else Color.Transparent)
                            .clickable(role = Role.Tab) { onMode(m) },
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = c.textPrimary) }
                }
            }
        }
        RoundButton(Icons.Filled.Search, stringResource(R.string.search), { onSearch?.invoke() }, size = 48, enabled = onSearch != null, modifier = Modifier.testTag("search-button"))
    }
}

/** Select-mode bottom bar: Share, the count, and nothing else in Phase 2 (delete arrives in Phase 6). */
@Composable
fun SelectBottomBar(count: Int, onShare: (() -> Unit)?, onDelete: (() -> Unit)?, modifier: Modifier = Modifier, onUnhide: (() -> Unit)? = null, onHide: (() -> Unit)? = null, onFavourite: (() -> Unit)? = null, favouriteClears: Boolean = false, onAddToAlbum: (() -> Unit)? = null) {
    val c = PhotoTheme.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onShare != null) RoundButton(Icons.Filled.Share, stringResource(R.string.share), onShare, size = 48, enabled = count > 0) else Spacer(Modifier.size(48.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                Modifier.height(48.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(c.barFill).border(BorderStroke(1.dp, c.hairline), CircleShape).padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.selected_n, count), style = MaterialTheme.typography.titleMedium, color = c.textPrimary) }
        }
        if (onAddToAlbum != null) { RoundButton(Icons.Filled.Add, stringResource(R.string.add_to_album), onAddToAlbum, size = 48, enabled = count > 0, modifier = Modifier.testTag("select-add-album")); Spacer(Modifier.width(8.dp)) }
        if (onFavourite != null) { RoundButton(if (favouriteClears) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, stringResource(if (favouriteClears) R.string.unfavourite else R.string.favourite), onFavourite, size = 48, enabled = count > 0, modifier = Modifier.testTag("select-favourite")); Spacer(Modifier.width(8.dp)) }
        if (onHide != null) { RoundButton(Icons.Filled.Lock, stringResource(R.string.hide), onHide, size = 48, enabled = count > 0); Spacer(Modifier.width(8.dp)) }
        if (onUnhide != null) PillButton(stringResource(R.string.unhide), onUnhide, Modifier.testTag("select-unhide"))
        if (onDelete != null) RoundButton(Icons.Filled.Delete, stringResource(R.string.delete), onDelete, size = 48, enabled = count > 0, tint = Color(0xFFE5484D)) else if (onUnhide == null) Spacer(Modifier.size(48.dp))
    }
}

/** The large title with the date range under it, the source filter and the Select button (spec 3.1). A scrim lets the grid scroll under it. */
@Composable
fun LibraryHeader(
    title: String, subtitle: String, selecting: Boolean, onSelect: () -> Unit, onDone: () -> Unit, onTitleLongPress: () -> Unit,
    source: Source, onSource: (Source) -> Unit, showSource: Boolean, modifier: Modifier = Modifier,
) {
    val c = PhotoTheme.colors
    var menu by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(c.background.copy(alpha = 0.96f), c.background.copy(alpha = 0.85f), Color.Transparent)))) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineLarge, color = c.textPrimary, modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onTitleLongPress() }) })
                Text(subtitle, Modifier.testTag("subtitle"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.textSecondary)
            }
            if (selecting) {
                RoundButton(Icons.Filled.Close, stringResource(R.string.done), onDone)
            } else {
                if (showSource) {
                    Box {
                        PillButton(sourceLabel(source), { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            for (s in Source.entries) {
                                DropdownMenuItem(text = { Text(sourceLabel(s), fontWeight = if (s == source) FontWeight.Bold else FontWeight.Normal) }, onClick = { menu = false; onSource(s) })
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                PillButton(stringResource(R.string.select), onSelect)
            }
        }
    }
}

@Composable
fun sourceLabel(source: Source): String = stringResource(
    when (source) {
        Source.All -> R.string.source_all
        Source.Nextcloud -> R.string.source_nextcloud
        Source.Phone -> R.string.source_phone
    },
)

/**
 * The normal bottom bar (spec section 2): a floating pill with two tabs, Library and Collections, and a separate round Search button
 * (inactive until the search phase). Shown on Collections and when the round Library button is pressed in the Library.
 */
@Composable
fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier, libraryLabel: String, collectionsLabel: String, searchLabel: String, onSearch: (() -> Unit)? = null) {
    val c = PhotoTheme.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.testTag("tabbar-pill").height(60.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(c.barFill).border(BorderStroke(1.dp, c.hairline), CircleShape).padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((tab, label) in listOf(Tab.Library to libraryLabel, Tab.Collections to collectionsLabel)) {
                val sel = tab == selected
                Box(
                    Modifier.height(50.dp).width(94.dp).clip(CircleShape).background(if (sel) c.surface else Color.Transparent)
                        .clickable(role = Role.Tab) { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = if (sel) c.accent else c.textPrimary)
                }
            }
        }
        Box(Modifier.weight(1f))
        RoundButton(Icons.Filled.Search, searchLabel, { onSearch?.invoke() }, size = 60, enabled = onSearch != null, modifier = Modifier.testTag("search-button"))
    }
}
