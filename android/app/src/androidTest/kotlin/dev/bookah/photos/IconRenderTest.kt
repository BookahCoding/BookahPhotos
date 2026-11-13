package dev.bookah.photos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Draws the launcher icon the way Android does (with the system's icon mask) and writes a preview sheet, so the icon can be looked at: full colour on a light
 * and a dark background, and the themed single-colour version. It also checks that the icon has all three layers.
 * Pull the picture with: adb pull /sdcard/Android/data/dev.bookah.photos/files/icon-preview.png
 */
class IconRenderTest {
    @Test fun theLauncherIconHasThreeLayersAndRendersToAPreview() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = ctx.packageManager.getApplicationIcon(ctx.packageName)
        assertTrue("the launcher icon is an adaptive icon", icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.background); assertNotNull(icon.foreground)
        assertNotNull("a themed (monochrome) layer is present", icon.monochrome)

        val s = 432
        val pad = 48
        val sheet = Bitmap.createBitmap(s * 3 + pad * 4, s + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.parseColor("#F2F2F5"))
        fun tile(x: Int, bg: Int, draw: (Canvas) -> Unit) {
            val p = android.graphics.Paint().apply { color = bg }
            c.drawRoundRect(x.toFloat() - 24, pad.toFloat() - 24, (x + s + 24).toFloat(), (pad + s + 24).toFloat(), 40f, 40f, p)
            c.save(); c.translate(x.toFloat(), pad.toFloat()); draw(c); c.restore()
        }
        // 1: full colour on a light screen. 2: on a dark screen. 3: themed, tinted like a wallpaper colour would.
        tile(pad, Color.WHITE) { icon.setBounds(0, 0, s, s); icon.draw(it) }
        tile(pad * 2 + s, Color.parseColor("#0B0B0D")) { icon.setBounds(0, 0, s, s); icon.draw(it) }
        tile(pad * 3 + s * 2, Color.parseColor("#D7E3FF")) {
            val mono = icon.monochrome!!.mutate()
            mono.setTint(Color.parseColor("#1B3A7A"))
            val frame = AdaptiveIconDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#BBD0FF")), mono)
            frame.setBounds(0, 0, s, s); frame.draw(it)
        }
        val out = File(ctx.getExternalFilesDir(null), "icon-preview.png")
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 10_000)
    }
}
