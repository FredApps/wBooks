package com.wbooks.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.wbooks.data.settings.ReaderSettings
import com.wbooks.parser.model.Document

/**
 * Speed-reading mode (RSVP). Planned:
 *  - Tokenize the loaded [Document] once; stream one word at a time at
 *    [ReaderSettings.speedreadWpm].
 *  - Rotary bezel adjusts WPM live via [onWpmChange]; tap toggles play/pause.
 */
@Composable
fun SpeedReadMode(
    document: Document,
    settings: ReaderSettings,
    onWpmChange: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "${settings.speedreadWpm} wpm",
            fontSize = (settings.textSizeSp + 8).sp,
            color = Color(settings.textColorArgb),
        )
    }
}
