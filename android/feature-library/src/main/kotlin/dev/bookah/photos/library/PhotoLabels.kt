package dev.bookah.photos.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.bookah.photos.data.LibraryIndex
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What a screen reader says for a photo tile: "Photo, 12 Aug 2024" or "Video, 12 Aug 2024" (or without a date). A tile is a picture and has no text of its own,
 * so without this TalkBack would say nothing at all.
 */
@Composable
fun rememberPhotoLabeller(): (LibraryIndex, Int) -> String {
    val photoDated = stringResource(R.string.photo_label)
    val videoDated = stringResource(R.string.video_label)
    val photoUndated = stringResource(R.string.photo_label_undated)
    val videoUndated = stringResource(R.string.video_label_undated)
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    return remember(photoDated, videoDated, photoUndated, videoUndated, format) {
        { lib, i ->
            val video = lib.isVideo(i)
            val day = lib.day[i]
            if (day == 0) (if (video) videoUndated else photoUndated)
            else String.format(if (video) videoDated else photoDated, LibraryIndex.toDate(day).format(format))
        }
    }
}
