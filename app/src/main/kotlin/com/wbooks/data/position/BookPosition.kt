package com.wbooks.data.position

/**
 * A position inside a parsed Document: which chapter and which block within it.
 * Block-level granularity is enough for ebook reading — we don't need character
 * offsets within a paragraph since paragraphs stay together visually.
 */
data class BookPosition(
    val chapterIndex: Int,
    val blockIndex: Int,
) {
    fun encode(): String = "$chapterIndex|$blockIndex"

    companion object {
        val START = BookPosition(0, 0)

        fun decode(raw: String): BookPosition? {
            val parts = raw.split('|')
            if (parts.size != 2) return null
            val ch = parts[0].toIntOrNull() ?: return null
            val bl = parts[1].toIntOrNull() ?: return null
            return BookPosition(ch, bl)
        }
    }
}
