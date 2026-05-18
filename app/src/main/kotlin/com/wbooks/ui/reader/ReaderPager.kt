package com.wbooks.ui.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wbooks.data.book.Book
import com.wbooks.ui.ReaderViewModel
import com.wbooks.ui.secondary.SecondaryScreen
import com.wbooks.ui.settings.SettingsScreen

/**
 * Three-page horizontal pager. See SettingsScreen / SecondaryScreen / ReaderScreen for
 * what each page does. Opens centered on the Reader.
 */
@Composable
fun ReaderPager(
    book: Book,
    vm: ReaderViewModel,
    onExit: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        when (page) {
            0 -> SecondaryScreen(book = book)
            1 -> ReaderScreen(book = book, vm = vm, onExit = onExit)
            2 -> SettingsScreen(vm = vm)
        }
    }
}
