package dev.bookah.photos.library

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.bookah.photos.data.ThumbHash
import dev.bookah.photos.design.PhotoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the app gets a preview picture of a photo from (Nextcloud's preview of a file id). */
interface Previews {
    /** A square, cropped preview for the grid. */
    fun url(fileId: Long, size: Int): String
    /** A preview with the photo's own proportions, for the viewer. */
    fun fitUrl(fileId: Long, size: Int): String = url(fileId, size)
    /** A small thumbnail made from the original, for files Nextcloud cannot make a preview of. Null when there is none (RAW, damaged, offline). */
    suspend fun fallbackThumb(fileId: Long, sizePx: Int): java.io.File? = null
}

/** Thumbnails of photos on this phone, made by the system (it keeps its own thumbnail cache, so scrolling stays smooth and videos work too). */
private val localThumbCache = object : LruCache<String, ImageBitmap>(500) {}

@Composable
fun LocalThumb(uri: String, sizePx: Int, modifier: Modifier = Modifier, fit: Boolean = false) {
    val context = LocalContext.current
    val key = "$sizePx|$uri"
    val bitmap by produceState(localThumbCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(Uri.parse(uri), android.util.Size(sizePx, sizePx), null).asImageBitmap() }.getOrNull()
            }?.also { localThumbCache.put(key, it) }
        }
    }
    bitmap?.let { Image(it, null, modifier.fillMaxSize(), contentScale = if (fit) ContentScale.Fit else ContentScale.Crop) }
}

/** Decoded placeholders (32 px at most, about 3 KB each), so scrolling back does not decode them again. */
private val placeholderCache = object : LruCache<Int, ImageBitmap>(4_000) {}

@Composable
fun rememberPlaceholder(key: Int, hash: ByteArray?): ImageBitmap? {
    if (hash == null) return null
    val cached = placeholderCache.get(key)
    val state = produceState(cached, key, hash) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                ThumbHash.decode(hash)?.let { Bitmap.createBitmap(it.argb, it.width, it.height, Bitmap.Config.ARGB_8888).asImageBitmap() }
            }?.also { placeholderCache.put(key, it) }
        }
    }
    return state.value
}

/** A square picture: the placeholder at once, the preview fades in over it. [sizePx] is the preview size asked from Nextcloud. */
@Composable
fun PhotoImage(
    index: Int,
    lib: dev.bookah.photos.data.LibraryIndex,
    sizePx: Int,
    previews: Previews,
    loader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    val localUri = lib.localUri[index]
    if (localUri != null) {
        // the phone has this photo: show the local copy, never download it
        Box(modifier.background(PhotoTheme.colors.surface)) { LocalThumb(localUri, sizePx) }
        return
    }
    val placeholder = rememberPlaceholder(lib.ids[index].hashCode(), lib.thumbhash[index])
    Box(modifier.background(PhotoTheme.colors.surface)) {
        if (placeholder != null) {
            Image(placeholder, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low)
        }
        if (!lib.isRaw[index]) {
            val id = lib.ids[index]
            val etag = lib.etagHash[index]
            val context = LocalContext.current
            val request = remember(id, etag, sizePx) {
                val key = "p$sizePx-$id-$etag"
                // while a bigger preview is made and loaded, show the smaller one that is probably already in memory (a cold 512 px preview
                // can take a moment on the server), then crossfade
                ImageRequest.Builder(context).data(previews.url(id, sizePx)).memoryCacheKey(key).diskCacheKey(key)
                    .placeholderMemoryCacheKey(if (sizePx > 256) "p256-$id-$etag" else null).crossfade(150).build()
            }
            // about 2% of the library has no preview in Nextcloud (some HEIC and big JPEG files, some videos): those cells get a quiet picture glyph
            var failed by remember(request) { mutableStateOf(false) }
            AsyncImage(request, null, loader, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { failed = true })
            // no Nextcloud preview: make a thumbnail from the original once (photos only), keep it, and show it
            val fallback by produceState<java.io.File?>(null, failed, id) {
                if (failed && !lib.isVideo(index) && !lib.isRaw[index] && id > 0) value = previews.fallbackThumb(id, sizePx)
            }
            fallback?.let { f -> AsyncImage(f, null, loader, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (failed && fallback == null && placeholder == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Box(Modifier.alpha(0.5f)) { LibraryGlyph(PhotoTheme.colors.textSecondary) } }
            }
        }
    }
}
