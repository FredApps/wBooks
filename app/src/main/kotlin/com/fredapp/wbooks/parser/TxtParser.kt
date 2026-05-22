package com.fredapp.wbooks.parser

import com.fredapp.wbooks.parser.model.Block
import com.fredapp.wbooks.parser.model.Chapter
import com.fredapp.wbooks.parser.model.Document
import com.fredapp.wbooks.parser.model.Run
import java.io.InputStream

/**
 * Plain text. Heuristics for v1:
 *  - Blank line separates paragraphs.
 *  - A short line surrounded by blank lines, in ALL CAPS, is treated as a heading.
 * We don't try to detect chapters â€” the whole text is one chapter.
 */
class TxtParser(
    private val onProgress: (Int) -> Unit = {},
) : BookParser {
    override fun parse(input: InputStream): Document {
        onProgress(15)
        val text = input.bufferedReader(Charsets.UTF_8).readText()
        onProgress(45)
        val paragraphs = text.split(Regex("\\r?\\n\\s*\\r?\\n"))
        onProgress(65)
        val blocks = paragraphs
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { para ->
                if (para.length < 80 && para == para.uppercase() && para.any { it.isLetter() }) {
                    Block.Heading(level = 1, text = para)
                } else {
                    Block.Paragraph(listOf(Run(para)))
                }
            }
        onProgress(90)
        return Document(title = "", author = null, chapters = listOf(Chapter(null, blocks)))
    }
}
