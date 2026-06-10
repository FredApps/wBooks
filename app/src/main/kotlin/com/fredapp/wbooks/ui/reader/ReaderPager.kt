package com.fredapp.wbooks.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import com.fredapp.wbooks.ui.DocumentState
import com.fredapp.wbooks.ui.ReaderViewModel
import com.fredapp.wbooks.ui.secondary.SecondaryScreen
import com.fredapp.wbooks.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/**
 * Three-page horizontal pager. Tools | Reader | Settings, opens on Reader.
 *
 * Back behavior is keyed on the settled page, but side-page activation is keyed
 * on currentPage. Some Wear touch scroll gestures briefly mark the pager as
 * "scroll in progress"; using that transient state for Tools/Settings activation
 * would re-run their scroll-to-top effects during normal vertical list scrolling.
 */
@Composable
fun ReaderPager(
    state: DocumentState,
    vm: ReaderViewModel,
    onExit: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    // See LibraryPager: drive active from currentPage, not isScrollInProgress.
    // A vertical scroll gesture inside a nested list can briefly flip
    // isScrollInProgress true→false and otherwise retrigger `onActivated`
    // (e.g. scroll-to-top) when the gesture ends.
    val readerActive by remember { derivedStateOf { pagerState.currentPage == 1 } }
    val sidePage by remember { derivedStateOf { pagerState.currentPage } }
    val scope = rememberCoroutineScope()
    var toolsSearchActive by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val goToReader = {
        toolsSearchActive = false
        scope.launch { pagerState.animateScrollToPage(1) }
        Unit
    }

    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) focusManager.clearFocus(force = true)
    }

    BackHandler(enabled = sidePage == 0 || sidePage == 2, onBack = goToReader)
    BackHandler(enabled = readerActive, onBack = onExit)
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = !toolsSearchActive,
    ) { page ->
        when (page) {
            0 -> SecondaryScreen(
                state = state,
                vm = vm,
                isActive = sidePage == 0,
                onSearchActiveChanged = { toolsSearchActive = it },
                onReaderPageRequested = goToReader,
                onExit = onExit,
            )
            1 -> ReaderScreen(state = state, vm = vm, isActive = readerActive, onExit = onExit)
            2 -> SettingsScreen(
                vm = vm,
                isActive = sidePage == 2,
                onBack = goToReader,
            )
        }
    }
}
