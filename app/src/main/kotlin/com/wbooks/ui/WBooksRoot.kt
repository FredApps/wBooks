package com.wbooks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.wbooks.ui.library.LibraryPager
import com.wbooks.ui.reader.ReaderPager

/**
 * Top-level nav. Library is the back of the stack; opening a book asks the VM to
 * load it and pushes the reader pager when there is a Loading/Loaded/Failed state.
 * Back from the reader (close) returns to Idle, which renders the library again.
 *
 * The library itself is a three-page pager: Search | Library | Settings.
 */
@Composable
fun WBooksRoot(vm: ReaderViewModel) {
    val docState by vm.document.collectAsState()

    when (docState) {
        DocumentState.Idle -> LibraryPager(vm = vm)
        else -> ReaderPager(state = docState, vm = vm, onExit = { vm.closeBook() })
    }
}
