package com.wbooks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.wbooks.ui.library.LibraryScreen
import com.wbooks.ui.reader.ReaderPager

/**
 * Top-level nav. Library is the back of the stack; opening a book asks the VM to
 * load it and pushes the reader pager when there is a Loading/Loaded/Failed state.
 * Back from the reader (close) returns to Idle, which renders the library again.
 */
@Composable
fun WBooksRoot(vm: ReaderViewModel) {
    val docState by vm.document.collectAsState()
    val books by vm.books.collectAsState()

    when (docState) {
        DocumentState.Idle -> LibraryScreen(
            books = books,
            onBookOpen = { vm.openBook(it) },
            onRefresh = { vm.refreshLibrary() },
        )
        else -> ReaderPager(state = docState, vm = vm, onExit = { vm.closeBook() })
    }
}
