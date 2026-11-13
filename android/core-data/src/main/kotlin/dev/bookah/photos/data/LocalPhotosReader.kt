package dev.bookah.photos.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Reads the photos and videos of this phone through MediaStore (read only: nothing on the phone is changed, moved or deleted).
 * Needs the media permission; without it the query simply returns what Android allows, possibly nothing.
 */
object LocalPhotosReader {
    fun read(context: Context): List<LocalRow> {
        val resolver = context.contentResolver
        return query(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false) +
            query(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)
    }

    private fun query(resolver: ContentResolver, base: Uri, isVideo: Boolean): List<LocalRow> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME); add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_TAKEN); add(MediaStore.MediaColumns.MIME_TYPE); add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT); add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            if (isVideo) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        val out = ArrayList<LocalRow>(4096)
        // files that are still being written or are in the trash are not photos yet / any more
        val selection = "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.IS_TRASHED} = 0"
        try {
            resolver.query(base, projection, android.os.Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            }, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val iDur = if (isVideo) c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION) else -1
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    val id = c.getLong(iId)
                    out.add(LocalRow(
                        mediaId = id,
                        uri = ContentUris.withAppendedId(base, id).toString(),
                        name = name,
                        size = c.getLong(iSize),
                        dateTakenMs = if (c.isNull(iTaken)) 0L else c.getLong(iTaken),
                        mime = c.getString(iMime) ?: if (isVideo) "video/*" else "image/*",
                        width = if (c.isNull(iW)) 0 else c.getInt(iW),
                        height = if (c.isNull(iH)) 0 else c.getInt(iH),
                        durationMs = if (iDur >= 0 && !c.isNull(iDur)) c.getInt(iDur) else 0,
                        folder = c.getString(iBucket) ?: "",
                        isVideo = isVideo,
                    ))
                }
            }
        } catch (e: SecurityException) {
            // no permission yet: the library simply has no local photos
        }
        return out
    }

}
