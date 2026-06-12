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
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.fredapp.wbooks.data.position.BookPosition
import com.fredapp.wbooks.data.settings.ReaderSettings
import com.fredapp.wbooks.parser.model.Document
import com.fredapp.wbooks.parser.model.focalIndex
import com.fredapp.wbooks.parser.model.indexAtOrAfter
import com.fredapp.wbooks.parser.model.tokenizeWords
import com.fredapp.wbooks.ui.ReaderViewModel
import com.fredapp.wbooks.ui.focus.ClaimRotaryFocusOnActive
import com.fredapp.wbooks.ui.layout.watchContentPadding
import kotlin.math.abs
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
    val words = remember(document) { document.tokenizeWords() }
    if (words.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("(no readable text)")
        }
        return
    }

    var index by remember(document) { mutableIntStateOf(words.indexAtOrAfter(initialPosition)) }
    var playing by remember { mutableStateOf(true) }
    var displayedWpm by remember { mutableIntStateOf(settings.speedreadWpm) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(settings.speedreadWpm) {
        displayedWpm = settings.speedreadWpm
    }

    LaunchedEffect(document) {
        vm.jumps.collect { target -> index = words.indexAtOrAfter(target) }
    }

    LaunchedEffect(document, words) {
        snapshotFlow { words[index].position }
            .drop(1)
            .distinctUntilChanged()
            .collect { position -> vm.reportPosition(position) }
    }

    LaunchedEffect(playing, displayedWpm) {
        if (!playing) return@LaunchedEffect
        val intervalMs = (60_000L / displayedWpm.coerceAtLeast(60)).coerceAtLeast(20L)
        while (index < words.size - 1) {
            delay(intervalMs)
            index++
        }
        playing = false
    }

    ClaimRotaryFocusOnActive(
        active = isActive,
        focusRequester = focusRequester,
        document,
        initialPosition,
        settings.mode,
    )
    val centerPadding = watchContentPadding(horizontal = 12.dp, vertical = 0.dp)

    fun stepWpm(scrollPixels: Float): Boolean {
        if (abs(scrollPixels) <= 0f) return false
        val step = if (scrollPixels > 0) WPM_STEP else -WPM_STEP
        val next = (displayedWpm + step).coerceIn(ReaderSettings.WPM_RANGE)
        if (next != displayedWpm) {
            displayedWpm = next
            onWpmChange(next)
        }
        return true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreRotaryScrollEvent { event -> stepWpm(event.verticalScrollPixels) }
            .onRotaryScrollEvent { event ->
                stepWpm(event.verticalScrollPixels)
            }
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { playing = !playing })
            },
    ) {
        Text(
            text = "$displayedWpm wpm",
            color = Color(settings.textColorArgb).copy(alpha = 0.58f),
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 14.dp, start = 48.dp, end = 48.dp)
                .fillMaxWidth(),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(centerPadding)
                .fillMaxWidth(),
        ) {
            FocalWord(
                word = words[index].text,
                fontSizeSp = ((settings.textSizeSp + 10) * settings.font.sizeScale).roundToInt(),
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
                        onClick = {
                            val next = (displayedWpm - WPM_STEP).coerceIn(ReaderSettings.WPM_RANGE)
                            displayedWpm = next
                            onWpmChange(next)
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                    Text(
                        text = "tap to play",
                        color = Color(settings.textColorArgb).copy(alpha = 0.5f),
                        style = MaterialTheme.typography.caption2,
                    )
                    CompactChip(
                        label = { Text("+25") },
                        onClick = {
                            val next = (displayedWpm + WPM_STEP).coerceIn(ReaderSettings.WPM_RANGE)
                            displayedWpm = next
                            onWpmChange(next)
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
        }
        Text(
            text = "${index + 1}/${words.size} · ${bookPercent(index, words.size)}%",
            color = Color(settings.textColorArgb).copy(alpha = 0.58f),
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(bottom = 14.dp, start = 48.dp, end = 48.dp)
                .fillMaxWidth(),
        )
    }
}

private val FOCAL_COLOR = Color(0xFFF06B5A)
private const val WPM_STEP = 25

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

// Word tokenisation, position lookup, and the RSVP focal index live in
// :reader-core (parser/model/ReaderSegmentation.kt) so the phone reader matches.

private fun bookPercent(index: Int, totalWords: Int): Int {
    if (totalWords <= 0) return 0
    return (((index + 1).toDouble() / totalWords) * 100.0).toInt().coerceIn(0, 100)
}
