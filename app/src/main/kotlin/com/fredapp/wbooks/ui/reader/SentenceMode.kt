package com.fredapp.wbooks.ui.reader

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import com.fredapp.wbooks.ui.layout.watchContentPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlin.math.abs

private data class SentenceItem(val text: String, val position: BookPosition)
private const val SENTENCE_SWIPE_THRESHOLD_PX = 36f
private const val AUTOSCROLL_SPEED_STEP = 1

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
    onAutoscrollSpeedChange: (Int) -> Unit,
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

    fun stepAutoscrollSpeed(scrollPixels: Float): Boolean {
        if (!settings.autoscrollEnabled || autoscrollPaused || abs(scrollPixels) <= 0f) return false
        val step = if (scrollPixels > 0) AUTOSCROLL_SPEED_STEP else -AUTOSCROLL_SPEED_STEP
        val next = (settings.autoscrollSpeed + step).coerceIn(ReaderSettings.AUTOSCROLL_SPEED_RANGE)
        if (next != settings.autoscrollSpeed) onAutoscrollSpeedChange(next)
        return true
    }

    fun advanceSentence() {
        index = (index + 1).coerceAtMost(sentences.size - 1)
    }

    fun rewindSentence() {
        index = (index - 1).coerceAtLeast(0)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onPreRotaryScrollEvent { event ->
                stepAutoscrollSpeed(event.verticalScrollPixels) || stepSentence(event.verticalScrollPixels)
            }
            .onRotaryScrollEvent { event ->
                stepAutoscrollSpeed(event.verticalScrollPixels) || stepSentence(event.verticalScrollPixels)
            }
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(settings.autoscrollEnabled) {
                detectTapGestures(onTap = {
                    if (settings.autoscrollEnabled) autoscrollPaused = !autoscrollPaused
                    else advanceSentence()
                })
            }
            .pointerInput(Unit) {
                var totalDragY = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalDragY = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        totalDragY += dragAmount
                        change.consume()
                    },
                    onDragEnd = {
                        when {
                            totalDragY <= -SENTENCE_SWIPE_THRESHOLD_PX -> advanceSentence()
                            totalDragY >= SENTENCE_SWIPE_THRESHOLD_PX -> rewindSentence()
                        }
                    },
                    onDragCancel = { totalDragY = 0f },
                )
            },
    ) {
        val contentPadding = watchContentPadding(horizontal = 12.dp, vertical = 12.dp)
        // Reserve a fixed strip at the bottom for the counter so the sentence
        // text never collides with it, regardless of sentence length or font
        // size. The counter never moves.
        val counterStripHeight = 24.dp
        val sentenceTextMaxHeight =
            (maxHeight - counterStripHeight - 32.dp).coerceAtLeast(48.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = counterStripHeight),
                contentAlignment = Alignment.Center,
            ) {
                FittingSentenceText(
                    text = sentences[index].text,
                    color = Color(settings.textColorArgb),
                    targetFontSizeSp = settings.sentenceTextSizeSp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = sentenceTextMaxHeight),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (settings.autoscrollEnabled && autoscrollPaused) {
                    Text(
                        text = "tap to resume",
                        color = Color(settings.textColorArgb).copy(alpha = 0.4f),
                        style = MaterialTheme.typography.caption2,
                    )
                }
                Text(
                    text = "${index + 1}/${sentences.size}",
                    color = Color(settings.textColorArgb).copy(alpha = 0.5f),
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
    val variants = remember(text) { text.fitVariants() }
    var variantIndex by remember(text) { mutableIntStateOf(0) }
    var fontSizeSp by remember(text, targetFontSizeSp, variantIndex) { mutableIntStateOf(targetFontSizeSp) }

    Text(
        text = variants[variantIndex],
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
            if (variantIndex < variants.lastIndex) {
                variantIndex++
            }
        },
    )
}

private fun String.fitVariants(): List<String> {
    val variants = mutableListOf(this)
    splitAfterToken(".\"")?.let { variants += it }
    splitAfterToken(",")?.let { commaSplit ->
        if (commaSplit !in variants) variants += commaSplit
    }
    return variants
}

private fun String.splitAfterToken(token: String): String? {
    val splitAt = tokenStartIndexes(token)
        .minByOrNull { kotlin.math.abs(it - length / 2) }
        ?: return null
    val end = splitAt + token.length
    return substring(0, end).trimEnd() + "\n" + substring(end).trimStart()
}

private fun String.tokenStartIndexes(token: String): List<Int> {
    val out = mutableListOf<Int>()
    var from = 0
    while (from <= length - token.length) {
        val idx = indexOf(token, startIndex = from)
        if (idx < 0) break
        out += idx
        from = idx + token.length
    }
    return out
}

private fun segmentSentences(doc: Document): List<SentenceItem> {
    val out = mutableListOf<SentenceItem>()
    for ((ci, chapter) in doc.chapters.withIndex()) {
        for ((bi, block) in chapter.blocks.withIndex()) {
            val text = when (block) {
                is Block.Heading -> block.text
                is Block.Paragraph -> block.runs.joinToString("") { it.text }
                Block.Divider, is Block.Code -> ""
            }.trim()
            if (text.isEmpty()) continue

            var subIndex = 0
            for (part in text.splitAtPunctuation()) {
                if (part.isEmpty()) continue
                out.add(SentenceItem(part, BookPosition(ci, bi, subIndex)))
                subIndex++
            }
        }
    }
    return out
}

/**
 * Always break after `.`, `."`, `,`, `,"` when followed by whitespace or end.
 * The trailing-space requirement keeps decimals like "3.14" and abbreviations
 * like "Mr.Smith" intact, while still splitting at every real sentence /
 * clause boundary.
 *
 * Quote characters include straight ("/') and curly (U+201C/D/U+2018/9) so
 * ebooks that use smart quotes still split after `."`.
 *
 * Fragments shorter than [MIN_FRAGMENT_SPACES] inter-word spaces are not
 * emitted as their own screen — instead the break is skipped and the
 * fragment keeps growing until it reaches the next valid boundary.
 */
private const val MIN_FRAGMENT_SPACES = 3

private fun Char.isCloseQuote(): Boolean =
    this == '"' || this == '\'' || this == '“' || this == '”' || this == '‘' || this == '’'

private fun String.splitAtPunctuation(): List<String> {
    val pieces = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '.' || c == ',') {
            var end = i + 1
            if (end < length && this[end].isCloseQuote()) end++
            val nextIsBoundary = end >= length || this[end].isWhitespace()
            if (nextIsBoundary) {
                val fragment = substring(start, end).trim()
                if (fragment.isNotEmpty() && fragment.count { it == ' ' } >= MIN_FRAGMENT_SPACES) {
                    pieces += fragment
                    start = end
                }
                i = end
                continue
            }
        }
        i++
    }
    substring(start).trim().takeIf { it.isNotEmpty() }?.let { pieces += it }
    return pieces.ifEmpty { listOf(trim()) }
}

/**
 * First sentence at or after [target], compared lexicographically on
 * (chapter, block, sub). Matching the sub-index lets bookmarks made in
 * sentence mode land back on the exact sentence — not just the start of
 * the paragraph.
 */
private fun sentenceIndexFor(sentences: List<SentenceItem>, target: BookPosition): Int {
    val i = sentences.indexOfFirst { s ->
        val p = s.position
        when {
            p.chapterIndex != target.chapterIndex -> p.chapterIndex > target.chapterIndex
            p.blockIndex != target.blockIndex -> p.blockIndex > target.blockIndex
            else -> p.subIndex >= target.subIndex
        }
    }
    return if (i >= 0) i else 0
}
