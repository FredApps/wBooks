package com.wbooks.data.book

import java.io.File

/**
 * A book on disk under [WBooksApp.booksDir]. Metadata is derived from the file
 * itself; persistent extras (last reading position, bookmarks) will live in
 * DataStore keyed by [id] when those features land.
 */
data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val format: BookFormat,
    val file: File,
)

enum class BookFormat {
    EPUB,
    TXT,
    FB2,
    HTML,
    DOCX,
    ODT;

    companion object {
        /** Match by file extension (case-insensitive). Returns null for unsupported types. */
        fun fromExtension(ext: String): BookFormat? = when (ext.lowercase()) {
            "epub" -> EPUB
            "txt" -> TXT
            "fb2" -> FB2
            "html", "htm", "xhtml" -> HTML
            "docx" -> DOCX
            "odt" -> ODT
            else -> null
        }
    }
}
