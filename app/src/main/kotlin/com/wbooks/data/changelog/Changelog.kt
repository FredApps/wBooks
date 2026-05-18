package com.wbooks.data.changelog

data class ChangelogEntry(
    val version: String,
    val date: String,
    val notes: List<String>,
)

/**
 * Hand-maintained changelog. Update with each release; the Settings > Changelog
 * screen renders it top-down.
 */
val CHANGELOG: List<ChangelogEntry> = listOf(
    ChangelogEntry(
        version = "0.1.0",
        date = "2026-05-18",
        notes = listOf(
            "Initial scaffold for Wear OS 3+ (Galaxy Watch 6 target).",
            "HorizontalPager nav (Tools | Reader | Settings).",
            "txt, html / xhtml, epub parsers; fb2 parser.",
            "Normal mode: paragraph layout with bold / italic / dividers, code-block syntax colouring, bezel scroll, tap-to-page, autoscroll.",
            "Speed-reading mode (RSVP) with focal-point letter and live bezel-WPM control.",
            "Sentence mode with BreakIterator segmentation and autoscroll.",
            "Per-book reading position, bookmarks (long-press to delete), chapter jump from the Tools page.",
            "Document-wide search via voice / keyboard input.",
            "On-watch upload server (LAN, PIN-gated) for transferring books without ADB.",
        ),
    ),
)
