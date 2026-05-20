package com.fredapp.wbooks.data.library

import com.fredapp.wbooks.data.book.Book
import com.fredapp.wbooks.data.book.BookFormat
import com.fredapp.wbooks.util.uniqueFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Library = whatever supported book files live under [booksDir]. The first-pass
 * implementation derives title from filename; richer metadata (read from each
 * file's parser-level title/author) can be layered on once we cache parses.
 */
class LibraryRepository(private val booksDir: File) {

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    /** Re-scan the books directory. Cheap enough to run on every library show. */
    suspend fun refresh() {
        if (!booksDir.exists()) booksDir.mkdirs()
        val scanned = withContext(Dispatchers.IO) {
            booksDir.walkTopDown()
                .filter { it.isFile }
                .mapNotNull { toBook(it) }
                .sortedBy { it.title.lowercase() }
                .toList()
        }
        _books.value = scanned
    }

    fun delete(id: String): Boolean {
        val target = _books.value.firstOrNull { it.id == id } ?: return false
        val removed = target.file.delete()
        if (removed) _books.value = _books.value.filterNot { it.id == id }
        return removed
    }

    suspend fun move(bookId: String, targetFolder: String): String? = withContext(Dispatchers.IO) {
        val src = _books.value.firstOrNull { it.id == bookId }?.file ?: return@withContext null
        val destDir = if (targetFolder.isEmpty()) booksDir else File(booksDir, targetFolder)
        if (!destDir.isInsideBooksDir()) return@withContext null
        destDir.mkdirs()
        if (src.parentFile?.canonicalFile == destDir.canonicalFile) return@withContext bookId
        val dest = uniqueFile(destDir, src.name)
        if (src.renameTo(dest)) {
            refresh()
            dest.relativeTo(booksDir).invariantSeparatorsPath
        } else {
            null
        }
    }

    private fun toBook(file: File): Book? {
        val format = BookFormat.fromExtension(file.extension) ?: return null
        return Book(
            id = file.relativeTo(booksDir).invariantSeparatorsPath,
            title = file.nameWithoutExtension,
            author = null,
            format = format,
            file = file,
        )
    }

    private fun File.isInsideBooksDir(): Boolean =
        canonicalFile.toPath().startsWith(booksDir.canonicalFile.toPath())
}
