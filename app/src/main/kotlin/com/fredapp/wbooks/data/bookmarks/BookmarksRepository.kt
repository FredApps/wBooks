package com.fredapp.wbooks.data.bookmarks

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fredapp.wbooks.data.position.BookPosition
import com.fredapp.wbooks.data.settings.ReadingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.bookmarksDataStore: DataStore<Preferences> by preferencesDataStore(name = "book_bookmarks")

/**
 * Per-book bookmark lists, encoded as a single semicolon-separated string per
 * book. Each entry is `chapter|block|savedAtMs|base64Label`. We don't expect
 * many bookmarks per book; if that changes we'll move to Room.
 */
class BookmarksRepository(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.bookmarksDataStore

    fun bookmarksFlow(bookId: String): Flow<List<Bookmark>> {
        val key = stringPreferencesKey("bm:$bookId")
        return store.data.map { prefs -> decodeAll(prefs[key].orEmpty()) }
    }

    suspend fun add(bookId: String, bookmark: Bookmark) {
        edit(bookId) { current ->
            // Don't store duplicates of the same position+mode pair — the same
            // spot in different modes is intentionally two separate entries.
            if (current.any { it.position == bookmark.position && it.mode == bookmark.mode }) current
            else current + bookmark
        }
    }

    suspend fun remove(bookId: String, position: BookPosition, mode: ReadingMode) {
        edit(bookId) { it.filterNot { existing -> existing.position == position && existing.mode == mode } }
    }

    /** Remove all bookmarks for a deleted book. */
    suspend fun clear(bookId: String) {
        val key = stringPreferencesKey("bm:$bookId")
        store.edit { it.remove(key) }
    }

    suspend fun moveBookId(fromBookId: String, toBookId: String) {
        if (fromBookId == toBookId) return
        val from = stringPreferencesKey("bm:$fromBookId")
        val to = stringPreferencesKey("bm:$toBookId")
        store.edit { prefs ->
            prefs[from]?.let { prefs[to] = it }
            prefs.remove(from)
        }
    }

    private suspend fun edit(bookId: String, transform: (List<Bookmark>) -> List<Bookmark>) {
        val key = stringPreferencesKey("bm:$bookId")
        store.edit { prefs ->
            val current = decodeAll(prefs[key].orEmpty())
            val next = transform(current).sortedBy { it.position.chapterIndex.toLong() * 1_000_000L + it.position.blockIndex }
            prefs[key] = encodeAll(next)
        }
    }

    private fun encodeAll(list: List<Bookmark>): String =
        list.joinToString(";") { encodeOne(it) }

    private fun decodeAll(raw: String): List<Bookmark> =
        if (raw.isBlank()) emptyList()
        else raw.split(';').mapNotNull(::decodeOne)

    private fun encodeOne(b: Bookmark): String {
        val label = b.label.orEmpty().toByteArray(Charsets.UTF_8)
        val labelB64 = Base64.encodeToString(label, Base64.NO_WRAP or Base64.NO_PADDING)
        val p = b.position
        return "${p.chapterIndex}|${p.blockIndex}|${p.subIndex}|${b.savedAtMs}|$labelB64|${b.mode.name}"
    }

    private fun decodeOne(raw: String): Bookmark? {
        // Storage has gone through three formats; map each one onto the current
        // 6-part shape so legacy bookmarks survive an upgrade.
        // 4-part: ch|bl|ts|label                (subIndex 0, mode NORMAL)
        // 5-part: ch|bl|sub|ts|label             (mode NORMAL)
        // 6-part: ch|bl|sub|ts|label|MODE        (current)
        val parts = raw.split('|')
        val (ch, bl, sub, ts, labelToken, modeToken) = when (parts.size) {
            4 -> Sextuple(parts[0], parts[1], "0", parts[2], parts[3], ReadingMode.NORMAL.name)
            5 -> Sextuple(parts[0], parts[1], parts[2], parts[3], parts[4], ReadingMode.NORMAL.name)
            6 -> Sextuple(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5])
            else -> return null
        }
        val chI = ch.toIntOrNull() ?: return null
        val blI = bl.toIntOrNull() ?: return null
        val subI = sub.toIntOrNull() ?: 0
        val tsL = ts.toLongOrNull() ?: return null
        val labelBytes = runCatching { Base64.decode(labelToken, Base64.NO_WRAP or Base64.NO_PADDING) }.getOrNull()
        val label = labelBytes?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
        val mode = runCatching { ReadingMode.valueOf(modeToken) }.getOrDefault(ReadingMode.NORMAL)
        return Bookmark(BookPosition(chI, blI, subI), tsL, label, mode)
    }

    private data class Sextuple(
        val a: String, val b: String, val c: String, val d: String, val e: String, val f: String,
    )
}
