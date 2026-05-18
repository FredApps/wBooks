package com.wbooks.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.wbooks.data.settings.ReaderSettings
import com.wbooks.parser.model.Document

/**
 * Sentence mode. Planned:
 *  - Segment the loaded [Document]'s paragraphs into sentences via BreakIterator.
 *  - Render one sentence at a time at [ReaderSettings.sentenceTextSizeSp].
 *  - Tap / bezel advances; same autoscroll plumbing as Normal mode.
 */
@Composable
fun SentenceMode(
    document: Document,
    settings: ReaderSettings,
) {
    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "[Sentence mode]\nFirst sentence will appear here.",
            fontSize = settings.sentenceTextSizeSp.sp,
            color = Color(settings.textColorArgb),
        )
    }
}
