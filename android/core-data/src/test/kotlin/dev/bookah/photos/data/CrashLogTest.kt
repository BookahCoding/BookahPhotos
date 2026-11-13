package dev.bookah.photos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class CrashLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a crash is written with its time, version, device and stack, and can be read and cleared`() {
        val log = CrashLog(java.io.File(tmp.root, "crash.log"))
        assertNull(log.read()); assertNull(log.lastTime())
        log.record("main", IllegalStateException("boom"), "1.0.1", "Test Phone, Android 16 (API 36)", Instant.parse("2026-11-13T20:00:00Z"))
        val text = log.read()!!
        assertTrue(text.contains("BookahPhotos 1.0.1") && text.contains("Test Phone") && text.contains("thread main") && text.contains("IllegalStateException: boom"))
        assertEquals(Instant.parse("2026-11-13T20:00:00Z"), log.lastTime())
        log.clear(); assertNull(log.read())
    }

    @Test fun `only the last five crashes are kept and the file stays small`() {
        val log = CrashLog(java.io.File(tmp.root, "crash.log"))
        for (i in 1..8) log.record("t", RuntimeException("crash number $i"), "1.0.0", "d", Instant.parse("2026-11-13T20:00:0${i}Z"))
        val text = log.read()!!
        assertFalse(text.contains("crash number 3")); assertTrue(text.contains("crash number 4") && text.contains("crash number 8"))
        assertEquals(5, Regex("(?m)^== ").findAll(text).count())
        assertEquals(Instant.parse("2026-11-13T20:00:08Z"), log.lastTime())
        log.record("t", RuntimeException("x".repeat(100_000)), "1.0.0", "d")
        assertTrue(java.io.File(tmp.root, "crash.log").length() < 80_000)
    }

    @Test fun `the handler writes the crash and then passes it on`() {
        val log = CrashLog(java.io.File(tmp.root, "crash.log"))
        val before = Thread.getDefaultUncaughtExceptionHandler()
        var passedOn: Throwable? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> passedOn = e }
            CrashLog.install(log, "1.0.0", "d")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalArgumentException("oops"))
            assertTrue(log.read()!!.contains("oops")); assertEquals("oops", passedOn!!.message)
        } finally { Thread.setDefaultUncaughtExceptionHandler(before) }
    }

    @Test fun `a log that cannot be written never throws`() {
        val unwritable = java.io.File(tmp.root, "dir").also { it.mkdir() }                      // a folder where a file is expected
        CrashLog(unwritable).record("t", RuntimeException("x"), "1", "d")
    }
}
