package com.wbooks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.wbooks.data.book.Book
import com.wbooks.ui.library.LibraryScreen
import com.wbooks.ui.reader.ReaderPager

/**
 * Top-level navigation: the library lives at the back of the stack; opening a book
 * pushes the three-page reader pager (Tools | Reader | Settings).
 *
 * We keep navigation manual here rather than pulling in wear-compose-navigation —
 * there are only two destinations and the back-press contract is simple.
 */
@Composable
fun WBooksRoot() {
    var openBook by remember { mutableStateOf<Book?>(null) }

    val current = openBook
    if (current == null) {
        LibraryScreen(onBookOpen = { openBook = it })
    } else {
        ReaderPager(book = current, onExit = { openBook = null })
    }
}
