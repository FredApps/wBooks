package com.fredapp.wbooksutil

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryListJsonTest {
    @Test
    fun decodesBooksAndEmptyFolders() {
        val json = """
            {
              "books":[
                {"id":"Fiction/moby.epub","title":"moby","format":"EPUB"},
                {"id":"root.txt","title":"root","format":"TXT"}
              ],
              "folders":["Fiction","Empty"]
            }
        """.trimIndent()

        val snapshot = LibraryListJson.decode(json.toByteArray(Charsets.UTF_8))

        assertEquals(listOf("Fiction", "Empty"), snapshot.folders)
        assertEquals(2, snapshot.books.size)
        assertEquals("Fiction/moby.epub", snapshot.books[0].id)
        assertEquals("root.txt", snapshot.books[1].id)
    }

    @Test
    fun decodesLegacyBooksOnlyPayload() {
        val json = """{"books":[{"id":"root.txt","title":"root","format":"TXT"}]}"""

        val snapshot = LibraryListJson.decode(json.toByteArray(Charsets.UTF_8))

        assertEquals(emptyList<String>(), snapshot.folders)
        assertEquals(listOf(BookSummary("root.txt", "root", "TXT")), snapshot.books)
    }

    @Test
    fun decodesBookCacheMetadata() {
        val json = """
            {
              "books":[
                {"id":"root.txt","title":"root","format":"TXT","sizeBytes":1234,"modifiedAtMs":5678}
              ],
              "folders":[]
            }
        """.trimIndent()

        val snapshot = LibraryListJson.decode(json.toByteArray(Charsets.UTF_8))

        assertEquals(1234L, snapshot.books.single().sizeBytes)
        assertEquals(5678L, snapshot.books.single().modifiedAtMs)
    }
}
