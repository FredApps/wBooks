package com.fredapp.wbooks.parser

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipFile

private const val MAX_TEXT_ENTRY_BYTES = 10L * 1024 * 1024
private const val MAX_BINARY_ENTRY_BYTES = 24L * 1024 * 1024

/**
 * Read a zip entry as raw bytes — used for EPUB embedded resources (images).
 * Returns null when the entry doesn't exist; throws when the entry is larger
 * than [MAX_BINARY_ENTRY_BYTES] (the same ceiling the codec enforces, so we
 * can't accidentally write a payload we couldn't read back).
 */
internal fun ZipFile.readBinaryEntry(name: String): ByteArray? {
    val entry = getEntry(name.trimStart('/')) ?: return null
    getInputStream(entry).use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_BINARY_ENTRY_BYTES) {
                throw IOException("ZIP binary entry exceeds ${MAX_BINARY_ENTRY_BYTES / 1_048_576} MB: $name")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

internal fun ZipFile.readTextEntry(name: String): String? {
    val entry = getEntry(name.trimStart('/')) ?: return null
    getInputStream(entry).use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_TEXT_ENTRY_BYTES) {
                throw IOException("ZIP text entry exceeds ${MAX_TEXT_ENTRY_BYTES / 1_048_576} MB: $name")
            }
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
