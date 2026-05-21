package com.fredapp.wbooks.parser.model

import com.fredapp.wbooks.data.position.BookPosition

/**
 * Read-only lookup tables derived from a parsed [Document]. Computed ONCE per
 * document open (off the main thread) so the Tools page, the reading-ETA
 * estimator, and the bookmark labels don't each walk the whole document on
 * every recomposition.
 *
 * Memory shape: O(blocks), not O(words) or O(sentences). For a Moby-Dick-sized
 * book that's tens of kilobytes, not several megabytes — important on a watch
 * where the old "one BookPosition per word" representation in
 * SecondaryScreen.wordProgressLabels was costing ~8 MB of heap by itself.
 *
 * Cumulative counts are *exclusive* prefix sums: `wordsBeforeBlock[ci][bi]` is
 * the number of words in the book *before* block (ci, bi). The word at the
 * given block is included in `wordsBeforeBlock[ci][bi + 1]`. Same convention
 * for sentences. This lets a caller derive "how far through am I?" via two
 * array reads + a subIndex offset, no scan required.
 */
data class DocumentMetrics(
    val totalBlocks: Int,
    val totalWords: Int,
    val totalSentences: Int,
    /** wordsBeforeBlock[ci][bi]; one extra trailing entry per chapter so [ci][blocks.size] is valid. */
    val wordsBeforeBlock: Array<IntArray>,
    /** sentencesBeforeBlock[ci][bi]; same shape as [wordsBeforeBlock]. */
    val sentencesBeforeBlock: Array<IntArray>,
    /** Pre-computed table of contents — the same list the Tools page renders. */
    val chapterJumps: List<ChapterJump>,
) {
    /** Word index (1-based) of the given position, clamped to [1, [totalWords]]. */
    fun wordIndexAt(position: BookPosition): Int {
        if (totalWords == 0) return 0
        val ci = position.chapterIndex.coerceIn(0, wordsBeforeBlock.lastIndex)
        val chapterRow = wordsBeforeBlock[ci]
        val bi = position.blockIndex.coerceIn(0, chapterRow.size - 1)
        return (chapterRow[bi] + 1).coerceIn(1, totalWords)
    }

    /** Sentence index (1-based) of the given position, clamped to [1, [totalSentences]]. */
    fun sentenceIndexAt(position: BookPosition): Int {
        if (totalSentences == 0) return 0
        val ci = position.chapterIndex.coerceIn(0, sentencesBeforeBlock.lastIndex)
        val chapterRow = sentencesBeforeBlock[ci]
        val bi = position.blockIndex.coerceIn(0, chapterRow.size - 1)
        return (chapterRow[bi] + position.subIndex + 1).coerceIn(1, totalSentences)
    }

    // Generated equals/hashCode skip the IntArrays since they're effectively
    // immutable-by-construction; data-class default equality would compare them
    // by reference, which is wrong, but we never compare DocumentMetrics
    // instances so we don't need to fix it.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Single entry in the chapter table-of-contents. */
data class ChapterJump(val title: String, val position: BookPosition)
