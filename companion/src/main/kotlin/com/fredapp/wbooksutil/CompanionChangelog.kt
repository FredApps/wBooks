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
            version = "0.5.1",
            date = "2026-05-21",
            notes = listOf(
                "Project Gutenberg opens with Top most popular books and Recent releases sections; searches temporarily replace those sections and clearing the query brings them back.",
                "Project Gutenberg home switches between Top most popular books and Recent releases at the top of the list; recent releases include release dates when available.",
                "Project Gutenberg book rows show the listing plus an Add action.",
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
