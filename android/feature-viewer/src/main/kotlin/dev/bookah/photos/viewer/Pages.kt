package dev.bookah.photos.viewer

import android.content.Context
import android.view.LayoutInflater
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.bookah.photos.library.LocalThumb
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import dev.bookah.photos.data.AssetDetails
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.library.rememberPlaceholder

/** Mime types Android can decode as originals; TIFF and RAW cannot be, so for them the large preview is the full picture. */
internal fun decodableOriginal(mime: String) = mime in setOf("image/jpeg", "image/png", "image/heic", "image/heif", "image/webp", "image/gif")

/**
 * One photo: the blurred placeholder at once, a 1024 px preview over it, and the original once the reader zooms in.
 * TIFF and RAW get a larger preview instead of the original. A file Nextcloud could not make a preview of says so plainly.
 */
@Composable
fun PhotoPage(
    index: Int, lib: LibraryIndex, env: ViewerEnv, active: Boolean,
    onZoomed: (Boolean) -> Unit, onTap: () -> Unit, modifier: Modifier = Modifier,
) {
    val zoom = remember { ZoomState() }
    LaunchedEffect(zoom.scale) { if (active) onZoomed(zoom.zoomed) }
    LaunchedEffect(active) { if (!active) zoom.reset() }
    val id = lib.ids[index]
    val etag = lib.etagHash[index]
    val context = LocalContext.current
    var details by remember(id) { mutableStateOf<AssetDetails?>(null) }
    var previewFailed by remember(id) { mutableStateOf(false) }
    // the original is only fetched once the reader has zoomed in
    LaunchedEffect(id, zoom.zoomed, previewFailed) { if ((zoom.zoomed || previewFailed) && details == null) details = env.details(id) }
    var originalFailed by remember(id) { mutableStateOf(false) }

    val localUri = lib.localUri[index]
    val large = lib.isRaw[index].not()
    val placeholder = rememberPlaceholder(id.hashCode(), lib.thumbhash[index])
    Box(
        modifier.fillMaxSize().clipToBounds().onSizeChanged { zoom.size = it }.zoomable(zoom, onTap),
        contentAlignment = Alignment.Center,
    ) {
        if (placeholder != null) Image(placeholder, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Low)
        if (localUri != null) {
            // the phone has this photo: show the local file, no network
            val localRequest = remember(localUri, zoom.zoomed) {
                ImageRequest.Builder(context).data(localUri).memoryCacheKey("loc-$localUri-${zoom.zoomed}")
                    .size(if (zoom.zoomed) Size(3000, 3000) else Size(1600, 1600)).crossfade(120).build()
            }
            AsyncImage(localRequest, null, env.loader, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onError = { previewFailed = true })
        } else if (large) {
            val key = "v1024-$id-$etag"
            val request = remember(id, etag) {
                ImageRequest.Builder(context).data(env.previews.fitUrl(id, 1024)).memoryCacheKey(key).diskCacheKey(key).crossfade(120).build()
            }
            AsyncImage(request, null, env.loader, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onError = { previewFailed = true })
            val d = details
            if (zoom.zoomed && d != null) {
                val request2 = remember(d.id, etag) {
                    if (decodableOriginal(d.mime)) {
                        ImageRequest.Builder(context).data(env.originalUrl(d.path)).memoryCacheKey("orig-$id-$etag").diskCacheKey("orig-$id-$etag")
                            .size(Size(3000, 3000)).crossfade(150).build()
                    } else {
                        // TIFF and the like: the largest preview Nextcloud makes
                        ImageRequest.Builder(context).data(env.previews.fitUrl(id, 2048)).memoryCacheKey("v2048-$id-$etag").diskCacheKey("v2048-$id-$etag")
                            .crossfade(150).build()
                    }
                }
                AsyncImage(request2, null, env.loader, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        // Nextcloud made no preview: show the original itself (HEIC, or a JPEG named .heic, decode fine on the phone)
        val dd = details
        if (previewFailed && localUri == null && dd != null && !originalFailed) {
            val fallbackRequest = remember(dd.id) {
                ImageRequest.Builder(context).data(env.originalUrl(dd.path)).memoryCacheKey("orig-view-$id-$etag").size(Size(2000, 2000)).crossfade(150).build()
            }
            AsyncImage(fallbackRequest, null, env.loader, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onError = { originalFailed = true })
        }
        if ((!large && localUri == null) || (previewFailed && (originalFailed || (dd == null && false)))) {
            Text(
                stringResource(R.string.no_preview), Modifier.align(Alignment.Center).padding(32.dp),
                color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** A video: the poster first, then ExoPlayer streaming the original from Nextcloud while this is the page on screen. */
@Composable
fun VideoPage(index: Int, lib: LibraryIndex, env: ViewerEnv, active: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val id = lib.ids[index]
    val etag = lib.etagHash[index]
    val context = LocalContext.current
    var details by remember(id) { mutableStateOf<AssetDetails?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var playing by remember(id) { mutableStateOf(false) }
    val localUri = lib.localUri[index]
    LaunchedEffect(id, active) { if (active && details == null && localUri == null) details = env.details(id) }
    val placeholder = rememberPlaceholder(id.hashCode(), lib.thumbhash[index])

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (placeholder != null) Image(placeholder, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Low)
        val key = "v1024-$id-$etag"
        val request = remember(id, etag) { ImageRequest.Builder(context).data(env.previews.fitUrl(id, 1024)).memoryCacheKey(key).diskCacheKey(key).crossfade(120).build() }
        if (!playing) {
            if (localUri != null) LocalThumb(localUri, 1024, Modifier, fit = true) else AsyncImage(request, null, env.loader, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        val d = details
        if (active && (d != null || localUri != null)) {
            val player = remember(id) { buildPlayer(context, env) }
            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { failed = true }
                    override fun onRenderedFirstFrame() { playing = true }
                }
                player.addListener(listener)
                player.setMediaItem(MediaItem.fromUri(localUri ?: env.originalUrl(d!!.path)))
                player.prepare()
                player.playWhenReady = true
                onDispose { player.removeListener(listener); player.release() }
            }
            AndroidView(
                factory = { ctx -> PlayerView(ctx).also { it.player = player; it.setBackgroundColor(android.graphics.Color.TRANSPARENT) } },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (active) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        if (failed) Text(stringResource(R.string.video_failed), Modifier.align(Alignment.Center).padding(32.dp), color = Color(0xCCFFFFFF))
    }
}

private fun buildPlayer(context: Context, env: ViewerEnv): ExoPlayer {
    // DefaultDataSource opens content:// addresses (photos on this phone) itself and hands http(s) to OkHttp (Nextcloud)
    val dataSource = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(env.okHttp))
    return ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource)).build()
}
