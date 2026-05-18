package com.wbooks.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.wbooks.data.book.Book
import com.wbooks.data.settings.ReaderSettings
import com.wbooks.data.settings.ReadingMode

/**
 * Dispatches to the active reading mode. The mode is part of [ReaderSettings] so the
 * user's last choice is restored across opens.
 */
@Composable
fun ReaderScreen(
    book: Book,
    onExit: () -> Unit,
) {
    // TODO: hoist into a ViewModel reading from DataStore.
    var settings by remember { mutableStateOf(ReaderSettings()) }

    Box(Modifier.fillMaxSize()) {
        when (settings.mode) {
            ReadingMode.NORMAL -> NormalMode(book = book, settings = settings)
            ReadingMode.SPEEDREAD -> SpeedReadMode(book = book, settings = settings,
                onWpmChange = { settings = settings.copy(speedreadWpm = it) })
            ReadingMode.SENTENCE -> SentenceMode(book = book, settings = settings)
        }
    }
}
