package com.wbooks.parser.model

/**
 * Parser-neutral representation of a book. All format parsers (epub/txt/fb2/html) lower to this.
 *
 * Inline styling is expressed as runs over plain text so the renderer can map them to
 * [androidx.compose.ui.text.AnnotatedString] without parser-specific branching.
 */
data class Document(
    val title: String,
    val author: String?,
    val chapters: List<Chapter>,
)

data class Chapter(
    val title: String?,
    val blocks: List<Block>,
)

sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val runs: List<Run>) : Block
    data object Divider : Block
    /** Pre-formatted code block. [language] is best-effort (from the source's class attr); null = unknown. */
    data class Code(val language: String?, val text: String) : Block
}

data class Run(
    val text: String,
    val style: RunStyle = RunStyle(),
)

data class RunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** ARGB; null means "use reader default colour". */
    val color: Int? = null,
)
