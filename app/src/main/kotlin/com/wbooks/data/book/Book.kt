package com.wbooks.data.book

import android.net.Uri

/**
 * A book the user has imported. The actual content sits on disk under the app's files dir;
 * we lazy-parse on open rather than holding the full text in memory.
 */
data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val format: BookFormat,
    val sourceUri: Uri,
    /** Bytes read into the book; used to restore reading position. */
    val lastPosition: Int = 0,
)

enum class BookFormat {
    EPUB,
    TXT,
    FB2,
    HTML,   // covers .htm, .html, .xhtml
}
