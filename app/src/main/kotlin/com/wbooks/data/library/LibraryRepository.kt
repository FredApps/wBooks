package com.wbooks.data.library

import com.wbooks.data.book.Book
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory placeholder. Real implementation will scan `filesDir/books/` and persist
 * metadata + reading position in DataStore (or Room, if the metadata grows).
 */
class LibraryRepository {
    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books

    fun add(book: Book) {
        _books.value = _books.value + book
    }

    fun remove(id: String) {
        _books.value = _books.value.filterNot { it.id == id }
    }
}
