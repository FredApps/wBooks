package com.wbooks.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.wbooks.data.book.Book
import com.wbooks.data.settings.ReaderSettings

/**
 * Normal reading mode. Planned behaviours, none of which are implemented yet:
 *  - Page-by-page rendering of the parsed document (paragraph layout, italics/bold/headings/dividers).
 *  - Touch tap to advance a screen, swipe for smooth scroll, rotary bezel for incremental scroll.
 *  - Autoscroll on a timer; tap toggles pause/resume when enabled.
 */
@Composable
fun NormalMode(
    book: Book,
    settings: ReaderSettings,
) {
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            text = "[Normal mode]\n${book.title}",
            fontSize = settings.textSizeSp.sp,
        )
    }
}
