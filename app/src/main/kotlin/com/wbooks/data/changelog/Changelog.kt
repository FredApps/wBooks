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
        version = "0.3.0",
        date = "2026-05-19",
        notes = listOf(
            "Phone companion app (separate APK). Mirrors the watch's library; SAF picker sends books to the watch over the Wear Data Layer (no IP / PIN). Material 3, dark-mode aware, minSdk 24.",
            "Watch-side BookReceiverService accepts uploads via ChannelClient and list / delete via MessageClient.sendRequest. Scope is cancelled on service teardown; binder thread is no longer blocked.",
            "LAN upload server (NanoHTTPD) stays as an additive transport — companion is an alternative, not a replacement.",
        ),
    ),
    ChangelogEntry(
        version = "0.2.0",
        date = "2026-05-19",
        notes = listOf(
            "DOCX and ODT support (Office Open XML, OpenDocument Text). Headings split into chapters, inline bold / italic / underline preserved, unsupported elements (tables, images, frames, ...) silently dropped.",
            "Two new bundled seed books: Stevenson's Jekyll & Hyde (DOCX) and Wells's The Time Machine (ODT) — pandoc-converted from Project Gutenberg, header / license boilerplate stripped.",
            "Upload server hardened: PIN now checked before multipart body is spooled; sliding-window rate limiter (10 wrong PINs / 60s -> 429); constant-time PIN compare; HTML escaping on the library listing.",
            "Repo housekeeping: GPLv3 LICENSE at root, GitHub Actions CI (assembleDebug + JVM unit tests for the parsers).",
        ),
    ),
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
