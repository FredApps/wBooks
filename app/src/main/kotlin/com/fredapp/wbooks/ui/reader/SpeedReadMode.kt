package com.fredapp.wbooks.ui.reader

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ChipDefaults
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

/**
 * RSVP â€” one word at a time, focal letter coloured and pinned to screen centre.
 *
 * Interactions:
 *  - Tap: toggle play / pause.
 *  - Rotary bezel: adjust WPM live (persisted via [onWpmChange]).
 *
 * Focal point: the optimal recognition point per the classic RSVP rule (Spritz
 * et al.). The word is rendered as a three-cell Row â€” prefix (weight, end-aligned),
 * focal letter (centre, accented colour), suffix (weight, start-aligned) â€” which
 * places the focal letter on the screen's vertical axis without manual measuring.
 */
@Composable
fun SpeedReadMode(
    document: Document,
    initialPosition: BookPosition,
    settings: ReaderSettings,
    vm: ReaderViewModel,
    isActive: Boolean,
    onWpmChange: (Int) -> Unit,
) {
    val words = remember(document) { tokenize(document) }
    if (words.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("(no readable text)")
        }
        return
    }

    var index by remember(document) { mutableIntStateOf(wordIndexFor(words, initialPosition)) }
    var playing by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(document) {
        vm.jumps.collect { target -> index = wordIndexFor(words, target) }
    }

    LaunchedEffect(document, words) {
        snapshotFlow { words[index].position }
            .drop(1)
            .distinctUntilChanged()
            .collect { position -> vm.reportPosition(position) }
    }

    LaunchedEffect(playing, settings.speedreadWpm) {
        if (!playing) return@LaunchedEffect
        val intervalMs = (60_000L / settings.speedreadWpm.coerceAtLeast(60)).coerceAtLeast(20L)
        while (index < words.size - 1) {
            delay(intervalMs)
            index++
        }
        playing = false
    }

    ClaimRotaryFocusOnActive(active = isActive, focusRequester = focusRequester)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onRotaryScrollEvent { event ->
                val delta = (event.verticalScrollPixels / 5f).toInt()
                if (delta != 0) onWpmChange(settings.speedreadWpm + delta)
                true
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { playing = !playing })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
        ) {
            Text(
                text = "${settings.speedreadWpm} wpm - ${index + 1}/${words.size}",
                color = Color(settings.textColorArgb).copy(alpha = 0.5f),
                style = MaterialTheme.typography.caption2,
            )
            FocalWord(
                word = words[index].text,
                fontSizeSp = settings.textSizeSp + 10,
                baseColor = Color(settings.textColorArgb),
                focalColor = FOCAL_COLOR,
            )
            if (!playing) {
                // Touch-only WPM control: visible only when paused so it doesn't
                // distract during reading. Bezel users adjust WPM live without pausing.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    CompactChip(
                        label = { Text("-25") },
                        onClick = { onWpmChange(settings.speedreadWpm - 25) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                    Text(
                        text = "tap to play",
                        color = Color(settings.textColorArgb).copy(alpha = 0.5f),
                        style = MaterialTheme.typography.caption2,
                    )
                    CompactChip(
                        label = { Text("+25") },
                        onClick = { onWpmChange(settings.speedreadWpm + 25) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
        }
    }
}

private val FOCAL_COLOR = Color(0xFFF06B5A)

private data class WordItem(val text: String, val position: BookPosition)

@Composable
private fun FocalWord(
    word: String,
    fontSizeSp: Int,
    baseColor: Color,
    focalColor: Color,
) {
    val orp = focalIndex(word)
    val prefix = word.substring(0, orp)
    val focal = word[orp].toString()
    val suffix = if (orp + 1 <= word.lastIndex) word.substring(orp + 1) else ""

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = prefix,
                color = baseColor,
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        Text(
            text = focal,
            color = focalColor,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Text(
                text = suffix,
                color = baseColor,
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
        }
    }
}

/**
 * Optimal recognition point. Standard RSVP table:
 *   1 char      -> 0
 *   2-5 chars   -> 1
 *   6-9 chars   -> 2
 *   10-13 chars -> 3
 *   14+ chars   -> 4
 */
internal fun focalIndex(word: String): Int = when (word.length) {
    0, 1 -> 0
    in 2..5 -> 1
    in 6..9 -> 2
    in 10..13 -> 3
    else -> 4
}.coerceAtMost(word.lastIndex.coerceAtLeast(0))

private fun tokenize(doc: Document): List<WordItem> {
    val ws = Regex("\\s+")
    val out = mutableListOf<WordItem>()
    for ((ci, chapter) in doc.chapters.withIndex()) {
        for ((bi, block) in chapter.blocks.withIndex()) {
            val text = when (block) {
                is Block.Heading -> block.text
                is Block.Paragraph -> block.runs.joinToString("") { it.text }
                Block.Divider, is Block.Code -> ""
            }
            if (text.isNotBlank()) {
                val position = BookPosition(ci, bi)
                out.addAll(text.trim().split(ws).filter { it.isNotEmpty() }.map { WordItem(it, position) })
            }
        }
    }
    return out
}

private fun wordIndexFor(words: List<WordItem>, target: BookPosition): Int {
    val i = words.indexOfFirst { word ->
        word.position.chapterIndex > target.chapterIndex ||
            (word.position.chapterIndex == target.chapterIndex && word.position.blockIndex >= target.blockIndex)
    }
    return if (i >= 0) i else 0
}
