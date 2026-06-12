package com.fredapp.wbooks.parser.model

import com.fredapp.wbooks.data.position.BookPosition

/** Total number of blocks across all chapters. */
fun Document.totalBlocks(): Int = chapters.sumOf { it.blocks.size }

/** Translate a position to a flat index over `chapters.flatMap { it.blocks }`. */
fun Document.flatIndexOf(position: BookPosition): Int {
    if (chapters.isEmpty()) return 0
    val chapter = position.chapterIndex.coerceIn(0, chapters.lastIndex)
    var idx = 0
    for (i in 0 until chapter) idx += chapters[i].blocks.size
    val maxBlock = (chapters[chapter].blocks.size - 1).coerceAtLeast(0)
    return idx + position.blockIndex.coerceIn(0, maxBlock)
}

/** Inverse of [flatIndexOf]. */
fun Document.positionAt(flatIndex: Int): BookPosition {
    if (chapters.isEmpty()) return BookPosition.START
    var remaining = flatIndex.coerceAtLeast(0)
    for ((ci, ch) in chapters.withIndex()) {
        if (remaining < ch.blocks.size) return BookPosition(ci, remaining)
        remaining -= ch.blocks.size
    }
    return BookPosition(chapters.lastIndex, chapters.last().blocks.lastIndex.coerceAtLeast(0))
}
