package com.fredapp.wbooks.data.bookmarks

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fredapp.wbooks.data.position.BookPosition
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
            // Avoid storing exact duplicates of the same position.
            if (current.any { it.position == bookmark.position }) current
            else current + bookmark
        }
    }

    suspend fun remove(bookId: String, position: BookPosition) {
        edit(bookId) { it.filterNot { existing -> existing.position == position } }
    }

    /** Remove all bookmarks for a deleted book. */
    suspend fun clear(bookId: String) {
        val key = stringPreferencesKey("bm:$bookId")
        store.edit { it.remove(key) }
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
        return "${b.position.chapterIndex}|${b.position.blockIndex}|${b.savedAtMs}|$labelB64"
    }

    private fun decodeOne(raw: String): Bookmark? {
        val parts = raw.split('|')
        if (parts.size != 4) return null
        val ch = parts[0].toIntOrNull() ?: return null
        val bl = parts[1].toIntOrNull() ?: return null
        val ts = parts[2].toLongOrNull() ?: return null
        val labelBytes = runCatching { Base64.decode(parts[3], Base64.NO_WRAP or Base64.NO_PADDING) }.getOrNull()
        val label = labelBytes?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
        return Bookmark(BookPosition(ch, bl), ts, label)
    }
}
