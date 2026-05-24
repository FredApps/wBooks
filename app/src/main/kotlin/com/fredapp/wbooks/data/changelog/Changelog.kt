package com.fredapp.wbooks.data.changelog

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
        version = "0.8.0",
        date = "2026-05-24",
        notes = listOf(
            "Watch Search page now includes Project Gutenberg search, popular books, and recent releases directly on the watch.",
            "Web and Utility library views now show watch library storage used, free space, and total disk space.",
            "Web and Utility sorting now use the same drag behavior: dropping within a folder can place before or after a book, while moving into a folder places the book first.",
            "Watch library book titles now wrap across multiple lines.",
        ),
    ),
    ChangelogEntry(
        version = "0.7.0",
        date = "2026-05-23",
        notes = listOf(
            "Book opening/parsing timeout increased to two minutes for large first-open conversions.",
            "The watch web server now starts only on Wi-Fi, refuses paired-phone Bluetooth and LTE paths, and binds only to the selected Wi-Fi address.",
            "Web settings now render the same About and Changelog content as the watch, including seed books, GPLv3, Gutenberg notice, and open-source attribution.",
            "Bookmark labels now match reading mode: chapters in Normal, sentences in Sentence mode, and exact words in Speed Reading; Speed Reading also shows book percent finished.",
            "Web library books can be reordered by dragging within a folder; the order is reflected on the watch, and the watch library footer shows library storage used plus device space left.",
            "Watch-side Wear uploads now verify the expected byte count and delete partial files when a phone-side Gutenberg add is canceled or disconnected.",
            "Phone-side Gutenberg retries can explicitly overwrite a canceled upload instead of creating duplicate numbered files.",
        ),
    ),
    ChangelogEntry(
        version = "0.5.1",
        date = "2026-05-22",
        notes = listOf(
            "Web interface cleanup: uploads from Add books now go to Root, drag-and-drop still targets folders, per-book moves use a compact Move action, and PDF guidance appears only when a PDF is uploaded.",
            "Web interface now includes the watch How to use guide and alerts the browser if the watch-side webserver is disabled while the page is open.",
            "Web interface now starts only on active Wi-Fi, avoiding paired-phone Bluetooth and LTE addresses that browsers cannot reach.",
            "Opening a freshly parsed book can now run for up to two minutes before timing out.",
        ),
    ),
    ChangelogEntry(
        version = "0.4.0",
        date = "2026-05-19",
        notes = listOf(
            "Time-to-finish estimate on the Tools page: per-book exponential moving average of ms-per-block-advance turns into \"~ Xm in chapter / ~ Xh Ym in book\". RSVP and chapter jumps are filtered out so the EMA tracks actual reading pace.",
            "Reading-time tile and watch-face complication surfacing today's minutes; both read the same daily totals from a new ReadingStatsRepository.",
            "Books-finished counter, daily reading totals (30-day rolling window), and last-50 WPM samples now persisted on the watch and exposed over a new /wbooks/stats Wear Data Layer path.",
            "Phone companion gains a stats dashboard: Today / Total / Finished cards, a 30-day bar chart of reading minutes, and a WPM-trend line chart (both pure Compose Canvas).",
            "Sentry SDK wired into both APKs; DSN sourced from local.properties so the project builds without an account.",
            "Phone-side Project Gutenberg browser: search the OPDS catalogue and stream a downloaded book straight to the watch via ChannelClient - no intermediate temp file.",
            "Release builds run through R8 + resource shrinking for both modules.",
        ),
    ),
    ChangelogEntry(
        version = "0.3.0",
        date = "2026-05-19",
        notes = listOf(
            "Phone companion app (separate APK). Mirrors the watch's library; SAF picker sends books to the watch over the Wear Data Layer (no IP / PIN). Material 3, dark-mode aware, minSdk 24.",
            "Watch-side BookReceiverService accepts uploads via ChannelClient and list / delete via MessageClient.sendRequest. Scope is cancelled on service teardown; binder thread is no longer blocked.",
            "LAN upload server (NanoHTTPD) stays as an additive transport - companion is an alternative, not a replacement.",
        ),
    ),
    ChangelogEntry(
        version = "0.2.0",
        date = "2026-05-19",
        notes = listOf(
            "DOCX and ODT support (Office Open XML, OpenDocument Text). Headings split into chapters, inline bold / italic / underline preserved, unsupported elements (tables, images, frames, ...) silently dropped.",
            "Two new bundled seed books: Stevenson's Jekyll & Hyde (DOCX) and Wells's The Time Machine (ODT) - pandoc-converted from Project Gutenberg, header / license boilerplate stripped.",
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
