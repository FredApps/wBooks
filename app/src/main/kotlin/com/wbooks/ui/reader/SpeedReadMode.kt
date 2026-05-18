package com.wbooks.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.wbooks.data.book.Book
import com.wbooks.data.settings.ReaderSettings

/**
 * Speed-reading mode (RSVP — one word at a time). Planned:
 *  - Stream tokens from the parsed document at [ReaderSettings.speedreadWpm].
 *  - Rotary bezel adjusts WPM live; swipe-left menu offers same control.
 *  - Tap toggles play/pause.
 */
@Composable
fun SpeedReadMode(
    book: Book,
    settings: ReaderSettings,
    onWpmChange: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "word", fontSize = (settings.textSizeSp + 8).sp)
    }
}
