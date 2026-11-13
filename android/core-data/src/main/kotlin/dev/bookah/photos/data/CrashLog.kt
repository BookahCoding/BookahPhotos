package dev.bookah.photos.data

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * A log of the app's crashes that stays on the phone (no telemetry, nothing is sent anywhere): when the app closes by itself because of an error, the error is
 * written here, and Settings has a button to share the text by hand. At most [KEEP] crashes are kept, newest last.
 */
class CrashLog(private val file: File) {
    /** Writes one crash. Never throws: a failing log must not make a crash worse. */
    fun record(thread: String, error: Throwable, appVersion: String, device: String, now: Instant = Instant.now()) {
        runCatching {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().take(MAX_TRACE)
            val entry = "== ${now} | BookahPhotos $appVersion | $device | thread $thread\n$trace\n"
            val entries = split(runCatching { file.readText() }.getOrDefault("")) + entry
            file.parentFile?.mkdirs()
            file.writeText(entries.takeLast(KEEP).joinToString(""))
        }
    }

    /** All the crashes kept, or null when there are none. */
    fun read(): String? = runCatching { file.readText().takeIf { it.isNotBlank() } }.getOrNull()

    /** When the newest crash happened, for the line in Settings. */
    fun lastTime(): Instant? = read()?.let { Regex("^== (\\S+) \\|", RegexOption.MULTILINE).findAll(it).lastOrNull()?.groupValues?.get(1) }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun clear() { file.delete() }

    private fun split(text: String): List<String> = if (text.isBlank()) emptyList() else Regex("(?m)^== ").split(text).filter { it.isNotBlank() }.map { "== $it" }

    companion object {
        const val KEEP = 5
        const val MAX_TRACE = 12_000

        /** Makes this the place where uncaught errors are written, then lets Android do what it did before (the app closes). */
        fun install(log: CrashLog, appVersion: String, device: String) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                log.record(t.name, e, appVersion, device)
                previous?.uncaughtException(t, e)
            }
        }
    }
}
