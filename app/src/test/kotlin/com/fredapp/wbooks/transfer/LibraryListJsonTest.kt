package com.fredapp.wbooks.transfer

import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryListJsonTest {
    @Test
    fun encodesBookCacheMetadata() {
        val json = LibraryListJson.encode(
            books = listOf(
                BookSummary(
                    id = "root.txt",
                    title = "root",
                    format = "TXT",
                    sizeBytes = 1234L,
                    modifiedAtMs = 5678L,
                )
            ),
        )

        assertTrue(json.contains(""""sizeBytes":1234"""))
        assertTrue(json.contains(""""modifiedAtMs":5678"""))
    }
}
