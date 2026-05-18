package com.wbooks.data.bookmarks

import com.wbooks.data.position.BookPosition

data class Bookmark(
    val position: BookPosition,
    val savedAtMs: Long,
    val label: String? = null,
)
