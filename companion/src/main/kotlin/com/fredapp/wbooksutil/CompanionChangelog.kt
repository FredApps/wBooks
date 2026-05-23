package com.fredapp.wbooksutil

/**
 * Phone-companion changelog. Independent from the watch's `Changelog.kt` because
 * the two surfaces ship as separate APKs and gain features on different cadences.
 * Update with each release; SettingsScreen renders it top-down.
 */
data class CompanionChangelogEntry(
    val version: String,
    val date: String,
    val notes: List<String>,
)

object CompanionChangelog {
    val ENTRIES: List<CompanionChangelogEntry> = listOf(
        CompanionChangelogEntry(
            version = "0.7.0",
            date = "2026-05-23",
            notes = listOf(
                "Utility can receive supported book files from Android share and add them to the watch.",
                "Shared PDFs now use the same HTML-conversion heuristics as the watch web interface.",
                "Project Gutenberg lists support loading more results and keep an add-progress notification visible during downloads and uploads.",
                "Project Gutenberg listings now show file size, active adds can be canceled from the progress bar, and interrupted transfers no longer leave partial books on the watch.",
                "Project Gutenberg Add is disabled for books already on the watch, while canceled adds can be retried as an overwrite.",
                "Library drag sorting now matches the web interface, including before/after drops inside a folder and first-position drops when moving into folders.",
                "Project Gutenberg listings now use the same author, optional release date, format, and file-size layout for popular books, recent releases, and search results.",
                "Folder overflow stays scrollable while Root remains reachable below the folder list.",
                "Changelog/About information refreshed to match the current watch release.",
            ),
        ),
        CompanionChangelogEntry(
            version = "0.5.1",
            date = "2026-05-21",
            notes = listOf(
                "Project Gutenberg opens with Top most popular books and Recent releases sections; searches temporarily replace those sections and clearing the query brings them back.",
                "Project Gutenberg home switches between Top most popular books and Recent releases at the top of the list; recent releases include release dates when available.",
                "Project Gutenberg book rows show the listing plus an Add action.",
                "Project Gutenberg lists can load more results, and adding a book now keeps a bottom progress notification visible until upload completes.",
                "The phone utility is now available from Android's share menu for supported book files, and shared PDFs use the same HTML-conversion heuristics as the web interface.",
                "How to use moved from Settings to the main library window help icon.",
                "Large folder sets now stay in a half-screen scroll area so Root remains visible below them, while small folder sets shrink upward dynamically.",
                "Books in an expanded folder now render directly under that folder's chip; Root sits below the folder list and scrolls to centre as folder count grows.",
                "Rename-folder dialog (edit icon next to the delete icon).",
                "Project Gutenberg search works again — search results now synthesise the EPUB download URL from the book id, and the keyboard Enter key submits.",
                "Library and settings auto-refresh every 5 s while the screen is on stage; the manual refresh icon is gone.",
                "Upload flow shows a modal with a progress bar while the bytes are in flight.",
                "Watch settings page gained Changelog and About sections at the bottom.",
            ),
        ),
        CompanionChangelogEntry(
            version = "0.5.0",
            date = "2026-05-19",
            notes = listOf(
                "PDF support (experimental): browser-side PDF.js conversion via the LAN web UI, plus pdfbox-android on the phone.",
                "Project Gutenberg browser: search OPDS feeds and stream a downloaded book straight to the watch via ChannelClient.",
                "Stats dashboard: 30-day reading-minutes bar chart, WPM trend line, totals cards.",
            ),
        ),
        CompanionChangelogEntry(
            version = "0.3.0",
            date = "2026-05-19",
            notes = listOf(
                "Initial phone companion. SAF file picker → Wear Data Layer upload to the watch. Material 3, dark-mode aware.",
                "Folder organisation: create, expand, delete, drag-and-drop assignment.",
            ),
        ),
    )
}
