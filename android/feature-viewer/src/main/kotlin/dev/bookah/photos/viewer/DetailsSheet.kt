package dev.bookah.photos.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.bookah.photos.data.AssetDetails
import dev.bookah.photos.data.Formats
import dev.bookah.photos.design.PhotoTheme
import java.util.Locale

/** The details sheet of spec 3.6: caption row, date card, camera card, place. Everything is read-only in Phase 2. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(fileId: Long, env: ViewerEnv, locale: Locale, onDismiss: () -> Unit) {
    var details by remember(fileId) { mutableStateOf<AssetDetails?>(null) }
    LaunchedEffect(fileId) { details = env.details(fileId) }
    val greek = locale.language == "el"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false), containerColor = PhotoTheme.colors.background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val d = details
            if (d == null) {
                Text("…", color = PhotoTheme.colors.textSecondary)
                return@Column
            }
            val c = PhotoTheme.colors
            // place (a map comes with the map phase; until then the place name)
            d.place(greek)?.let { place ->
                Card { Row(Modifier.padding(16.dp)) { Text(place, style = MaterialTheme.typography.titleMedium, color = c.textPrimary) } }
            }
            // caption (read-only)
            Card { Text(stringResource(R.string.no_caption), Modifier.padding(16.dp), color = c.textSecondary, style = MaterialTheme.typography.bodyLarge) }
            // date card
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        Formats.dateTimeFull(d.takenUtc, d.offsetMin, locale) ?: stringResource(R.string.unknown_date),
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = c.textPrimary,
                    )
                    if (d.zoneAssumed && d.takenUtc != null) Text(stringResource(R.string.details_date_assumed), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                    Text(d.fileName, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    val where = when {
                        d.phoneOnly -> stringResource(R.string.details_phone_only)
                        d.onPhone -> stringResource(R.string.details_on_both)
                        else -> stringResource(R.string.details_in_nextcloud)
                    }
                    Text(where, style = MaterialTheme.typography.labelMedium, color = if (d.phoneOnly) c.accent else c.textSecondary)
                    if (d.notIndexed) Text(stringResource(R.string.details_not_indexed), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                    d.folder?.let { Text(stringResource(R.string.details_folder, it), style = MaterialTheme.typography.labelMedium, color = c.textSecondary) }
                    if (d.phoneOnly) {
                        Text(
                            stringResource(R.string.back_up_now), Modifier.padding(top = 4.dp).clip(RoundedCornerShape(16.dp)).background(c.accent).clickable { env.onBackUp(d.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                }
            }
            // camera card
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.camera ?: typeLabel(d), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                        Text(formatBadge(d), style = MaterialTheme.typography.labelMedium, color = c.textSecondary,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.background).padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    d.lens?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary) }
                    val facts = listOfNotNull(
                        Formats.megapixels(d.width, d.height),
                        if (d.width != null && d.height != null) "${d.width} × ${d.height}" else null,
                        Formats.size(d.size),
                        d.durationMs?.takeIf { it > 0 }?.let { Formats.duration(it) },
                    )
                    Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    HorizontalDivider(color = c.hairline)
                    val e = d.exposure
                    val dash = stringResource(R.string.dash)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (v in listOf(Formats.iso(e?.iso), Formats.focal(e?.focalLength), null, Formats.aperture(e?.fNumber), Formats.shutter(e?.exposureTime))) {
                            Text(v ?: dash, style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(PhotoTheme.colors.surface)) { content() }
}

private fun typeLabel(d: AssetDetails) = if (d.isVideo) "Video" else "Photo"

/** "JPEG", "HEIF", "PNG", "MOV" from the stored type. */
private fun formatBadge(d: AssetDetails): String = when (d.mime) {
    "image/jpeg" -> "JPEG"
    "image/heic", "image/heif" -> "HEIF"
    "image/png" -> "PNG"
    "image/tiff" -> "TIFF"
    "image/x-dcraw" -> "RAW"
    "video/quicktime" -> "MOV"
    "video/mp4" -> "MP4"
    else -> d.mime.substringAfter('/').uppercase()
}
