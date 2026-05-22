package com.fredapp.wbooks.parser.model

/**
 * Parser-neutral representation of a book. All format parsers (epub/txt/fb2/html/docx/odt) lower to this.
 *
 * Inline styling is expressed as runs over plain text so the renderer can map them to
 * [androidx.compose.ui.text.AnnotatedString] without parser-specific branching.
 *
 * Persistence: round-tripped to/from disk by [com.fredapp.wbooks.parser.cache.DocumentCodec] using
 * an explicit binary layout (not Java serialization). Adding, removing, or reordering any
 * field below requires bumping [com.fredapp.wbooks.parser.cache.DocumentCache.SCHEMA_VERSION] so
 * stale cache entries are invalidated.
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
    /**
     * Embedded raster image. [bytes] is the original encoded payload (PNG/JPEG/
     * GIF/WebP) so we don't double-decode; the renderer is responsible for
     * decoding and constraining to the watch's safe area. [alt] is best-effort
     * descriptive text and shown when decoding fails. [mime] is best-effort.
     */
    data class Image(val bytes: ByteArray, val mime: String? = null, val alt: String = "") : Block {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Image) return false
            return mime == other.mime && alt == other.alt && bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + (mime?.hashCode() ?: 0)
            result = 31 * result + alt.hashCode()
            return result
        }
    }
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
