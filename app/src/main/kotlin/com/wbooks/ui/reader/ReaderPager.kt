package com.wbooks.ui.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wbooks.data.book.Book
import com.wbooks.ui.secondary.SecondaryScreen
import com.wbooks.ui.settings.SettingsScreen

/**
 * Three-page horizontal pager:
 *   page 0 — Tools (search, bookmark here, bookmarks list, chapters)
 *   page 1 — Reader (initial page, where the user lands when a book is opened)
 *   page 2 — Settings (mode, font, colors, autoscroll, transfer)
 *
 * Why this shape: Wear OS reserves swipe-from-left-edge for the back/dismiss gesture
 * and won't reliably let us bind it to a menu. Putting menus as adjacent pager pages
 * means swipe-right works on the inner pages and swipe-right at page 0 (no further
 * left to go) cleanly performs the system dismiss — which we let exit the reader
 * back to the library.
 */
@Composable
fun ReaderPager(
    book: Book,
    onExit: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        when (page) {
            0 -> SecondaryScreen(book = book)
            1 -> ReaderScreen(book = book, onExit = onExit)
            2 -> SettingsScreen()
        }
    }
}
