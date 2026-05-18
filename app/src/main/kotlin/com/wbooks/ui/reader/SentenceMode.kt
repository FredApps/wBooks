package com.wbooks.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.wbooks.data.book.Book
import com.wbooks.data.settings.ReaderSettings

/**
 * Sentence mode. Planned:
 *  - Segment the parsed document into sentences (BreakIterator with locale = book locale, fall back to English).
 *  - Render one sentence at a time at [ReaderSettings.sentenceTextSizeSp] (defaults larger than normal).
 *  - Tap / bezel advances; same autoscroll plumbing as Normal mode.
 */
@Composable
fun SentenceMode(
    book: Book,
    settings: ReaderSettings,
) {
    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "[Sentence mode]\nFirst sentence will appear here.",
            fontSize = settings.sentenceTextSizeSp.sp,
        )
    }
}
