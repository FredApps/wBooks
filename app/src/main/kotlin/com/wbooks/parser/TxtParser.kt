package com.wbooks.parser

import com.wbooks.parser.model.Block
import com.wbooks.parser.model.Chapter
import com.wbooks.parser.model.Document
import com.wbooks.parser.model.Run
import java.io.InputStream

/**
 * Plain text. Heuristics for v1:
 *  - Blank line separates paragraphs.
 *  - A short line surrounded by blank lines, in ALL CAPS, is treated as a heading.
 * We don't try to detect chapters — the whole text is one chapter.
 */
class TxtParser : BookParser {
    override fun parse(input: InputStream): Document {
        val text = input.bufferedReader(Charsets.UTF_8).readText()
        val paragraphs = text.split(Regex("\\r?\\n\\s*\\r?\\n"))
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
        return Document(title = "", author = null, chapters = listOf(Chapter(null, blocks)))
    }
}
