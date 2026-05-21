package com.fredapp.wbooks.data.library

import com.fredapp.wbooks.data.book.Book
import com.fredapp.wbooks.data.book.BookFormat
import com.fredapp.wbooks.util.uniqueFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Library = whatever supported book files live under [booksDir]. The first-pass
 * implementation derives title from filename; richer metadata (read from each
 * file's parser-level title/author) can be layered on once we cache parses.
 *
 * Folders are first-class: a top-level subdirectory under [booksDir] is a folder
 * even if it currently holds no books. That's what lets the UI show "Fiction (0)"
 * after the user deletes the last book in it, instead of having the folder vanish.
 */
class LibraryRepository(private val booksDir: File) {

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    /** Sorted list of top-level folder names (empty folders included). */
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    /** Re-scan the books directory. Cheap enough to run on every library show. */
    suspend fun refresh() {
        if (!booksDir.exists()) booksDir.mkdirs()
        val (scanned, folderNames) = withContext(Dispatchers.IO) {
            val files = booksDir.walkTopDown()
                .filter { it.isFile }
                .mapNotNull { toBook(it) }
                .sortedBy { it.title.lowercase() }
                .toList()
            val folders = booksDir.listFiles { f -> f.isDirectory }
                ?.map { it.name }
                ?.sorted()
                .orEmpty()
            files to folders
        }
        _books.value = scanned
        _folders.value = folderNames
    }

    fun delete(id: String): Boolean {
        val target = _books.value.firstOrNull { it.id == id } ?: return false
        val removed = target.file.delete()
        if (removed) _books.update { list -> list.filterNot { it.id == id } }
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

    suspend fun createFolder(name: String): Boolean = withContext(Dispatchers.IO) {
        val clean = cleanFolderName(name) ?: return@withContext false
        val dir = File(booksDir, clean)
        if (!dir.isInsideBooksDir() || dir.canonicalFile == booksDir.canonicalFile) return@withContext false
        val made = dir.mkdirs() || dir.isDirectory
        if (made) refresh()
        made
    }

    /** Rename a top-level folder. Returns the new folder name on success, null on failure. */
    suspend fun renameFolder(oldName: String, newName: String): String? = withContext(Dispatchers.IO) {
        val clean = cleanFolderName(newName) ?: return@withContext null
        if (clean == oldName) return@withContext oldName
        val src = File(booksDir, oldName)
        val dest = File(booksDir, clean)
        if (!src.isInsideBooksDir() || !dest.isInsideBooksDir()) return@withContext null
        if (!src.isDirectory || dest.exists()) return@withContext null
        val ok = src.renameTo(dest)
        if (ok) refresh()
        if (ok) clean else null
    }

    /** Delete a folder and every book in it. Returns list of book ids removed (for state cleanup). */
    suspend fun deleteFolder(name: String): List<String> = withContext(Dispatchers.IO) {
        val dir = File(booksDir, name)
        if (!dir.isInsideBooksDir() || dir.canonicalFile == booksDir.canonicalFile || !dir.isDirectory) {
            return@withContext emptyList()
        }
        val ids = _books.value.filter { it.id.substringBeforeLast('/', "") == name }.map { it.id }
        dir.deleteRecursively()
        refresh()
        ids
    }

    private fun cleanFolderName(raw: String): String? {
        val t = raw.trim().trim('/', '\\')
        if (t.isEmpty()) return null
        if (t.contains('/') || t.contains('\\')) return null
        if (t == "." || t == "..") return null
        return t
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
