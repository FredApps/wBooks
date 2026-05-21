package com.fredapp.wbooks.data.stats

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.statsDataStore: DataStore<Preferences> by preferencesDataStore(name = "reading_stats")

/**
 * Cross-book reading stats used by the daily tile, the watch-face complication,
 * and the phone-companion dashboard.
 *
 * Storage shape (one Preferences file):
 *   day:YYYY-MM-DD     -> long ms read on that day
 *   total_ms           -> long sum across all days
 *   finished:<bookId>  -> int 1 once completed (idempotent count source)
 *   finished_count     -> int total books finished
 *   wpm                -> string "ts1,wpm1;ts2,wpm2;..." (capped at [WPM_HISTORY_MAX])
 */
class ReadingStatsRepository(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.statsDataStore

    data class Summary(
        val totalMs: Long,
        val todayMs: Long,
        val booksFinished: Int,
        val recentDaily: List<DailyEntry>,
        val recentWpm: List<WpmSample>,
    )

    data class DailyEntry(val date: LocalDate, val ms: Long)
    data class WpmSample(val timestampMs: Long, val wpm: Int)

    /**
     * Add [deltaMs] to today's reading time. Sessions that cross midnight are
     * attributed in chunks based on whatever day is "today" at the moment of
     * each call â€” [ReaderViewModel] flushes every minute, so the worst-case
     * misattribution is â‰¤ 1 minute landing on the new day.
     */
    suspend fun recordSession(deltaMs: Long) {
        if (deltaMs <= 0) return
        val today = LocalDate.now().toString()
        store.edit { prefs ->
            val dayKey = longPreferencesKey("day:$today")
            prefs[dayKey] = (prefs[dayKey] ?: 0L) + deltaMs
            prefs[TOTAL_KEY] = (prefs[TOTAL_KEY] ?: 0L) + deltaMs
        }
    }

    /** Idempotent: only the first call for a given book counts toward the total. */
    suspend fun markFinished(bookId: String) {
        val markerKey = intPreferencesKey("finished:$bookId")
        store.edit { prefs ->
            if (prefs[markerKey] == 1) return@edit
            prefs[markerKey] = 1
            prefs[FINISHED_COUNT_KEY] = (prefs[FINISHED_COUNT_KEY] ?: 0) + 1
        }
    }

    suspend fun isFinished(bookId: String): Boolean =
        store.data.first()[intPreferencesKey("finished:$bookId")] == 1

    suspend fun moveBookId(fromBookId: String, toBookId: String) {
        if (fromBookId == toBookId) return
        val from = intPreferencesKey("finished:$fromBookId")
        val to = intPreferencesKey("finished:$toBookId")
        store.edit { prefs ->
            if (prefs[from] == 1 && prefs[to] != 1) {
                prefs[to] = 1
            }
            prefs.remove(from)
        }
    }

    suspend fun recordWpm(wpm: Int) {
        if (wpm <= 0) return
        val now = System.currentTimeMillis()
        store.edit { prefs ->
            val current = prefs[WPM_KEY].orEmpty()
            val appended = if (current.isEmpty()) "$now,$wpm" else "$current;$now,$wpm"
            // Trim to the most recent [WPM_HISTORY_MAX] samples.
            val parts = appended.split(';')
            prefs[WPM_KEY] = if (parts.size <= WPM_HISTORY_MAX) appended
            else parts.takeLast(WPM_HISTORY_MAX).joinToString(";")
        }
    }

    val summaryFlow: Flow<Summary> = store.data.map { prefs ->
        val today = LocalDate.now()
        val todayMs = prefs[longPreferencesKey("day:$today")] ?: 0L
        Summary(
            totalMs = prefs[TOTAL_KEY] ?: 0L,
            todayMs = todayMs,
            booksFinished = prefs[FINISHED_COUNT_KEY] ?: 0,
            recentDaily = recentDaily(prefs, daysBack = 30),
            recentWpm = decodeWpm(prefs[WPM_KEY]),
        )
    }

    suspend fun snapshot(): Summary = summaryFlow.first()

    /**
     * Last [daysBack] days, oldest-first. Older `day:` keys remain on disk but
     * are never read â€” DataStore stays cheap (a year of daily entries is
     * <50 KB). A real prune-on-write would tighten this if storage ever
     * becomes a concern.
     */
    private fun recentDaily(prefs: Preferences, daysBack: Int): List<DailyEntry> {
        val today = LocalDate.now()
        val out = mutableListOf<DailyEntry>()
        for (offset in 0 until daysBack) {
            val date = today.minusDays(offset.toLong())
            val ms = prefs[longPreferencesKey("day:$date")] ?: 0L
            out += DailyEntry(date, ms)
        }
        return out.asReversed()
    }

    private fun decodeWpm(raw: String?): List<WpmSample> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(';').mapNotNull { pair ->
            val comma = pair.indexOf(',')
            if (comma <= 0) return@mapNotNull null
            val ts = pair.substring(0, comma).toLongOrNull() ?: return@mapNotNull null
            val v = pair.substring(comma + 1).toIntOrNull() ?: return@mapNotNull null
            WpmSample(ts, v)
        }
    }

    private companion object {
        val TOTAL_KEY = longPreferencesKey("total_ms")
        val FINISHED_COUNT_KEY = intPreferencesKey("finished_count")
        val WPM_KEY = stringPreferencesKey("wpm")
        const val WPM_HISTORY_MAX = 50
    }
}
