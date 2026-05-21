package com.fredapp.wbooks.data.bookmarks

import com.fredapp.wbooks.data.position.BookPosition
import com.fredapp.wbooks.data.settings.ReadingMode

data class Bookmark(
    val position: BookPosition,
    val savedAtMs: Long,
    val label: String? = null,
    val mode: ReadingMode = ReadingMode.NORMAL,
)
