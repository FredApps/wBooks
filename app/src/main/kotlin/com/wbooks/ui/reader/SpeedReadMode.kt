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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wbooks.data.settings.ReaderSettings
import com.wbooks.parser.model.Block
import com.wbooks.parser.model.Document
import kotlinx.coroutines.delay

/**
 * Rapid Serial Visual Presentation — one word at a time, centred, advancing at
 * [ReaderSettings.speedreadWpm].
 *
 * Interactions:
 *  - Tap: toggle play/pause.
 *  - Rotary bezel: adjust WPM live (each detent ~25 wpm). The change is pushed
 *    back via [onWpmChange] so it persists in DataStore.
 *
 * Tokenisation includes heading text (so chapter titles get a beat at their
 * speed) but skips dividers and code blocks — code isn't meant to be RSVP-read.
 */
@Composable
fun SpeedReadMode(
    document: Document,
    settings: ReaderSettings,
    onWpmChange: (Int) -> Unit,
) {
    val words = remember(document) { tokenize(document) }
    if (words.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("(no readable text)")
        }
        return
    }

    var index by remember(document) { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(playing, settings.speedreadWpm) {
        if (!playing) return@LaunchedEffect
        val intervalMs = (60_000L / settings.speedreadWpm.coerceAtLeast(60)).coerceAtLeast(20L)
        while (index < words.size - 1) {
            delay(intervalMs)
            index++
        }
        playing = false
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = "${settings.speedreadWpm} wpm  ·  ${index + 1}/${words.size}",
                color = Color(settings.textColorArgb).copy(alpha = 0.5f),
                style = MaterialTheme.typography.caption2,
            )
            Text(
                text = words[index],
                color = Color(settings.textColorArgb),
                fontSize = (settings.textSizeSp + 10).sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            if (!playing) {
                Text(
                    text = "tap to play",
                    color = Color(settings.textColorArgb).copy(alpha = 0.4f),
                    style = MaterialTheme.typography.caption2,
                )
            }
        }
    }
}

private fun tokenize(doc: Document): List<String> {
    val ws = Regex("\\s+")
    val out = mutableListOf<String>()
    for (chapter in doc.chapters) {
        for (block in chapter.blocks) {
            val text = when (block) {
                is Block.Heading -> block.text
                is Block.Paragraph -> block.runs.joinToString("") { it.text }
                Block.Divider, is Block.Code -> ""
            }
            if (text.isNotBlank()) {
                out.addAll(text.trim().split(ws).filter { it.isNotEmpty() })
            }
        }
    }
    return out
}
