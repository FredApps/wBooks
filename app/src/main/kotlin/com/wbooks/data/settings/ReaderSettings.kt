package com.wbooks.data.settings

import androidx.compose.ui.graphics.Color

enum class ReadingMode { NORMAL, SPEEDREAD, SENTENCE }

/**
 * Persisted reader configuration. Will be backed by DataStore<Preferences>;
 * for now this is a plain data class so the UI can be wired against a stable shape.
 */
data class ReaderSettings(
    val mode: ReadingMode = ReadingMode.NORMAL,
    val fontFamily: String = "serif",
    val textSizeSp: Int = 16,
    val sentenceTextSizeSp: Int = 22,
    val textColor: Color = Color(0xFFE8E6E1),
    val autoscrollEnabled: Boolean = false,
    /** Lines per minute for normal/sentence autoscroll. */
    val autoscrollLpm: Int = 20,
    /** Words per minute in speedread mode. */
    val speedreadWpm: Int = 300,
)
