package com.fredapp.wbooks.parser.cache

import com.fredapp.wbooks.parser.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * On-disk cache of parsed [Document]s, keyed by Book.id and fingerprinted with
 * (file size, mtime, schema version). Large EPUBs take noticeable time to parse
 * on a watch CPU; the cache makes reopens near-instant.
 *
 * Encoding goes through [DocumentCodec] â€” an explicit binary layout, not Java
 * serialization. Bump [SCHEMA_VERSION] when DocumentCodec's layout changes; old
 * cache files are then silently skipped on load.
 *
 * File layout under [dir]:
 *   <sha1-of-bookId>.bin :
 *       i32 magic
 *       i32 schemaVersion
 *       i64 sizeBytes (fingerprint)
 *       i64 mtimeMs   (fingerprint)
 *       DocumentCodec.write(doc) ...
 */
class DocumentCache(private val dir: File) {

    data class Key(val bookId: String, val sizeBytes: Long, val mtimeMs: Long)

    init { dir.mkdirs() }

    suspend fun load(key: Key): Document? = withContext(Dispatchers.IO) {
        val file = pathFor(key.bookId)
        if (!file.exists()) return@withContext null
        runCatching {
            DataInputStream(FileInputStream(file).buffered()).use { dis ->
                if (dis.readInt() != MAGIC) return@use null
                if (dis.readInt() != SCHEMA_VERSION) return@use null
                val size = dis.readLong()
                val mtime = dis.readLong()
                if (size != key.sizeBytes || mtime != key.mtimeMs) return@use null
                DocumentCodec.read(dis)
            }
        }.getOrNull()
    }

    suspend fun store(key: Key, doc: Document) = withContext(Dispatchers.IO) {
        val file = pathFor(key.bookId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            DataOutputStream(FileOutputStream(tmp).buffered()).use { dos ->
                dos.writeInt(MAGIC)
                dos.writeInt(SCHEMA_VERSION)
                dos.writeLong(key.sizeBytes)
                dos.writeLong(key.mtimeMs)
                DocumentCodec.write(dos, doc)
            }
            // Atomic replace so a half-written cache file can never be read, and a
            // crash mid-store never leaves the entry missing if the prior one was good.
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** Remove cache entry for a book that's been deleted from the library. */
    fun invalidate(bookId: String) {
        pathFor(bookId).delete()
    }

    fun moveBookId(fromBookId: String, toBookId: String) {
        if (fromBookId == toBookId) return
        val from = pathFor(fromBookId)
        if (!from.exists()) return
        val to = pathFor(toBookId)
        to.parentFile?.mkdirs()
        if (to.exists()) to.delete()
        from.renameTo(to)
    }

    private fun pathFor(bookId: String): File {
        val sha = MessageDigest.getInstance("SHA-1")
            .digest(bookId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$sha.bin")
    }

    companion object {
        private const val MAGIC = 0x77426F6B    // "wBok"
        /** Bump when [DocumentCodec]'s layout changes or model fields are added/removed/reordered. */
        const val SCHEMA_VERSION = 4
    }
}
