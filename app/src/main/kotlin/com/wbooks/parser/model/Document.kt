package com.wbooks.parser.model

import java.io.Serializable

/**
 * Parser-neutral representation of a book. All format parsers (epub/txt/fb2/html) lower to this.
 *
 * Inline styling is expressed as runs over plain text so the renderer can map them to
 * [androidx.compose.ui.text.AnnotatedString] without parser-specific branching.
 *
 * Implements [Serializable] so [com.wbooks.parser.cache.DocumentCache] can round-trip
 * parsed documents via ObjectOutputStream. The cache is gated by a schema version and
 * a per-book fingerprint; bump those if you change anything below the interface line.
 */
data class Document(
    val title: String,
    val author: String?,
    val chapters: List<Chapter>,
) : Serializable

data class Chapter(
    val title: String?,
    val blocks: List<Block>,
) : Serializable

sealed interface Block : Serializable {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val runs: List<Run>) : Block
    /** Singleton; Kotlin generates readResolve() for data objects so Java serialization preserves identity. */
    data object Divider : Block
    /** Pre-formatted code block. [language] is best-effort (from the source's class attr); null = unknown. */
    data class Code(val language: String?, val text: String) : Block
}

data class Run(
    val text: String,
    val style: RunStyle = RunStyle(),
) : Serializable

data class RunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** ARGB; null means "use reader default colour". */
    val color: Int? = null,
) : Serializable
