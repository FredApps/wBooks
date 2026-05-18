package com.wbooks.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.wbooks.ui.DocumentState
import com.wbooks.ui.ReaderViewModel
import com.wbooks.ui.secondary.SecondaryScreen
import com.wbooks.ui.settings.SettingsScreen

/**
 * Three-page horizontal pager. Tools | Reader | Settings, opens on Reader.
 */
@Composable
fun ReaderPager(
    state: DocumentState,
    vm: ReaderViewModel,
    onExit: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val readerActive by remember { derivedStateOf { pagerState.currentPage == 1 } }
    BackHandler(onBack = onExit)
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        when (page) {
            0 -> SecondaryScreen(state = state, vm = vm)
            1 -> ReaderScreen(state = state, vm = vm, isActive = readerActive, onExit = onExit)
            2 -> SettingsScreen(vm = vm)
        }
    }
}
