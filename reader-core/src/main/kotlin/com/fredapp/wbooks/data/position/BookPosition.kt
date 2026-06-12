package com.fredapp.wbooks.data.position

/**
 * A position inside a parsed Document: which chapter and which block within it.
 * Block-level granularity is enough for ebook reading â€” we don't need character
 * offsets within a paragraph since paragraphs stay together visually.
 */
data class BookPosition(
    val chapterIndex: Int,
    val blockIndex: Int,
    /** Sentence index within the block (sentence mode only). Zero for all other modes. */
    val subIndex: Int = 0,
) {
    fun encode(): String =
        if (subIndex == 0) "$chapterIndex|$blockIndex"
        else "$chapterIndex|$blockIndex|$subIndex"

    companion object {
        val START = BookPosition(0, 0)

        fun decode(raw: String): BookPosition? {
            val parts = raw.split('|')
            if (parts.size !in 2..3) return null
            val ch = parts[0].toIntOrNull() ?: return null
            val bl = parts[1].toIntOrNull() ?: return null
            val sub = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return BookPosition(ch, bl, sub)
        }
    }
}
