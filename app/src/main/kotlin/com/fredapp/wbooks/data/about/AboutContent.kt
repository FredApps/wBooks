package com.fredapp.wbooks.data.about

data class AboutSection(
    val title: String,
    val lines: List<String>,
)

val WATCH_SEED_BOOKS: List<String> = listOf(
    "Moby Dick",
    "Pride and Prejudice",
    "The Adventures of Sherlock Holmes",
    "The Strange Case of Dr Jekyll and Mr Hyde",
    "The Time Machine",
    "The Yellow Wallpaper",
)

val WATCH_ABOUT_SECTIONS: List<AboutSection> = listOf(
    AboutSection(
        title = "Seed books",
        lines = WATCH_SEED_BOOKS.map { "- $it" },
    ),
    AboutSection(
        title = "Built with",
        lines = listOf("Kotlin, Jetpack Compose for Wear OS, Jsoup, NanoHTTPD, PDFBox, and more."),
    ),
    AboutSection(
        title = "License",
        lines = listOf(
            "wBooks is licensed under GPLv3.",
            "Source: github.com/FredApps/wBooks",
            "Bundled Gutenberg texts are public domain in the United States; check your jurisdiction.",
        ),
    ),
    AboutSection(
        title = "Open Source",
        lines = listOf(
            "This app uses many open-source libraries including jsoup, NanoHTTPD, PDFBox, and Google's Jetpack libraries.",
            "See ATTRIBUTION.md for full credits and licenses.",
        ),
    ),
)

