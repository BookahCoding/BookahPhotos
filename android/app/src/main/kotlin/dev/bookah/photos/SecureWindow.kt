package dev.bookah.photos

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * While [enabled], the window is marked secure: Android shows it as black in screenshots, screen recordings and the recent apps list. Used while hidden
 * photos are on screen.
 */
@Composable
fun SecureWindow(enabled: Boolean) {
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(enabled) {
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (enabled) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
