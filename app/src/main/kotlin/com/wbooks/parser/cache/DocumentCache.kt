package com.wbooks.parser.cache

import com.wbooks.parser.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest

/**
 * On-disk cache of parsed [Document]s, keyed by Book.id and fingerprinted with
 * (file size, mtime, schema version). Large EPUBs take noticeable time to parse
 * on a watch CPU; round-tripping via ObjectOutputStream is much faster.
 *
 * File layout under [dir]:
 *   <sha1-of-bookId>.bin   header { magic, schemaVersion, sizeBytes, mtimeMs } + ObjectOutputStream(Document)
 *
 * Schema versioning: bump [SCHEMA_VERSION] when any model class
 * ([Document], [com.wbooks.parser.model.Chapter], [com.wbooks.parser.model.Block],
 * [com.wbooks.parser.model.Run], [com.wbooks.parser.model.RunStyle]) gains, loses,
 * or renames a field, OR when the parsers change in a way that produces different
 * output for the same input. Stale entries are silently ignored.
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
                ObjectInputStream(dis).readObject() as? Document
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
                ObjectOutputStream(dos).use { oos -> oos.writeObject(doc) }
            }
            // Atomic-ish rename so a half-written cache file can never be read.
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** Remove cache entry for a book that's been deleted from the library. */
    fun invalidate(bookId: String) {
        pathFor(bookId).delete()
    }

    private fun pathFor(bookId: String): File {
        val sha = MessageDigest.getInstance("SHA-1")
            .digest(bookId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$sha.bin")
    }

    private companion object {
        const val MAGIC = 0x77426F6B          // "wBok"
        const val SCHEMA_VERSION = 1
    }
}
