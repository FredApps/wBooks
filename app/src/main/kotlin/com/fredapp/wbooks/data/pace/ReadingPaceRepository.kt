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
 * Per-book reading-pace statistic. We track the average milliseconds spent
 * between block-level position advances (each tap-to-page on the reader, roughly).
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

    data class Pace(val msPerBlock: Double, val sampleCount: Int) {
        /** A pace is "ready" once we've seen enough samples to trust the average. */
        val isReady: Boolean get() = sampleCount >= MIN_READY_SAMPLES
    }

    fun paceFlow(bookId: String): Flow<Pace?> {
        val key = stringPreferencesKey("pace:$bookId")
        return store.data.map { prefs -> prefs[key]?.let(::decode) }
    }

    /**
     * Drop the pace entry for [bookId]. Intended to be called when a book is
     * deleted (TODO: wire from the watch's delete flow + the upload server's
     * delete endpoint so deleted books don't leave orphaned pace data).
     */
    suspend fun clear(bookId: String) {
        val key = stringPreferencesKey("pace:$bookId")
        store.edit { it.remove(key) }
    }

    suspend fun moveBookId(fromBookId: String, toBookId: String) {
        if (fromBookId == toBookId) return
        val from = stringPreferencesKey("pace:$fromBookId")
        val to = stringPreferencesKey("pace:$toBookId")
        store.edit { prefs ->
            prefs[from]?.let { prefs[to] = it }
            prefs.remove(from)
        }
    }

    suspend fun recordAdvance(bookId: String, deltaMs: Long) {
        if (deltaMs !in MIN_DELTA_MS..MAX_DELTA_MS) return
        val key = stringPreferencesKey("pace:$bookId")
        store.edit { prefs ->
            val prior = prefs[key]?.let(::decode)
            val next = if (prior == null) {
                Pace(deltaMs.toDouble(), 1)
            } else {
                Pace(
                    msPerBlock = ALPHA * deltaMs + (1.0 - ALPHA) * prior.msPerBlock,
                    sampleCount = (prior.sampleCount + 1).coerceAtMost(MAX_SAMPLE_COUNT),
                )
            }
            prefs[key] = encode(next)
        }
    }

    private fun encode(p: Pace): String = "${p.msPerBlock}|${p.sampleCount}"

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

        /** Below this we treat the advance as an accidental double-tap. */
        const val MIN_DELTA_MS = 500L

        /**
         * Above this the user was probably idle (locked, called away, ...).
         * Dense / technical reading on a small screen can legitimately spend
         * 1â€“2 minutes on a single block, so the cap is generous.
         */
        const val MAX_DELTA_MS = 180_000L

        /** Need at least this many samples before the UI shows the estimate. */
        const val MIN_READY_SAMPLES = 3

        /** Cap the count so the EMA's "weight" stays bounded in the UI. */
        const val MAX_SAMPLE_COUNT = 10_000
    }
}
