package com.fredapp.wbooks.parser

import com.fredapp.wbooks.parser.model.Block
import com.fredapp.wbooks.parser.model.Chapter
import com.fredapp.wbooks.parser.model.Document
import com.fredapp.wbooks.parser.model.Run
import com.fredapp.wbooks.parser.model.RunStyle
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.InputStream

/**
 * FictionBook 2 (.fb2).
 *
 * Structure (under FictionBook root):
 *   description > title-info > book-title / author
 *   body > section (recursive). Each top-level section becomes a Chapter.
 *
 * Inline elements: <emphasis> = italic, <strong> = bold, <code> = inline code run.
 * Block elements inside a section: <title> = heading (level 1), <subtitle> =
 * heading (level 2), <empty-line/> = divider, <p> = paragraph. Nested <section>
 * contributes a level-2 heading from its own <title> and then its blocks inline.
 *
 * We use Jsoup in XML mode so all parsing in this app shares one parser dependency.
 * The .fb2.zip variant should be unwrapped by the caller before reaching here.
 */
class Fb2Parser(
    private val onProgress: (Int) -> Unit = {},
) : BookParser {

    override fun parse(input: InputStream): Document {
        onProgress(15)
        val xml = input.bufferedReader(Charsets.UTF_8).readText()
        onProgress(40)
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        onProgress(60)

        val title = doc.selectFirst("description > title-info > book-title")?.text()?.trim().orEmpty()
        val authorEl = doc.selectFirst("description > title-info > author")
        val author = authorEl?.let { a ->
            val first = a.selectFirst("first-name")?.text().orEmpty().trim()
            val last = a.selectFirst("last-name")?.text().orEmpty().trim()
            listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
        }

        val chapters = doc.select("body > section").map { sectionToChapter(it) }
            .ifEmpty {
                // Some FB2 files put paragraphs directly under <body> with no <section>.
                val body = doc.selectFirst("body")
                if (body != null) listOf(Chapter(title = null, blocks = sectionBlocks(body, headingLevel = 1)))
                else emptyList()
            }

        onProgress(90)
        return Document(title = title, author = author, chapters = chapters)
    }

    private fun sectionToChapter(section: Element): Chapter {
        val sectionTitle = section.selectFirst("> title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        return Chapter(title = sectionTitle, blocks = sectionBlocks(section, headingLevel = 1))
    }

    /** Walk a <section> (or body) collecting its blocks, recursing into nested sections. */
    private fun sectionBlocks(section: Element, headingLevel: Int): List<Block> {
        val out = mutableListOf<Block>()
        for (child in section.children()) {
            when (child.tagName().lowercase()) {
                "title" -> child.text().trim().takeIf { it.isNotEmpty() }
                    ?.let { out += Block.Heading(level = headingLevel, text = it) }
                "subtitle" -> child.text().trim().takeIf { it.isNotEmpty() }
                    ?.let { out += Block.Heading(level = headingLevel + 1, text = it) }
                "p" -> out += Block.Paragraph(runsOf(child))
                "empty-line" -> out += Block.Divider
                "section" -> out += sectionBlocks(child, headingLevel = headingLevel + 1)
                "epigraph", "cite" -> {
                    // Treat epigraphs as a sequence of paragraphs; FB2 wraps inner <p> the same way.
                    out += sectionBlocks(child, headingLevel = headingLevel + 1)
                }
                "image", "binary" -> {
                    // Images aren't rendered on the watch (no graphics in v1); skip.
                }
                else -> {
                    // Unknown block: if it has children, recurse; otherwise drop it.
                    if (child.children().isNotEmpty()) out += sectionBlocks(child, headingLevel)
                }
            }
        }
        return out
    }

    private fun runsOf(p: Element): List<Run> {
        val out = mutableListOf<Run>()
        collectRuns(p, RunStyle(), out)
        return out
    }

    private fun collectRuns(node: Node, style: RunStyle, out: MutableList<Run>) {
        when (node) {
            is TextNode -> if (node.text().isNotEmpty()) out += Run(node.text(), style)
            is Element -> {
                val next = when (node.tagName().lowercase()) {
                    "emphasis" -> style.copy(italic = true)
                    "strong" -> style.copy(bold = true)
                    "code", "kbd" -> style // monospace rendering belongs to Block.Code; inline keeps default
                    else -> style
                }
                for (c in node.childNodes()) collectRuns(c, next, out)
            }
        }
    }
}
