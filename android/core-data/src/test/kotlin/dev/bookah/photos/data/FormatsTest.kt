package dev.bookah.photos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class FormatsTest {
    private val en = Locale.ENGLISH

    @Test fun `date and time use the photo's own offset`() {
        // 2024-08-24 08:46:33 UTC is 11:46 at +03:00
        val t = 1_724_489_193L
        assertEquals("24 August 2024", Formats.dateLong(t, 180, en))
        assertEquals("11:46", Formats.time(t, 180, en))
        assertEquals("08:46", Formats.time(t, 0, en))
        // late in the evening in a zone far behind UTC the local day is still the day before
        assertEquals("23 August 2024", Formats.dateLong(t, -9 * 60, en))
    }

    @Test fun `unknown date gives null, not a made-up one`() {
        assertNull(Formats.dateLong(null, null, en))
        assertNull(Formats.time(null, 180, en))
        assertNull(Formats.dateTimeFull(null, null, en))
    }

    @Test fun `camera values are formatted like a photo app does`() {
        assertEquals("1/33 s", Formats.shutter(0.0303))
        assertEquals("2 s", Formats.shutter(2.0))
        assertEquals("ƒ/1.5", Formats.aperture(1.5))
        assertEquals("26 mm", Formats.focal(26.0))
        assertEquals("ISO 500", Formats.iso(500.0))
        assertNull(Formats.shutter(0.0)); assertNull(Formats.aperture(null)); assertNull(Formats.iso(0.0))
        assertEquals("12 MP", Formats.megapixels(4032, 3024))
        assertEquals("3.0 MP", Formats.megapixels(1170, 2532))
        assertNull(Formats.megapixels(null, 100))
        assertEquals("3.2 MB", Formats.size(3_400_000))
        assertEquals("0:05", Formats.duration(5_000))
        assertEquals("1:02:03", Formats.duration(3_723_000))
    }

    @Test fun `exposure json from the indexer is parsed and bad input is ignored`() {
        val e = Exposure.parse("""{"ExposureTime": 0.0303, "FNumber": 1.5, "ISO": 500, "FocalLength": 26, "Other": 1}""")!!
        assertEquals(500.0, e.iso!!, 0.0)
        assertEquals(1.5, e.fNumber!!, 0.0)
        assertNull(Exposure.parse(null))
        assertNull(Exposure.parse("not json"))
        assertNull(Exposure.parse(""))
        assertNull(Exposure.parse("""{"ISO": "x"}""")!!.iso)
    }
}
