package com.fredapp.wbooks.parser.model

import com.fredapp.wbooks.data.position.BookPosition

/**
 * Shared text segmentation for the Sentence and Speed-Reading modes, used
 * identically by the watch reader and the phone companion reader so both
 * surfaces split books the same way.
 *
 * Both [segmentSentences] and [tokenizeWords] materialise one item per
 * sentence / word in a flat list. That's O(sentences) / O(words) memory held
 * only while the mode is on screen — the watch has run this on Moby-Dick-sized
 * books within its tighter heap, so the phone is comfortably within budget.
 */

/** An item with a book position, so jump/restore logic can be shared. */
sealed interface PositionedItem {
    val position: BookPosition
}

data class SentenceItem(val text: String, override val position: BookPosition) : PositionedItem
data class WordItem(val text: String, override val position: BookPosition) : PositionedItem

/** Plain text of a block for reading flow. Dividers and code blocks contribute nothing. */
private fun Block.readableText(): String = when (this) {
    is Block.Heading -> text
    is Block.Paragraph -> runs.joinToString("") { it.text }
    Block.Divider, is Block.Code -> ""
}

/**
 * Split the whole document into sentence-sized fragments, each tagged with the
 * (chapter, block, sub) position of where it starts. See [splitAtPunctuation]
 * for the boundary rules.
 */
fun Document.segmentSentences(): List<SentenceItem> {
    val out = mutableListOf<SentenceItem>()
    for ((ci, chapter) in chapters.withIndex()) {
        for ((bi, block) in chapter.blocks.withIndex()) {
            val text = block.readableText().trim()
            if (text.isEmpty()) continue
            var subIndex = 0
            for (part in text.splitAtPunctuation()) {
                if (part.isEmpty()) continue
                out.add(SentenceItem(part, BookPosition(ci, bi, subIndex)))
                subIndex++
            }
        }
    }
    return out
}

/** Tokenise the whole document into words, each tagged with its block position. */
fun Document.tokenizeWords(): List<WordItem> {
    val ws = Regex("\\s+")
    val out = mutableListOf<WordItem>()
    for ((ci, chapter) in chapters.withIndex()) {
        for ((bi, block) in chapter.blocks.withIndex()) {
            val text = block.readableText()
            if (text.isNotBlank()) {
                out.addAll(
                    text.trim()
                        .split(ws)
                        .filter { it.isNotEmpty() }
                        .mapIndexed { wordIndex, word -> WordItem(word, BookPosition(ci, bi, wordIndex)) },
                )
            }
        }
    }
    return out
}

/**
 * Index of the first item at or after [target], compared lexicographically on
 * (chapter, block, sub). Matching the sub-index lets a bookmark made in this
 * mode land back on the exact sentence / word, not just the start of the block.
 * Returns the last index when nothing is at-or-after target. Callers must guard
 * the empty-list case first.
 */
fun List<PositionedItem>.indexAtOrAfter(target: BookPosition): Int {
    val i = indexOfFirst { item ->
        val p = item.position
        when {
            p.chapterIndex != target.chapterIndex -> p.chapterIndex > target.chapterIndex
            p.blockIndex != target.blockIndex -> p.blockIndex > target.blockIndex
            else -> p.subIndex >= target.subIndex
        }
    }
    return if (i >= 0) i else lastIndex
}

/**
 * Always break after `.`, `."`, `,`, `,"` when followed by whitespace or end.
 * The trailing-space requirement keeps decimals like "3.14" and abbreviations
 * like "Mr.Smith" intact, while still splitting at every real sentence /
 * clause boundary.
 *
 * Quote characters include straight ("/') and curly (U+201C/D/U+2018/9) so
 * ebooks that use smart quotes still split after `."`.
 *
 * Fragments shorter than [MIN_FRAGMENT_SPACES] inter-word spaces are not
 * emitted as their own screen — instead the break is skipped and the fragment
 * keeps growing until it reaches the next valid boundary.
 */
private const val MIN_FRAGMENT_SPACES = 3

private fun Char.isCloseQuote(): Boolean =
    this == '"' || this == '\'' || this == '“' || this == '”' || this == '‘' || this == '’'

private fun String.splitAtPunctuation(): List<String> {
    val pieces = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '.' || c == ',') {
            var end = i + 1
            if (end < length && this[end].isCloseQuote()) end++
            val nextIsBoundary = end >= length || this[end].isWhitespace()
            if (nextIsBoundary) {
                val fragment = substring(start, end).trim()
                if (fragment.isNotEmpty() && fragment.count { it == ' ' } >= MIN_FRAGMENT_SPACES) {
                    pieces += fragment
                    start = end
                }
                i = end
                continue
            }
        }
        i++
    }
    substring(start).trim().takeIf { it.isNotEmpty() }?.let { pieces += it }
    return pieces.ifEmpty { listOf(trim()) }
}

/**
 * Optimal recognition point for RSVP. Standard table:
 *   1 char -> 0, 2-5 -> 1, 6-9 -> 2, 10-13 -> 3, 14+ -> 4.
 */
fun focalIndex(word: String): Int = when (word.length) {
    0, 1 -> 0
    in 2..5 -> 1
    in 6..9 -> 2
    in 10..13 -> 3
    else -> 4
}.coerceAtMost(word.lastIndex.coerceAtLeast(0))
