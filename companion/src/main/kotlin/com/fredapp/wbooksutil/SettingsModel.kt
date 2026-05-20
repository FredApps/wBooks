package com.fredapp.wbooksutil

/**
 * Companion-side mirror of `:app`'s settings enums and ranges. Duplicated by
 * hand following the project's "no shared module" convention â€” same as
 * [WearProtocol]. When the watch adds a new ReadingMode/FontChoice/ThemeChoice
 * value or shifts a range, mirror it here so the phone UI stays in sync.
 */
enum class ReadingMode { NORMAL, SPEEDREAD, SENTENCE }
enum class ThemeChoice { DARK, LIGHT, SYSTEM }
enum class FontChoice(val familyName: String) {
    DEFAULT("default"),
    SERIF("serif"),
    SANS("sans-serif"),
    MONO("monospace"),
    CURSIVE("cursive"),
}

object SettingsRanges {
    val TEXT_SIZE = 10..36
    val SENTENCE_TEXT_SIZE = 14..48
    val AUTOSCROLL_SPEED = 1..60
    val SCREEN_BRIGHTNESS = 10..100
    val WPM = 100..900

    /** Same curated palette as the watch. Stays in sync by hand. */
    val TEXT_COLOR_PALETTE: List<Int> = listOf(
        0xFFE8E6E1.toInt(),
        0xFFFFFFFF.toInt(),
        0xFFD4C19C.toInt(),
        0xFF9CB5D4.toInt(),
        0xFFA8D49C.toInt(),
        0xFFD49C9C.toInt(),
    )
}

fun colorName(argb: Int): String = when (argb) {
    0xFFE8E6E1.toInt() -> "Warm white"
    0xFFFFFFFF.toInt() -> "White"
    0xFFD4C19C.toInt() -> "Sepia"
    0xFF9CB5D4.toInt() -> "Pale blue"
    0xFFA8D49C.toInt() -> "Pale green"
    0xFFD49C9C.toInt() -> "Pale red"
    else -> "Custom"
}
