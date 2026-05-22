package com.fredapp.wbooksutil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Decoder + set-request encoder for the watch-side settings sync. The decoder
 * is the side most likely to silently mis-handle a payload, since the watch's
 * encoder is the authoritative source. These tests pin the wire shape we expect.
 */
class SettingsJsonTest {

    private val fullJson = """{"mode":"NORMAL","font":"SERIF","textSizeSp":16,"sentenceTextSizeSp":22,"textColorArgb":-1517599,"autoscrollEnabled":false,"autoscrollSpeed":20,"screenBrightness":100,"speedreadWpm":300,"crashReportingEnabled":true}"""

    @Test
    fun decodes_full_payload() {
        val s = SettingsJson.decode(fullJson.toByteArray(Charsets.UTF_8))
        assertNotNull(s)
        s!!
        assertEquals("NORMAL", s.mode)
        assertEquals("SERIF", s.font)
        assertEquals(16, s.textSizeSp)
        assertEquals(22, s.sentenceTextSizeSp)
        assertEquals(-1517599, s.textColorArgb)
        assertEquals(false, s.autoscrollEnabled)
        assertEquals(20, s.autoscrollSpeed)
        assertEquals(100, s.screenBrightness)
        assertEquals(300, s.speedreadWpm)
        assertEquals(true, s.crashReportingEnabled)
    }

    @Test
    fun returns_null_when_crash_reporting_field_missing() {
        // Hand-built shape with one field stripped. The whole point of strict
        // decode: a truncated reply must NOT silently flip Sentry off.
        val partial = fullJson.replace(""","crashReportingEnabled":true""", "")
        assertNull(SettingsJson.decode(partial.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun returns_null_when_enum_field_missing() {
        val partial = fullJson.replace(""""mode":"NORMAL",""", "")
        assertNull(SettingsJson.decode(partial.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun returns_null_when_int_field_missing() {
        val partial = fullJson.replace(""","textSizeSp":16""", "")
        assertNull(SettingsJson.decode(partial.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun returns_null_on_empty_or_blank_payload() {
        assertNull(SettingsJson.decode(ByteArray(0)))
        assertNull(SettingsJson.decode("   ".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun returns_null_when_bool_field_unparseable() {
        val bad = fullJson.replace("\"crashReportingEnabled\":true", "\"crashReportingEnabled\":maybe")
        assertNull(SettingsJson.decode(bad.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun encode_set_request_uses_string_value_form() {
        val intReq = String(SettingsJson.encodeSetRequest("textSizeSp", 18), Charsets.UTF_8)
        assertEquals("""{"key":"textSizeSp","value":"18"}""", intReq)

        val boolReq = String(SettingsJson.encodeSetRequest("crashReportingEnabled", true), Charsets.UTF_8)
        assertEquals("""{"key":"crashReportingEnabled","value":"true"}""", boolReq)

        val negativeIntReq = String(SettingsJson.encodeSetRequest("textColorArgb", -1517599), Charsets.UTF_8)
        assertEquals("""{"key":"textColorArgb","value":"-1517599"}""", negativeIntReq)

        val enumReq = String(SettingsJson.encodeSetRequest("mode", "SPEEDREAD"), Charsets.UTF_8)
        assertEquals("""{"key":"mode","value":"SPEEDREAD"}""", enumReq)
    }
}
