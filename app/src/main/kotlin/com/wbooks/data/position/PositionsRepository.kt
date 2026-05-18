package com.wbooks.data.position

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.positionsDataStore: DataStore<Preferences> by preferencesDataStore(name = "book_positions")

/** Reserved key for the most-recently-opened book; consumed by the Resume tile + auto-resume. */
private val LAST_OPENED_KEY = stringPreferencesKey("__last_opened__")

/**
 * One Preferences DataStore file for every book's last reading position, keyed by
 * the Book.id (relative-path-from-booksDir). One file is fine: positions are small
 * strings, and we never iterate them — only point-look-up by id.
 */
class PositionsRepository(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.positionsDataStore

    fun positionFlow(bookId: String): Flow<BookPosition> {
        val key = stringPreferencesKey("pos:$bookId")
        return store.data.map { prefs -> prefs[key]?.let(BookPosition::decode) ?: BookPosition.START }
    }

    suspend fun hasOpened(bookId: String): Boolean {
        val prefs = store.data.first()
        return prefs[stringPreferencesKey("opened:$bookId")] == "1" ||
            prefs[stringPreferencesKey("pos:$bookId")] != null ||
            prefs[LAST_OPENED_KEY] == bookId
    }

    suspend fun markOpened(bookId: String) {
        val key = stringPreferencesKey("opened:$bookId")
        store.edit { it[key] = "1" }
    }

    suspend fun readPosition(bookId: String): BookPosition {
        val key = stringPreferencesKey("pos:$bookId")
        return store.data.first()[key]?.let(BookPosition::decode) ?: BookPosition.START
    }

    suspend fun setPosition(bookId: String, pos: BookPosition) {
        val key = stringPreferencesKey("pos:$bookId")
        store.edit { it[key] = pos.encode() }
    }

    suspend fun clear(bookId: String) {
        val key = stringPreferencesKey("pos:$bookId")
        store.edit { it.remove(key) }
    }

    // ---- Last-opened book (for the Resume tile + app auto-resume). ----

    val lastOpenedBookId: Flow<String?> = store.data.map { it[LAST_OPENED_KEY] }

    suspend fun readLastOpenedBookId(): String? = store.data.first()[LAST_OPENED_KEY]

    suspend fun setLastOpenedBookId(id: String?) {
        store.edit {
            if (id == null) it.remove(LAST_OPENED_KEY) else it[LAST_OPENED_KEY] = id
        }
    }
}
