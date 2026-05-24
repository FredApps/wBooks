package com.fredapp.wbooks.data.library

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun moveWithinSameFolderPrependsBook() = runBlocking {
        val root = temp.newFolder("books")
        val folder = File(root, "Classics").also { it.mkdirs() }
        File(folder, "A Tale.txt").writeText("one")
        File(folder, "B Tale.txt").writeText("two")
        val repo = LibraryRepository(root)

        repo.refresh()
        repo.move("Classics/B Tale.txt", "Classics")

        assertEquals(
            listOf("Classics/B Tale.txt", "Classics/A Tale.txt"),
            repo.books.value.map { it.id },
        )
    }
}
