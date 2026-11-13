package dev.bookah.photos

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.bookah.photos.data.Session
import dev.bookah.photos.library.Previews
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/** Adds the app password only to requests for the signed-in server, never to other hosts. */
private class AuthInterceptor(private val host: String, private val header: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        return if (req.url.host == host) chain.proceed(req.newBuilder().header("Authorization", header).build()) else chain.proceed(req)
    }
}

/** Thumbnails come from Nextcloud's preview of a file id (verified in Phase 0). */
fun previewsFor(session: Session, fallback: FallbackThumbs? = null) = object : Previews {
    override suspend fun fallbackThumb(fileId: Long, sizePx: Int) = fallback?.get(fileId, sizePx)
    override fun url(fileId: Long, size: Int) = "${session.server}/index.php/core/preview?fileId=$fileId&x=$size&y=$size&a=true&mode=cover"
    override fun fitUrl(fileId: Long, size: Int) = "${session.server}/index.php/core/preview?fileId=$fileId&x=$size&y=$size&a=true"
}

/** The shared client with the app password added for the signed-in server (used for pictures and video). */
fun authedClient(base: OkHttpClient, session: Session): OkHttpClient {
    val host = session.server.substringAfter("://").substringBefore('/').substringBefore(':')
    return base.newBuilder().addInterceptor(AuthInterceptor(host, session.authHeader)).build()
}

fun buildImageLoader(context: Context, client: OkHttpClient): ImageLoader {
    return ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
        .memoryCache { MemoryCache.Builder().maxSizeBytes(160L * 1024 * 1024).build() }
        // capped disk cache; the 2 GB default of the plan, adjustable in Settings later
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("thumbs").toOkioPath()).maxSizeBytes(2L * 1024 * 1024 * 1024).build() }
        .build()
}
