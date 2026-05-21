package com.fredapp.wbooks.parser.model

import com.fredapp.wbooks.data.position.BookPosition

private val WHITESPACE = Regex("\\s+")

/**
 * Compute [DocumentMetrics] for [doc]. Heavy: walks every block once and runs
 * the sentence segmenter — call from Dispatchers.Default on document load, NOT
 * during composition.
 *
 * Sentence segmentation mirrors the rules in
 * [com.fredapp.wbooks.ui.reader.segmentSentences] so the count matches what
 * the user sees in sentence mode. The mirroring is intentional: keeping the
 * sentence-mode renderer self-contained at the cost of a small amount of
 * duplication beats coupling the renderer to the metrics builder.
 */
fun computeDocumentMetrics(doc: Document): DocumentMetrics {
    val chapterCount = doc.chapters.size
    val wordsBefore = Array(chapterCount) { ci ->
        IntArray(doc.chapters[ci].blocks.size + 1)
    }
    val sentencesBefore = Array(chapterCount) { ci ->
        IntArray(doc.chapters[ci].blocks.size + 1)
    }

    var totalWords = 0
    var totalSentences = 0
    var totalBlocks = 0

    for (ci in 0 until chapterCount) {
        val chapter = doc.chapters[ci]
        val wordsRow = wordsBefore[ci]
        val sentencesRow = sentencesBefore[ci]
        for (bi in chapter.blocks.indices) {
            wordsRow[bi] = totalWords
            sentencesRow[bi] = totalSentences
            val (w, s) = blockWordAndSentenceCount(chapter.blocks[bi])
            totalWords += w
            totalSentences += s
            totalBlocks++
        }
        wordsRow[chapter.blocks.size] = totalWords
        sentencesRow[chapter.blocks.size] = totalSentences
    }

    return DocumentMetrics(
        totalBlocks = totalBlocks,
        totalWords = totalWords,
        totalSentences = totalSentences,
        wordsBeforeBlock = wordsBefore,
        sentencesBeforeBlock = sentencesBefore,
        chapterJumps = buildChapterJumps(doc),
    )
}

private fun blockWordAndSentenceCount(block: Block): IntArray {
    val text = when (block) {
        is Block.Heading -> block.text
        is Block.Paragraph -> block.runs.joinToString("") { it.text }
        Block.Divider, is Block.Code -> return intArrayOf(0, 0)
    }
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return intArrayOf(0, 0)
    val words = trimmed.split(WHITESPACE).count { it.isNotEmpty() }
    val sentences = countSentences(trimmed)
    return intArrayOf(words, sentences)
}

/**
 * Mirror of [com.fredapp.wbooks.ui.reader.splitAtPunctuation] for counting only.
 * Returns at least 1 for any non-empty block so the totals match the renderer.
 */
private fun countSentences(text: String): Int {
    var count = 0
    var spaces = 0
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        if (c == ' ' || c.isWhitespace()) {
            if (i > 0 && !text[i - 1].isWhitespace()) spaces++
        }
        if (c == '.' || c == ',') {
            var end = i + 1
            if (end < n && text[end].isCloseQuote()) end++
            val nextBoundary = end >= n || text[end].isWhitespace()
            if (nextBoundary && spaces >= MIN_FRAGMENT_SPACES) {
                count++
                spaces = 0
            }
            i = end
            continue
        }
        i++
    }
    // Trailing fragment.
    return (count + 1).coerceAtLeast(1)
}

private const val MIN_FRAGMENT_SPACES = 3

private fun Char.isCloseQuote(): Boolean =
    this == '"' || this == '\'' || this == '“' || this == '”' ||
        this == '‘' || this == '’'

private fun buildChapterJumps(doc: Document): List<ChapterJump> {
    val headingJumps = mutableListOf<ChapterJump>()
    for ((ci, chapter) in doc.chapters.withIndex()) {
        chapter.title?.takeIf { it.isNotBlank() }?.let { title ->
            headingJumps += ChapterJump(title, BookPosition(ci, 0))
        }
        for ((bi, block) in chapter.blocks.withIndex()) {
            if (block is Block.Heading && block.text.isNotBlank()) {
                headingJumps += ChapterJump(block.text, BookPosition(ci, bi))
            }
        }
    }
    val distinct = headingJumps.distinctBy { it.position }
    val explicitChapters = distinct.filter { it.title.looksLikeChapterHeading() }
    val meaningfulHeadings = distinct.filterNot { it.title.looksLikeBoilerplateHeading() }
    return (explicitChapters.ifEmpty { meaningfulHeadings }).ifEmpty {
        doc.chapters.mapIndexed { idx, _ -> ChapterJump("Chapter ${idx + 1}", BookPosition(idx, 0)) }
    }
}

private fun String.looksLikeChapterHeading(): Boolean {
    val t = trim()
    return t.startsWith("chapter ", ignoreCase = true) ||
        Regex("^(book|part|volume)\\s+[ivxlcdm0-9]+\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)
}

private fun String.looksLikeBoilerplateHeading(): Boolean {
    val t = trim()
    if (t.isBlank()) return true
    val lower = t.lowercase()
    return lower.startsWith("the project gutenberg ebook") ||
        lower.startsWith("project gutenberg") ||
        lower.startsWith("by ") ||
        lower == "contents" ||
        lower == "table of contents" ||
        lower.contains("transcriber's note") ||
        lower.contains("transcriber’s note")
}
