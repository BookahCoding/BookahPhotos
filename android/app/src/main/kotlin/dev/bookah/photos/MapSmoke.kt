package dev.bookah.photos

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import coil3.ImageLoader
import dev.bookah.photos.data.AssetRow
import dev.bookah.photos.data.LibraryIndex
import dev.bookah.photos.library.Previews
import dev.bookah.photos.map.MapPage
import dev.bookah.photos.map.MapPoint

/**
 * A check for developers, not a feature: `adb shell am start -n dev.bookah.photos/.MainActivity --ez map_smoke true` shows the map with made-up photos, without a
 * sign-in. It exists only in a build made with `-PmapSmoke=1` (a published release never is), so a minified build can be run on an emulator.
 */
object MapSmoke {
    fun wanted(extra: Boolean) = BuildConfig.MAP_SMOKE && extra

    @Composable
    fun Show(context: Context, onBack: () -> Unit) {
        val lib = remember { LibraryIndex.build((0 until 20).map { AssetRow(100L + it, 20240101 + it, it.toLong(), 0, null, it, false) }) }
        val points = remember { listOf(MapPoint(1, 101, 37.9838, 23.7275), MapPoint(2, 102, 37.9838, 23.7275), MapPoint(3, 103, 39.62, 19.92), MapPoint(4, 104, 35.34, 25.14)) }
        val previews = remember { object : Previews { override fun url(fileId: Long, size: Int) = "http://127.0.0.1:9/p/$fileId" } }
        val loader = remember { ImageLoader.Builder(context).build() }
        MapPage(points, lib, previews, loader, BuildConfig.VERSION_NAME, onOpen = {}, onBack = onBack)
    }
}

/**
 * Developer check for the unlock prompt and the secure window, on an emulator with a PIN: `--ez hidden_smoke true`. A button asks for the unlock; after it, a
 * made-up "hidden" screen is shown with the window marked secure. Only in a build made with `-PmapSmoke=1`.
 */
object HiddenSmoke {
    fun wanted(extra: Boolean) = BuildConfig.MAP_SMOKE && extra

    @Composable
    fun Show(activity: android.app.Activity) {
        var result by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<UnlockResult?>(null) }
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().padding(40.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            androidx.compose.material3.Button(onClick = { PlatformUnlocker(activity).unlock("Hidden photos", "Unlock to see the photos you hid") { result = it } }, modifier = androidx.compose.ui.Modifier.testTag("open")) {
                androidx.compose.material3.Text("Open hidden")
            }
            androidx.compose.material3.Text("RESULT: " + (result?.name ?: "none"))
            if (result == UnlockResult.Success) androidx.compose.material3.Text("SECRET CONTENT SHOWN HERE")
        }
        SecureWindow(result == UnlockResult.Success)
    }
}
