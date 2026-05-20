package com.fredapp.wbooks.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.fredapp.wbooks.transfer.FoldersJson
import com.fredapp.wbooks.ui.ReaderViewModel
import com.fredapp.wbooks.ui.settings.SettingsScreen

/**
 * Three-page horizontal pager for the library: Search | Library | Settings.
 * Opens on Library (page 1). Swipe right â†’ title search, swipe left â†’ settings.
 */
@Composable
fun LibraryPager(vm: ReaderViewModel) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val books by vm.books.collectAsState()
    val folderState by vm.folderState.collectAsState()

    val settledPage by remember {
        derivedStateOf { if (pagerState.isScrollInProgress) -1 else pagerState.currentPage }
    }
    val searchActive by remember { derivedStateOf { settledPage == 0 } }
    val libraryActive by remember { derivedStateOf { settledPage == 1 } }
    val settingsActive by remember { derivedStateOf { settledPage == 2 } }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        when (page) {
            0 -> LibrarySearchScreen(
                books = books,
                isActive = searchActive,
                onBookOpen = { vm.openBook(it) },
            )
            1 -> LibraryScreen(
                books = books,
                folderState = folderState,
                isActive = libraryActive,
                onBookOpen = { vm.openBook(it) },
                onRefresh = { vm.refreshLibrary() },
            )
            2 -> SettingsScreen(vm = vm, isActive = settingsActive)
        }
    }
}
