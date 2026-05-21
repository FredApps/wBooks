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
 * mode switch does not have to re-filter a single list. Each entry is a
 * pipe-separated record: `chapter|block|sub|savedAtMs|base64Label`. We don't
 * expect many bookmarks per book; if that changes we'll move to Room.
 *
 * Legacy reads: an older single-bucket key `bm:<bookId>` may still exist on
 * upgraded installs. The first access to any mode for a book migrates those
 * entries into the new keyed buckets (by the mode stamp on each old record,
 * defaulting to NORMAL).
 */
class BookmarksRepository(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.bookmarksDataStore

    fun bookmarksFlow(bookId: String, mode: ReadingMode): Flow<List<Bookmark>> {
        val bucket = bucketKey(bookId, mode)
        val legacy = legacyKey(bookId)
        return store.data.map { prefs ->
            // Read-only union of the mode bucket and any not-yet-migrated
            // legacy entries belonging to this mode. The next write through
            // [edit] migrates the legacy key into per-mode buckets.
            val fromBucket = decodeAll(prefs[bucket].orEmpty(), mode)
            val fromLegacy = prefs[legacy]
                ?.split(';')
                ?.mapNotNull(::decodeLegacy)
                ?.filter { it.mode == mode }
                ?: emptyList()
            (fromBucket + fromLegacy).distinctBy { it.position }
        }
    }

    suspend fun add(bookId: String, bookmark: Bookmark) {
        edit(bookId, bookmark.mode) { current ->
            // Don't store exact duplicates of the same position within the
            // same mode — taps on the same spot are idempotent.
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
            prefs.remove(legacyKey(bookId))
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
            val legacyFrom = legacyKey(fromBookId)
            prefs[legacyFrom]?.let { prefs[legacyKey(toBookId)] = it }
            prefs.remove(legacyFrom)
        }
    }

    private suspend fun edit(
        bookId: String,
        mode: ReadingMode,
        transform: (List<Bookmark>) -> List<Bookmark>,
    ) {
        val key = bucketKey(bookId, mode)
        store.edit { prefs ->
            migrateLegacyKey(prefs, bookId)
            val current = decodeAll(prefs[key].orEmpty(), mode)
            val next = transform(current)
                .sortedBy { it.position.chapterIndex.toLong() * 1_000_000L + it.position.blockIndex }
            prefs[key] = encodeAll(next)
        }
    }

    private fun migrateLegacyKey(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        bookId: String,
    ) {
        val legacy = legacyKey(bookId)
        val raw = prefs[legacy] ?: return
        val buckets: MutableMap<ReadingMode, MutableList<Bookmark>> = ReadingMode.values()
            .associateWith { mutableListOf<Bookmark>() }
            .toMutableMap()
        for (token in raw.split(';')) {
            val bm = decodeLegacy(token) ?: continue
            buckets.getValue(bm.mode) += bm
        }
        for ((mode, list) in buckets) {
            if (list.isEmpty()) continue
            val key = bucketKey(bookId, mode)
            val existing = decodeAll(prefs[key].orEmpty(), mode)
            val merged = (existing + list)
                .distinctBy { it.position }
                .sortedBy { it.position.chapterIndex.toLong() * 1_000_000L + it.position.blockIndex }
            prefs[key] = encodeAll(merged)
        }
        prefs.remove(legacy)
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

    /** Decode a record from a mode-keyed bucket. The mode comes from the key. */
    private fun decodeOne(raw: String, mode: ReadingMode): Bookmark? {
        val parts = raw.split('|')
        // 5-part: ch|bl|sub|ts|label  (current keyed-bucket shape)
        // 4-part: ch|bl|ts|label      (very old, pre-subIndex; tolerate)
        val (ch, bl, sub, ts, labelToken) = when (parts.size) {
            4 -> Quintuple(parts[0], parts[1], "0", parts[2], parts[3])
            5 -> Quintuple(parts[0], parts[1], parts[2], parts[3], parts[4])
            else -> return null
        }
        val chI = ch.toIntOrNull() ?: return null
        val blI = bl.toIntOrNull() ?: return null
        val subI = sub.toIntOrNull() ?: 0
        val tsL = ts.toLongOrNull() ?: return null
        val labelBytes = runCatching { Base64.decode(labelToken, Base64.NO_WRAP or Base64.NO_PADDING) }.getOrNull()
        val label = labelBytes?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
        return Bookmark(BookPosition(chI, blI, subI), tsL, label, mode)
    }

    /** Decode an entry from the legacy single-bucket key, which still carries
     *  the mode stamp inside the record. */
    private fun decodeLegacy(raw: String): Bookmark? {
        val parts = raw.split('|')
        // Legacy shapes that ever shipped:
        // 4-part: ch|bl|ts|label
        // 5-part: ch|bl|sub|ts|label
        // 6-part: ch|bl|sub|ts|label|MODE
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

    private fun bucketKey(bookId: String, mode: ReadingMode) =
        stringPreferencesKey("bm:${mode.name}:$bookId")

    private fun legacyKey(bookId: String) = stringPreferencesKey("bm:$bookId")

    private data class Quintuple(
        val a: String, val b: String, val c: String, val d: String, val e: String,
    )

    private data class Sextuple(
        val a: String, val b: String, val c: String, val d: String, val e: String, val f: String,
    )
}
