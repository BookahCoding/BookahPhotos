package dev.bookah.photos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** How much of the phone's photos Android lets the app read. */
enum class LocalAccess {
    /** Not allowed (yet). */
    None,
    /** Android 14 and later: the reader chose "selected photos" only, so the library would miss the rest. */
    Partial,
    /** All photos and videos. */
    Full,
    ;

    companion object {
        /** The permissions to ask for on this Android version. */
        fun required(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        fun current(context: Context): LocalAccess {
            fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            return when {
                Build.VERSION.SDK_INT >= 33 -> when {
                    granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) -> Full
                    Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Partial
                    granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) -> Partial
                    else -> None
                }
                else -> if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) Full else None
            }
        }
    }
}
