package com.wbooks.parser

import com.wbooks.parser.model.Block
import com.wbooks.parser.model.Chapter
import com.wbooks.parser.model.Document
import com.wbooks.parser.model.Run
import com.wbooks.parser.model.RunStyle
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.InputStream

/**
 * HTML / xhtml. Walks the body, mapping a curated subset of tags into [Block]/[Run].
 *
 * `<pre><code>` blocks become [Block.Code]; the rendering layer is responsible for
 * applying syntax colouring to them. Language is taken from `class="language-xxx"`
 * (Pandoc/Pygments convention) when present.
 */
class HtmlParser : BookParser {
    override fun parse(input: InputStream): Document =
        fromJsoup(Jsoup.parse(input, Charsets.UTF_8.name(), ""))

    /** Parse already-decoded HTML text. Used by [EpubParser] when feeding spine chapters. */
    fun parse(html: String): Document = fromJsoup(Jsoup.parse(html))

    /** Convert a single XHTML body into a list of [Block]s suitable for one [Chapter]. */
    internal fun blocksOf(html: String): List<Block> {
        val out = mutableListOf<Block>()
        walk(Jsoup.parse(html).body(), out)
        return out
    }

    private fun fromJsoup(doc: org.jsoup.nodes.Document): Document {
        val title = doc.title().ifBlank { doc.selectFirst("h1")?.text().orEmpty() }
        val blocks = mutableListOf<Block>()
        walk(doc.body(), blocks)
        return Document(title = title, author = null, chapters = listOf(Chapter(null, blocks)))
    }

    private fun walk(root: Element, out: MutableList<Block>) {
        for (child in root.children()) {
            when (child.tagName().lowercase()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val level = child.tagName().substring(1).toInt()
                    out += Block.Heading(level, child.text())
                }
                "hr" -> out += Block.Divider
                "p" -> out += Block.Paragraph(runs(child))
                "pre" -> {
                    val codeEl = child.selectFirst("code") ?: child
                    val lang = codeEl.classNames().firstOrNull { it.startsWith("language-") }
                        ?.removePrefix("language-")
                    out += Block.Code(language = lang, text = codeEl.wholeText())
                }
                "blockquote", "div", "section", "article" -> walk(child, out)
                "ul", "ol" -> {
                    // TODO real list rendering; for now flatten each <li> to a paragraph
                    for (li in child.select("> li")) out += Block.Paragraph(runs(li))
                }
                else -> {
                    // Unknown block: if it contains block-level children, recurse; else treat as paragraph.
                    if (child.children().any { it.isBlock() }) walk(child, out)
                    else out += Block.Paragraph(runs(child))
                }
            }
        }
    }

    private fun runs(el: Element): List<Run> {
        val out = mutableListOf<Run>()
        collectRuns(el, RunStyle(), out)
        return out
    }

    private fun collectRuns(node: Node, style: RunStyle, out: MutableList<Run>) {
        when (node) {
            is TextNode -> if (node.text().isNotEmpty()) out += Run(node.text(), style)
            is Element -> {
                val next = when (node.tagName().lowercase()) {
                    "b", "strong" -> style.copy(bold = true)
                    "i", "em", "cite" -> style.copy(italic = true)
                    "u" -> style.copy(underline = true)
                    else -> style
                }
                for (c in node.childNodes()) collectRuns(c, next, out)
            }
        }
    }

    private fun Element.isBlock(): Boolean = tagName().lowercase() in setOf(
        "p", "div", "section", "article", "blockquote", "pre",
        "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "hr",
    )
}
