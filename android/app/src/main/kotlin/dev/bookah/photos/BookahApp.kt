package dev.bookah.photos

import android.app.Application
import dev.bookah.photos.data.CrashLog
import java.io.File

/** The app. Its one job: from the very start, write uncaught errors to a log on the phone (Settings > Problems can share it). */
class BookahApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(CrashLog(File(filesDir, "crash.log")), BuildConfig.VERSION_NAME, "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
    }
}
