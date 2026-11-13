package dev.bookah.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Thumbnails for photos that Nextcloud cannot make a preview of (about 2% of the library: some HEIC files, JPEGs named .heic, unusual files).
 * The original is downloaded once, shrunk to a small square JPEG that is kept in the cache folder, and the original is thrown away, so the
 * next time the thumbnail is a tiny local file. A file that cannot be decoded (for example a camera RAW named .jpg) is remembered and not tried again.
 */
class FallbackThumbs(context: Context, private val fetch: (String) -> java.io.InputStream?, private val originalUrl: suspend (Long) -> String?) {
    constructor(context: Context, client: OkHttpClient, originalUrl: suspend (Long) -> String?) : this(
        context,
        { url ->
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            if (resp.isSuccessful) object : java.io.FilterInputStream(resp.body.byteStream()) { override fun close() { super.close(); resp.close() } } else { resp.close(); null }
        },
        originalUrl,
    )

    private val dir = File(context.cacheDir, "fallback-thumbs").also { it.mkdirs() }
    private val gate = Semaphore(2)          // at most two originals at a time: gentle on the server and on mobile data

    suspend fun get(fileId: Long, sizePx: Int): File? = withContext(Dispatchers.IO) {
        val done = File(dir, "$fileId-$sizePx.jpg")
        if (done.isFile) return@withContext done
        if (File(dir, "$fileId.fail").exists()) return@withContext null
        gate.withPermit {
            if (done.isFile) return@withPermit done
            val url = originalUrl(fileId) ?: return@withPermit null
            val tmp = File(dir, "$fileId.part")
            try {
                val input = fetch(url) ?: return@withPermit null
                tmp.outputStream().use { out -> input.use { it.copyTo(out) } }
                val bitmap = decodeSquare(tmp, sizePx)
                if (bitmap == null) {
                    File(dir, "$fileId.fail").writeText("x")
                    return@withPermit null
                }
                done.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                bitmap.recycle()
                done
            } catch (e: java.io.IOException) {
                null          // a network hiccup: try again next time
            } finally {
                tmp.delete()
            }
        }
    }

    /** The middle square of the picture at [sizePx], decoded with a sample size so a 48 MP photo never fills memory. */
    private fun decodeSquare(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        if (square !== decoded) decoded.recycle()
        val scaled = if (side > sizePx) Bitmap.createScaledBitmap(square, sizePx, sizePx, true) else square
        if (scaled !== square) square.recycle()
        return scaled
    }
}
