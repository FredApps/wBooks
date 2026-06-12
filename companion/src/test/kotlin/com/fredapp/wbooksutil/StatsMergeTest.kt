package com.fredapp.wbooksutil

import com.fredapp.wbooks.data.stats.ReadingStatsRepository
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsMergeTest {

    private fun phone(
        totalMs: Long = 0,
        todayMs: Long = 0,
        finished: Int = 0,
        daily: List<Pair<String, Long>> = emptyList(),
        wpm: List<Pair<Long, Int>> = emptyList(),
    ) = ReadingStatsRepository.Summary(
        totalMs = totalMs,
        todayMs = todayMs,
        booksFinished = finished,
        recentDaily = daily.map { ReadingStatsRepository.DailyEntry(LocalDate.parse(it.first), it.second) },
        recentWpm = wpm.map { ReadingStatsRepository.WpmSample(it.first, it.second) },
    )

    @Test
    fun `no watch returns phone stats unchanged`() {
        val merged = StatsMerge.merge(
            watch = null,
            phone = phone(totalMs = 1000, todayMs = 200, finished = 2, daily = listOf("2026-06-10" to 200L)),
        )
        assertEquals(1000, merged.totalMs)
        assertEquals(200, merged.todayMs)
        assertEquals(2, merged.booksFinished)
        assertEquals(listOf(StatsSummary.DailyEntry("2026-06-10", 200L)), merged.daily)
    }

    @Test
    fun `totals and finished counts are additive`() {
        val watch = StatsSummary(totalMs = 5000, todayMs = 300, booksFinished = 3, daily = emptyList(), wpm = emptyList())
        val merged = StatsMerge.merge(watch, phone(totalMs = 1000, todayMs = 200, finished = 2))
        assertEquals(6000, merged.totalMs)
        assertEquals(500, merged.todayMs)
        assertEquals(5, merged.booksFinished)
    }

    @Test
    fun `daily entries sum per date across the union of days`() {
        val watch = StatsSummary(
            totalMs = 0, todayMs = 0, booksFinished = 0,
            daily = listOf(
                StatsSummary.DailyEntry("2026-06-10", 100L),
                StatsSummary.DailyEntry("2026-06-11", 50L),
            ),
            wpm = emptyList(),
        )
        val merged = StatsMerge.merge(
            watch,
            phone(daily = listOf("2026-06-11" to 25L, "2026-06-12" to 80L)),
        )
        assertEquals(
            listOf(
                StatsSummary.DailyEntry("2026-06-10", 100L),
                StatsSummary.DailyEntry("2026-06-11", 75L),
                StatsSummary.DailyEntry("2026-06-12", 80L),
            ),
            merged.daily,
        )
    }

    @Test
    fun `wpm samples interleave in timestamp order`() {
        val watch = StatsSummary(
            totalMs = 0, todayMs = 0, booksFinished = 0, daily = emptyList(),
            wpm = listOf(StatsSummary.WpmSample(10, 200), StatsSummary.WpmSample(30, 240)),
        )
        val merged = StatsMerge.merge(watch, phone(wpm = listOf(20L to 220, 40L to 260)))
        assertEquals(listOf(200, 220, 240, 260), merged.wpm.map { it.wpm })
    }
}
