package com.fredapp.wbooks.data.gutenberg

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.gutenbergDownloadsDataStore: DataStore<Preferences> by preferencesDataStore(name = "gutenberg_downloads")

/**
 * Project Gutenberg identity markers keyed by current book id.
 *
 * Filename/title matching breaks after a user renames a downloaded book. This
 * repository stores the Gutenberg entry identity separately and follows the
 * same book-id migrations as positions, bookmarks, stats, and parser cache.
 */
class GutenbergDownloadsRepository(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.gutenbergDownloadsDataStore

    val downloadedKeysFlow: Flow<Set<String>> = store.data.map { prefs ->
        prefs.asMap().values
            .filterIsInstance<String>()
            .flatMap { decodeKeys(it) }
            .toSet()
    }

    suspend fun markDownloaded(bookId: String, keys: Set<String>) {
        val clean = keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return
        store.edit { prefs -> prefs[bucketKey(bookId)] = clean.joinToString("\n") }
    }

    suspend fun clear(bookId: String) {
        store.edit { prefs -> prefs.remove(bucketKey(bookId)) }
    }

    suspend fun moveBookId(fromBookId: String, toBookId: String) {
        if (fromBookId == toBookId) return
        val from = bucketKey(fromBookId)
        val to = bucketKey(toBookId)
        store.edit { prefs ->
            prefs[from]?.let { prefs[to] = it }
            prefs.remove(from)
        }
    }

    private fun decodeKeys(raw: String): List<String> =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun bucketKey(bookId: String) = stringPreferencesKey("gutenberg:$bookId")
}
