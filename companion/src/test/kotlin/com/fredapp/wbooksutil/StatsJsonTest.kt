package com.fredapp.wbooksutil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoder-side wire-format test. Pairs with `:app`'s `StatsJsonTest` â€” the
 * fixture below is the byte-for-byte shape that encoder produces (and that
 * the watch sends over MessageClient). If `:app` ever changes the encoder,
 * one of the two test files will fail loudly.
 */
class StatsJsonTest {

    @Test
    fun decodes_minimal_payload() {
        val json = """{"totalMs":0,"todayMs":0,"booksFinished":0,"daily":[],"wpm":[]}"""
        val s = StatsJson.decode(json.toByteArray(Charsets.UTF_8))
        assertEquals(0L, s.totalMs)
        assertEquals(0L, s.todayMs)
        assertEquals(0, s.booksFinished)
        assertEquals(emptyList<StatsSummary.DailyEntry>(), s.daily)
        assertEquals(emptyList<StatsSummary.WpmSample>(), s.wpm)
    }

    @Test
    fun decodes_full_payload() {
        val json = """
            {"totalMs":3600000,"todayMs":600000,"booksFinished":2,
             "daily":[{"date":"2026-05-18","ms":300000},
                      {"date":"2026-05-19","ms":600000}],
             "wpm":[{"ts":1747526400000,"wpm":320},
                    {"ts":1747530000000,"wpm":400}]}
        """.trimIndent()
        val s = StatsJson.decode(json.toByteArray(Charsets.UTF_8))
        assertEquals(3_600_000L, s.totalMs)
        assertEquals(600_000L, s.todayMs)
        assertEquals(2, s.booksFinished)
        assertEquals(
            listOf(
                StatsSummary.DailyEntry("2026-05-18", 300_000L),
                StatsSummary.DailyEntry("2026-05-19", 600_000L),
            ),
            s.daily,
        )
        assertEquals(
            listOf(
                StatsSummary.WpmSample(1747526400000L, 320),
                StatsSummary.WpmSample(1747530000000L, 400),
            ),
            s.wpm,
        )
    }

    @Test
    fun decodes_negative_and_large_numbers() {
        val json = """{"totalMs":99999999999,"todayMs":-1,"booksFinished":42,"daily":[],"wpm":[]}"""
        val s = StatsJson.decode(json.toByteArray(Charsets.UTF_8))
        assertEquals(99_999_999_999L, s.totalMs)
        assertEquals(-1L, s.todayMs)
        assertEquals(42, s.booksFinished)
    }

    @Test
    fun handles_missing_keys_gracefully() {
        // Forward-compat: an older watch might emit fewer fields, or a newer
        // one might omit one in error. We default missing values to 0 / empty.
        val json = """{"totalMs":1000}"""
        val s = StatsJson.decode(json.toByteArray(Charsets.UTF_8))
        assertEquals(1000L, s.totalMs)
        assertEquals(0L, s.todayMs)
        assertTrue(s.daily.isEmpty())
        assertTrue(s.wpm.isEmpty())
    }
}
