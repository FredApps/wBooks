package com.wbooks.ui.reader

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wbooks.data.settings.ReaderSettings
import com.wbooks.parser.model.Block
import com.wbooks.parser.model.Document
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

/**
 * One sentence at a time, larger text. Tap or bezel forward → next sentence;
 * bezel backward → previous. Autoscroll plumbing lives here too (not wired yet).
 *
 * Sentences come from [BreakIterator] (locale: device default) over each
 * paragraph's plain text. Headings are kept as their own one-sentence "blocks"
 * so chapter titles still get their own beat.
 */
@Composable
fun SentenceMode(
    document: Document,
    settings: ReaderSettings,
) {
    val sentences = remember(document) { segmentSentences(document) }
    if (sentences.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("(no readable text)")
        }
        return
    }

    var index by remember(document) { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onRotaryScrollEvent { event ->
                val direction = if (event.verticalScrollPixels > 0) 1 else -1
                // One sentence per detent — guard against bursts by requiring a min magnitude.
                if (abs(event.verticalScrollPixels) >= 5f) {
                    index = (index + direction).coerceIn(0, sentences.size - 1)
                }
                true
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    if (index < sentences.size - 1) index++
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = "${index + 1}/${sentences.size}",
                color = Color(settings.textColorArgb).copy(alpha = 0.5f),
                style = MaterialTheme.typography.caption2,
            )
            Text(
                text = sentences[index],
                color = Color(settings.textColorArgb),
                fontSize = settings.sentenceTextSizeSp.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun segmentSentences(doc: Document): List<String> {
    val iter = BreakIterator.getSentenceInstance(Locale.getDefault())
    val out = mutableListOf<String>()
    for (chapter in doc.chapters) {
        for (block in chapter.blocks) {
            val text = when (block) {
                is Block.Heading -> block.text
                is Block.Paragraph -> block.runs.joinToString("") { it.text }
                Block.Divider, is Block.Code -> ""
            }.trim()
            if (text.isEmpty()) continue

            iter.setText(text)
            var start = iter.first()
            var end = iter.next()
            while (end != BreakIterator.DONE) {
                val sentence = text.substring(start, end).trim()
                if (sentence.isNotEmpty()) out.add(sentence)
                start = end
                end = iter.next()
            }
        }
    }
    return out
}
