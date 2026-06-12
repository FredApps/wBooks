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
 * Three separate bookmark buckets per book — one per [ReadingMode]. The
 * storage key is `bm:<MODE>:<bookId>` so the modes do not share state and a
 * mode switch loads a different list, not a filtered view. Each entry is a
 * pipe-separated record: `chapter|block|sub|savedAtMs|base64Label`. We don't
 * expect many bookmarks per book; if that changes we'll move to Room.
 */
class BookmarksRepository(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.bookmarksDataStore

    fun bookmarksFlow(bookId: String, mode: ReadingMode): Flow<List<Bookmark>> {
        val bucket = bucketKey(bookId, mode)
        return store.data.map { prefs -> decodeAll(prefs[bucket].orEmpty(), mode) }
    }

    suspend fun add(bookId: String, bookmark: Bookmark) {
        edit(bookId, bookmark.mode) { current ->
            if (current.any { it.position == bookmark.position }) current
            else current + bookmark
        }
    }

    suspend fun remove(bookId: String, position: BookPosition, mode: ReadingMode) {
        edit(bookId, mode) { it.filterNot { existing -> existing.position == position } }
    }

    /** Remove all bookmarks for a deleted book across every mode bucket. */
    suspend fun clear(bookId: String) {
        store.edit { prefs ->
            for (mode in ReadingMode.values()) prefs.remove(bucketKey(bookId, mode))
        }
    }

    suspend fun moveBookId(fromBookId: String, toBookId: String) {
        if (fromBookId == toBookId) return
        store.edit { prefs ->
            for (mode in ReadingMode.values()) {
                val from = bucketKey(fromBookId, mode)
                val to = bucketKey(toBookId, mode)
                prefs[from]?.let { prefs[to] = it }
                prefs.remove(from)
            }
        }
    }

    private suspend fun edit(
        bookId: String,
        mode: ReadingMode,
        transform: (List<Bookmark>) -> List<Bookmark>,
    ) {
        val key = bucketKey(bookId, mode)
        store.edit { prefs ->
            val current = decodeAll(prefs[key].orEmpty(), mode)
            val next = transform(current)
                .sortedBy { it.position.chapterIndex.toLong() * 1_000_000L + it.position.blockIndex }
            prefs[key] = encodeAll(next)
        }
    }

    private fun encodeAll(list: List<Bookmark>): String =
        list.joinToString(";") { encodeOne(it) }

    private fun decodeAll(raw: String, mode: ReadingMode): List<Bookmark> =
        if (raw.isBlank()) emptyList()
        else raw.split(';').mapNotNull { decodeOne(it, mode) }

    private fun encodeOne(b: Bookmark): String {
        val label = b.label.orEmpty().toByteArray(Charsets.UTF_8)
        val labelB64 = Base64.encodeToString(label, Base64.NO_WRAP or Base64.NO_PADDING)
        val p = b.position
        return "${p.chapterIndex}|${p.blockIndex}|${p.subIndex}|${b.savedAtMs}|$labelB64"
    }

    private fun decodeOne(raw: String, mode: ReadingMode): Bookmark? {
        val parts = raw.split('|')
        if (parts.size != 5) return null
        val chI = parts[0].toIntOrNull() ?: return null
        val blI = parts[1].toIntOrNull() ?: return null
        val subI = parts[2].toIntOrNull() ?: 0
        val tsL = parts[3].toLongOrNull() ?: return null
        val labelBytes = runCatching { Base64.decode(parts[4], Base64.NO_WRAP or Base64.NO_PADDING) }.getOrNull()
        val label = labelBytes?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
        return Bookmark(BookPosition(chI, blI, subI), tsL, label, mode)
    }

    private fun bucketKey(bookId: String, mode: ReadingMode) =
        stringPreferencesKey("bm:${mode.name}:$bookId")
}
