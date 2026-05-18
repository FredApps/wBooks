package com.wbooks.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.wbooks.data.book.Book
import com.wbooks.data.settings.ReadingMode
import com.wbooks.ui.ReaderViewModel

/**
 * Dispatches to the active reading mode. Mode + display preferences come from
 * the shared [ReaderViewModel] — flipping mode in Settings is reflected here
 * immediately because both screens collect the same StateFlow.
 */
@Composable
fun ReaderScreen(
    book: Book,
    vm: ReaderViewModel,
    onExit: () -> Unit,
) {
    val settings by vm.settings.collectAsState()

    Box(Modifier.fillMaxSize()) {
        when (settings.mode) {
            ReadingMode.NORMAL -> NormalMode(book = book, settings = settings)
            ReadingMode.SPEEDREAD -> SpeedReadMode(
                book = book,
                settings = settings,
                onWpmChange = vm::setSpeedreadWpm,
            )
            ReadingMode.SENTENCE -> SentenceMode(book = book, settings = settings)
        }
    }
}
