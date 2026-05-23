package com.fredapp.wbooks.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import com.fredapp.wbooks.ui.ReaderViewModel
import com.fredapp.wbooks.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/**
 * Three-page horizontal pager for the library: Search | Library | Settings.
 * Opens on Library (page 1). Swipe right → title search, swipe left → settings.
 *
 * Back from Search or Settings returns to the Library page rather than exiting
 * the app. Back from Library is the default system back (exits to launcher).
 */
@Composable
fun LibraryPager(vm: ReaderViewModel) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val books by vm.books.collectAsState()
    val folders by vm.folders.collectAsState()

    // Drive active state from currentPage directly, NOT from isScrollInProgress.
    // isScrollInProgress can flip transiently during a touch that ends up being
    // a vertical scroll in nested content; that flicker would drop `active` to
    // false then back to true on release, which retriggers `onActivated` and
    // (in SettingsScreen) snaps the list back to the top. currentPage only
    // changes when the pager actually crosses a snap threshold, so it gives a
    // stable activation signal that survives vertical scroll gestures.
    val searchActive by remember { derivedStateOf { pagerState.currentPage == 0 } }
    val libraryActive by remember { derivedStateOf { pagerState.currentPage == 1 } }
    val settingsActive by remember { derivedStateOf { pagerState.currentPage == 2 } }
    val scope = rememberCoroutineScope()
    val goToLibrary = { scope.launch { pagerState.animateScrollToPage(1) } ; Unit }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) focusManager.clearFocus(force = true)
    }

    // Back from search or settings → Library page. From the library page,
    // BackHandler is not registered, so the system default (exit) applies.
    BackHandler(enabled = searchActive || settingsActive) { goToLibrary() }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        when (page) {
            0 -> LibrarySearchScreen(
                books = books,
                isActive = searchActive,
                onBookOpen = { vm.openBook(it) },
                onRefresh = { vm.refreshLibrary() },
                onBack = { goToLibrary() },
            )
            1 -> LibraryScreen(
                books = books,
                folderNames = folders,
                isActive = libraryActive,
                onBookOpen = { vm.openBook(it) },
                onRefresh = { vm.refreshLibrary() },
                onMoveBook = { bookId, folder -> vm.moveBook(bookId, folder) },
                onRenameBook = { bookId, title -> vm.renameBook(bookId, title) },
                onDeleteBook = { vm.deleteBook(it) },
                onCreateFolder = { vm.createFolder(it) },
                onRenameFolder = { o, n -> vm.renameFolder(o, n) },
                onDeleteFolder = { vm.deleteFolder(it) },
            )
            2 -> SettingsScreen(
                vm = vm,
                isActive = settingsActive,
                onBack = { goToLibrary() },
            )
        }
    }
}
