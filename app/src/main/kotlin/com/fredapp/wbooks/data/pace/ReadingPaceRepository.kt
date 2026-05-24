package com.fredapp.wbooks.data.pace

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.paceDataStore: DataStore<Preferences> by preferencesDataStore(name = "reading_pace")

/**
 * Per-book reading-pace statistic. We track the average milliseconds per word
 * between natural position advances.
 *
 * Encoding is an exponential moving average so a single per-book Preferences
 * value captures the rolling pace cheaply â€” no need to retain a sliding window
 * of samples. The smoothing factor [ALPHA] yields a ~10-sample effective window
 * which is enough to be responsive without jittering on individual long pauses.
 *
 * Outliers (very short = double-tap glitch, very long = user paused / put the
 * watch down) are clamped out at the call site so they don't poison the average.
 */
class ReadingPaceRepository(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.paceDataStore

    data class Pace(val msPerWord: Double, val sampleCount: Int) {
        /** A pace is "ready" once we've seen enough samples to trust the average. */
        val isReady: Boolean get() = sampleCount >= MIN_READY_SAMPLES
    }

    fun paceFlow(bookId: String): Flow<Pace?> {
        val key = stringPreferencesKey("wordpace:$bookId")
        return store.data.map { prefs -> prefs[key]?.let(::decode) }
    }

    /**
     * Drop the pace entry for [bookId]. Intended to be called when a book is
     * deleted so removed books don't leave orphaned pace data.
     */
    suspend fun clear(bookId: String) {
        val key = stringPreferencesKey("wordpace:$bookId")
        val legacyKey = stringPreferencesKey("pace:$bookId")
        store.edit {
            it.remove(key)
            it.remove(legacyKey)
        }
    }

    suspend fun moveBookId(fromBookId: String, toBookId: String) {
        if (fromBookId == toBookId) return
        val from = stringPreferencesKey("wordpace:$fromBookId")
        val to = stringPreferencesKey("wordpace:$toBookId")
        val legacyFrom = stringPreferencesKey("pace:$fromBookId")
        store.edit { prefs ->
            prefs[from]?.let { prefs[to] = it }
            prefs.remove(from)
            prefs.remove(legacyFrom)
        }
    }

    suspend fun recordWordAdvance(bookId: String, msPerWord: Long) {
        recordWordAdvances(bookId, msPerWord, 1)
    }

    suspend fun recordWordAdvances(bookId: String, msPerWord: Long, count: Int) {
        val clampedMsPerWord = msPerWord.coerceIn(MIN_MS_PER_WORD, MAX_MS_PER_WORD)
        val safeCount = count.coerceIn(1, MAX_WORDS_PER_REPORT)
        val key = stringPreferencesKey("wordpace:$bookId")
        store.edit { prefs ->
            val prior = prefs[key]?.let(::decode)
            var averageMsPerWord = prior?.msPerWord ?: clampedMsPerWord.toDouble()
            var sampleCount = prior?.sampleCount ?: 0
            repeat(safeCount) {
                averageMsPerWord = if (sampleCount == 0) {
                    clampedMsPerWord.toDouble()
                } else {
                    ALPHA * clampedMsPerWord + (1.0 - ALPHA) * averageMsPerWord
                }
                sampleCount = (sampleCount + 1).coerceAtMost(MAX_SAMPLE_COUNT)
            }
            prefs[key] = encode(Pace(averageMsPerWord, sampleCount))
        }
    }

    private fun encode(p: Pace): String = "${p.msPerWord}|${p.sampleCount}"

    private fun decode(raw: String): Pace? {
        val bar = raw.indexOf('|')
        if (bar <= 0) return null
        val ms = raw.substring(0, bar).toDoubleOrNull() ?: return null
        val n = raw.substring(bar + 1).toIntOrNull() ?: return null
        return Pace(ms, n)
    }

    private companion object {
        /** EMA smoothing factor. Higher = more responsive, more jittery. */
        const val ALPHA = 0.2

        /** 75 ms/word = 800 WPM. Faster movement is clamped to this bootstrap ceiling. */
        const val MIN_MS_PER_WORD = 75L

        /** 5 seconds/word is slow enough to cover pauses without poisoning the average forever. */
        const val MAX_MS_PER_WORD = 5_000L

        /**
         * Need at least this many word advances before calling the pace
         * personalized. The UI can still show a bootstrap estimate before this.
         */
        const val MIN_READY_SAMPLES = 20

        /** Cap the count so the EMA's "weight" stays bounded in the UI. */
        const val MAX_SAMPLE_COUNT = 10_000

        /**
         * One renderer report can cover many words during a continuous swipe.
         * Cap it high enough for deliberate bezel/touch scrolling, low enough
         * that accidental same-chapter teleports don't instantly dominate.
         */
        const val MAX_WORDS_PER_REPORT = 1_000
    }
}
