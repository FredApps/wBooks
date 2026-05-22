package com.fredapp.wbooks.data.settings

enum class ReadingMode { NORMAL, SPEEDREAD, SENTENCE }

/** Cycle to the next reading mode (used by the Settings UI's mode chip). */
fun ReadingMode.next(): ReadingMode = when (this) {
    ReadingMode.NORMAL -> ReadingMode.SPEEDREAD
    ReadingMode.SPEEDREAD -> ReadingMode.SENTENCE
    ReadingMode.SENTENCE -> ReadingMode.NORMAL
}

enum class FontChoice(val familyName: String) {
    DEFAULT("default"),
    SERIF("serif"),
    SANS("sans-serif"),
    MONO("monospace"),
    CURSIVE("cursive");

    fun next(): FontChoice = entries[(ordinal + 1) % entries.size]
}

/**
 * Persisted reader configuration. Stored in DataStore<Preferences>; see SettingsRepository.
 *
 * Deliberately free of Compose / Android imports so the data layer stays portable and
 * trivial to unit-test on the JVM.
 *
 * @property textColorArgb ARGB-packed integer. UI maps to androidx.compose.ui.graphics.Color.
 * @property autoscrollSpeed 1..60. Interpreted per-mode: lines/min in Normal & Sentence modes.
 */
data class ReaderSettings(
    val mode: ReadingMode = ReadingMode.NORMAL,
    val font: FontChoice = FontChoice.SERIF,
    val textSizeSp: Int = 16,
    val sentenceTextSizeSp: Int = 22,
    val textColorArgb: Int = 0xFFD4C19C.toInt(),
    val autoscrollEnabled: Boolean = false,
    val autoscrollSpeed: Int = 20,
    val screenBrightness: Int = 100,
    /** Words per minute in speedread mode. */
    val speedreadWpm: Int = 300,
    /**
     * Minutes of inactivity before Normal/Sentence mode releases keep-awake
     * and the app moves itself to the background. Speedread ignores this and
     * stays awake indefinitely while it is the active mode.
     */
    val keepAwakeMinutes: Int = 5,
) {
    companion object {
        val TEXT_SIZE_RANGE = 10..36
        val SENTENCE_TEXT_SIZE_RANGE = 14..48
        val AUTOSCROLL_SPEED_RANGE = 1..60
        val SCREEN_BRIGHTNESS_RANGE = 10..100
        val WPM_RANGE = 100..900
        val KEEP_AWAKE_MINUTES_RANGE = 1..10

        /** Curated text-colour palette the user can cycle through in settings. */
        val TEXT_COLOR_PALETTE: List<Int> = listOf(
            0xFFD4C19C.toInt(), // sepia (default)
            0xFFFFFFFF.toInt(), // pure white
            0xFFB0B0B0.toInt(), // grey
            0xFFE8E6E1.toInt(), // warm white legacy value
            0xFF9CB5D4.toInt(), // pale blue
            0xFFA8D49C.toInt(), // pale green
            0xFFD49C9C.toInt(), // pale red
        )
    }
}

/** Returns the palette index immediately after [current], wrapping. */
fun nextTextColor(current: Int): Int {
    val idx = ReaderSettings.TEXT_COLOR_PALETTE.indexOf(current)
    val next = if (idx < 0) 0 else (idx + 1) % ReaderSettings.TEXT_COLOR_PALETTE.size
    return ReaderSettings.TEXT_COLOR_PALETTE[next]
}
