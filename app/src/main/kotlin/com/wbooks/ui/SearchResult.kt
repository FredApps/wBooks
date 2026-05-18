package com.wbooks.ui

import com.wbooks.data.position.BookPosition

/** A single hit found in the loaded document by [ReaderViewModel.runSearch]. */
data class SearchResult(
    val position: BookPosition,
    val snippet: String,
)
