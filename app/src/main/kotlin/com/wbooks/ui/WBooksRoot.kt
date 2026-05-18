package com.wbooks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.wbooks.data.book.Book
import com.wbooks.ui.library.LibraryScreen
import com.wbooks.ui.reader.ReaderPager

/**
 * Top-level navigation: the library lives at the back of the stack; opening a book
 * pushes the three-page reader pager (Tools | Reader | Settings).
 */
@Composable
fun WBooksRoot(vm: ReaderViewModel) {
    var openBook by remember { mutableStateOf<Book?>(null) }

    val current = openBook
    if (current == null) {
        LibraryScreen(onBookOpen = { openBook = it })
    } else {
        ReaderPager(book = current, vm = vm, onExit = { openBook = null })
    }
}
