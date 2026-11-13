package dev.bookah.photos.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.data.MonthGroup
import dev.bookah.photos.design.PhotoTheme
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** One tile of the years or months views: a cover picture, an optional label top left, a rounded corner. */
@Composable
private fun CoverTile(
    lib: LibraryIndex, index: Int, label: String?, previews: Previews, loader: ImageLoader, radius: Dp, onClick: () -> Unit, modifier: Modifier = Modifier,
    labelSize: Int = 16,
) {
    Box(modifier.clip(RoundedCornerShape(radius)).clickable(onClick = onClick)) {
        PhotoImage(index, lib, 768, previews, loader, Modifier.fillMaxSize())
        if (label != null) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66000000), 0.4f to Color.Transparent)))
            Text(label, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 12.dp), color = Color.White, fontSize = labelSize.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Years: one large card per year (350 x 262 dp in the spec) with the year in white. The "Unknown date" card comes first. */
@Composable
fun YearsView(
    lib: LibraryIndex, previews: Previews, loader: ImageLoader, state: LazyListState,
    topPadding: Dp, bottomPadding: Dp, onYear: (firstIndex: Int) -> Unit, onUnknown: () -> Unit, modifier: Modifier = Modifier,
) {
    val unknown = stringResource(R.string.unknown_date)
    LazyColumn(
        modifier.fillMaxSize(), state = state,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topPadding, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (lib.undatedCount > 0) item(key = "unknown") {
            CoverTile(lib, lib.coverIn(0, lib.undatedCount), unknown, previews, loader, 24.dp, onUnknown, Modifier.fillMaxWidth().aspectRatio(350f / 262f), labelSize = 22)
        }
        items(lib.years.size, key = { lib.years[it].year }) { n ->
            val y = lib.years[n]
            CoverTile(lib, lib.coverIn(y.from, y.toExclusive), y.year.toString(), previews, loader, 24.dp, { onYear(y.from) }, Modifier.fillMaxWidth().aspectRatio(350f / 262f), labelSize = 24)
        }
    }
}

/**
 * Months: a heading per month and a mosaic of the busiest days. Four or more days: one large cover and three small tiles;
 * two or three days: equal tiles; one day: one wide tile. Each small tile shows its day number; a tap opens All at that day.
 */
@Composable
fun MonthsView(
    lib: LibraryIndex, previews: Previews, loader: ImageLoader, state: LazyListState, locale: Locale,
    topPadding: Dp, bottomPadding: Dp, onDay: (firstIndex: Int) -> Unit, onUnknown: () -> Unit, modifier: Modifier = Modifier,
) {
    val unknown = stringResource(R.string.unknown_date)
    LazyColumn(
        modifier.fillMaxSize(), state = state,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topPadding, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (lib.undatedCount > 0) item(key = "unknown") {
            Column {
                Text(unknown, style = MaterialTheme.typography.titleLarge, color = PhotoTheme.colors.textPrimary, modifier = Modifier.padding(bottom = 10.dp))
                CoverTile(lib, lib.coverIn(0, lib.undatedCount), null, previews, loader, 24.dp, onUnknown, Modifier.fillMaxWidth().aspectRatio(350f / 233f))
            }
        }
        items(lib.months.size, key = { lib.months[it].year * 100 + lib.months[it].month }) { n ->
            MonthBlock(lib, lib.months[n], previews, loader, locale, onDay)
        }
    }
}

@Composable
private fun MonthBlock(lib: LibraryIndex, m: MonthGroup, previews: Previews, loader: ImageLoader, locale: Locale, onDay: (Int) -> Unit) {
    val title = Month.of(m.month).getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { it.titlecase(locale) } + " " + m.year
    // the busiest days make the mosaic; they are shown in date order
    val top = m.days.sortedByDescending { it.count }.take(4).sortedBy { it.day }
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, color = PhotoTheme.colors.textPrimary, modifier = Modifier.padding(bottom = 10.dp))
        fun dayTile(d: dev.bookah.photos.data.DayGroup) = Triple(lib.coverIn(d.from, d.toExclusive), (d.day % 100).toString(), d.from)
        when {
            m.days.size >= 4 -> {
                val big = m.days.maxBy { it.count }
                val smalls = m.days.filter { it != big }.sortedByDescending { it.count }.take(3).sortedBy { it.day }
                val (bi, _, bf) = dayTile(big)
                CoverTile(lib, bi, (big.day % 100).toString(), previews, loader, 24.dp, { onDay(bf) }, Modifier.fillMaxWidth().aspectRatio(350f / 233f))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    smalls.forEach { d ->
                        val (i, label, f) = dayTile(d)
                        CoverTile(lib, i, label, previews, loader, 16.dp, { onDay(f) }, Modifier.weight(1f).aspectRatio(1f), labelSize = 14)
                    }
                }
            }
            m.days.size >= 2 -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                top.forEach { d ->
                    val (i, label, f) = dayTile(d)
                    CoverTile(lib, i, label, previews, loader, 20.dp, { onDay(f) }, Modifier.weight(1f).aspectRatio(0.9f), labelSize = 16)
                }
            }
            else -> {
                val d = m.days.first()
                val (i, label, f) = dayTile(d)
                CoverTile(lib, i, label, previews, loader, 24.dp, { onDay(f) }, Modifier.fillMaxWidth().aspectRatio(350f / 233f))
            }
        }
    }
}
