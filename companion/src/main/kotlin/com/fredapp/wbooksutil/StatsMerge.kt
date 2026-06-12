package com.fredapp.wbooksutil

import com.fredapp.wbooks.data.stats.ReadingStatsRepository

/**
 * Folds the phone's own reading stats together with the watch's into a single
 * [StatsSummary] for display. The merge is invisible to the user — they see one
 * combined picture of their reading regardless of which device it happened on.
 *
 * Each device keeps its own [ReadingStatsRepository] (stats are not synced over
 * the Data Layer), so totals are additive. When the watch is unreachable we
 * simply show the phone's stats; the watch's contribution folds back in the next
 * time it's reachable.
 */
object StatsMerge {

    fun merge(watch: StatsSummary?, phone: ReadingStatsRepository.Summary): StatsSummary {
        val phoneSummary = phone.toStatsSummary()
        if (watch == null) return phoneSummary
        return StatsSummary(
            totalMs = watch.totalMs + phoneSummary.totalMs,
            todayMs = watch.todayMs + phoneSummary.todayMs,
            // Additive across devices. The same book finished on both the watch
            // and the phone would count twice, but stats aren't synced so there's
            // no shared finished-set to dedupe against — and reading the same book
            // through on two devices is a rare edge.
            booksFinished = watch.booksFinished + phoneSummary.booksFinished,
            daily = mergeDaily(watch.daily, phoneSummary.daily),
            wpm = mergeWpm(watch.wpm, phoneSummary.wpm),
        )
    }

    private fun ReadingStatsRepository.Summary.toStatsSummary(): StatsSummary =
        StatsSummary(
            totalMs = totalMs,
            todayMs = todayMs,
            booksFinished = booksFinished,
            daily = recentDaily.map { StatsSummary.DailyEntry(it.date.toString(), it.ms) },
            wpm = recentWpm.map { StatsSummary.WpmSample(it.timestampMs, it.wpm) },
        )

    /** Union of dates, summing each day's milliseconds. "YYYY-MM-DD" sorts chronologically. */
    private fun mergeDaily(
        a: List<StatsSummary.DailyEntry>,
        b: List<StatsSummary.DailyEntry>,
    ): List<StatsSummary.DailyEntry> {
        val byDate = sortedMapOf<String, Long>()
        for (d in a) byDate[d.date] = (byDate[d.date] ?: 0L) + d.ms
        for (d in b) byDate[d.date] = (byDate[d.date] ?: 0L) + d.ms
        return byDate.map { StatsSummary.DailyEntry(it.key, it.value) }
    }

    /** Interleave both sample streams in time order, keeping the most recent [WPM_KEEP]. */
    private fun mergeWpm(
        a: List<StatsSummary.WpmSample>,
        b: List<StatsSummary.WpmSample>,
    ): List<StatsSummary.WpmSample> =
        (a + b).sortedBy { it.timestampMs }.takeLast(WPM_KEEP)

    private const val WPM_KEEP = 50
}
