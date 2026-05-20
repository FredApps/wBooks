package com.fredapp.wbooks.data.bookmarks

import com.fredapp.wbooks.data.position.BookPosition

data class Bookmark(
    val position: BookPosition,
    val savedAtMs: Long,
    val label: String? = null,
)
