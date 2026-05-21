package com.fredapp.wbooks.ui.reader

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.fredapp.wbooks.data.position.BookPosition
import com.fredapp.wbooks.data.settings.ReaderSettings
import com.fredapp.wbooks.parser.model.Block
import com.fredapp.wbooks.parser.model.Document
import com.fredapp.wbooks.ui.ReaderViewModel
import com.fredapp.wbooks.ui.focus.ClaimRotaryFocusOnActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

private data class SentenceItem(val text: String, val position: BookPosition)

/**
 * One sentence at a time, larger text.
 *
 * Interactions:
 *  - Tap (autoscroll OFF): next sentence.
 *  - Tap (autoscroll ON): toggle pause.
 *  - Rotary forward / back: next / previous sentence.
 *  - Autoscroll: when on, advance every (60 / autoscrollSpeed) seconds.
 *
 * Position plumbing:
 *  - On book open: jump to the first sentence at-or-after the saved block position.
 *  - On advance: report the new sentence's block position back to the VM.
 *  - On vm.jumps: jump to the first sentence at the target block position.
 */
@Composable
fun SentenceMode(
    document: Document,
    initialPosition: BookPosition,
    settings: ReaderSettings,
    vm: ReaderViewModel,
    isActive: Boolean,
) {
    val sentences = remember(document) { segmentSentences(document) }
    if (sentences.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("(no readable text)")
        }
        return
    }

    var index by remember(document) {
        mutableIntStateOf(sentenceIndexFor(sentences, initialPosition))
    }
    var autoscrollPaused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    ClaimRotaryFocusOnActive(active = isActive, focusRequester = focusRequester)

    // Handle external jumps (chapter list, bookmark tap).
    LaunchedEffect(document) {
        vm.jumps.collect { target -> index = sentenceIndexFor(sentences, target) }
    }

    // Report position back to the VM as the user advances.
    LaunchedEffect(document, sentences) {
        snapshotFlow { index }
            .drop(1)
            .distinctUntilChanged()
            .collect { i -> vm.reportPosition(sentences[i].position) }
    }

    // Autoscroll: one sentence per (60 / speed) seconds.
    LaunchedEffect(settings.autoscrollEnabled, settings.autoscrollSpeed, autoscrollPaused) {
        if (!settings.autoscrollEnabled || autoscrollPaused) return@LaunchedEffect
        val intervalMs = (60_000L / settings.autoscrollSpeed.coerceAtLeast(1)).coerceAtLeast(150L)
        while (index < sentences.size - 1) {
            delay(intervalMs)
            index = (index + 1).coerceAtMost(sentences.size - 1)
        }
    }

    fun stepSentence(scrollPixels: Float): Boolean {
        if (abs(scrollPixels) <= 0f) return false
        val direction = if (scrollPixels > 0) 1 else -1
        index = (index + direction).coerceIn(0, sentences.size - 1)
        return true
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onPreRotaryScrollEvent { event -> stepSentence(event.verticalScrollPixels) }
            .onRotaryScrollEvent { event ->
                stepSentence(event.verticalScrollPixels)
            }
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(settings.autoscrollEnabled) {
                detectTapGestures(onTap = { offset ->
                    if (settings.autoscrollEnabled) {
                        // Spec: autoscroll pauses on tap, resumes on tap.
                        autoscrollPaused = !autoscrollPaused
                    } else {
                        // Touch-only navigation: top third of the screen taps to the
                        // previous sentence, bottom two thirds tap to the next.
                        val prevZone = offset.y < size.height / 3f
                        index = if (prevZone) (index - 1).coerceAtLeast(0)
                        else (index + 1).coerceAtMost(sentences.size - 1)
                    }
                })
            },
    ) {
        val sentenceTextMaxHeight = (maxHeight - 58.dp).coerceAtLeast(48.dp)
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            Text(
                text = "${index + 1}/${sentences.size}",
                color = Color(settings.textColorArgb).copy(alpha = 0.5f),
                style = MaterialTheme.typography.caption2,
            )
            FittingSentenceText(
                text = sentences[index].text,
                color = Color(settings.textColorArgb),
                targetFontSizeSp = settings.sentenceTextSizeSp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = sentenceTextMaxHeight),
            )
            if (settings.autoscrollEnabled && autoscrollPaused) {
                Text(
                    text = "tap to resume",
                    color = Color(settings.textColorArgb).copy(alpha = 0.4f),
                    style = MaterialTheme.typography.caption2,
                )
            }
        }
    }
}

@Composable
private fun FittingSentenceText(
    text: String,
    color: Color,
    targetFontSizeSp: Int,
    modifier: Modifier = Modifier,
) {
    val minFontSizeSp = 12
    var displayText by remember(text) { mutableStateOf(text) }
    var fontSizeSp by remember(text, targetFontSizeSp) { mutableIntStateOf(targetFontSizeSp) }
    var commaSplitTried by remember(text) { mutableStateOf(false) }

    Text(
        text = displayText,
        color = color,
        fontSize = fontSizeSp.sp,
        textAlign = TextAlign.Center,
        overflow = TextOverflow.Clip,
        modifier = modifier,
        onTextLayout = { result ->
            if (!result.hasVisualOverflow) return@Text
            if (fontSizeSp > minFontSizeSp) {
                fontSizeSp--
                return@Text
            }
            if (!commaSplitTried) {
                displayText = text.withCenterCommaLineBreak()
                commaSplitTried = true
            }
        },
    )
}

private fun String.withCenterCommaLineBreak(): String {
    val commaIndexes = indices.filter { this[it] == ',' }
    if (commaIndexes.isEmpty()) return this
    val midpoint = length / 2
    val splitAt = commaIndexes.minBy { kotlin.math.abs(it - midpoint) }
    return substring(0, splitAt + 1).trimEnd() + "\n" + substring(splitAt + 1).trimStart()
}

private fun segmentSentences(doc: Document): List<SentenceItem> {
    val iter = BreakIterator.getSentenceInstance(Locale.getDefault())
    val out = mutableListOf<SentenceItem>()
    for ((ci, chapter) in doc.chapters.withIndex()) {
        for ((bi, block) in chapter.blocks.withIndex()) {
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
                if (sentence.isNotEmpty()) out.add(SentenceItem(sentence, BookPosition(ci, bi)))
                start = end
                end = iter.next()
            }
        }
    }
    return out
}

/** First sentence whose origin is at or after [target] (lexicographic on chapter, then block). */
private fun sentenceIndexFor(sentences: List<SentenceItem>, target: BookPosition): Int {
    val i = sentences.indexOfFirst { s ->
        s.position.chapterIndex > target.chapterIndex ||
            (s.position.chapterIndex == target.chapterIndex && s.position.blockIndex >= target.blockIndex)
    }
    return if (i >= 0) i else 0
}
