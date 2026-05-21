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
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * OpenDocument Text (.odt).
 *
 * Pipeline:
 *   1. Spool the stream to [tmpDir] (defaults to JVM tmpdir) so we can read the
 *      archive as a [ZipFile] without holding it in memory.
 *   2. Read meta.xml for `dc:title` / `meta:initial-creator` (falls back to `dc:creator`).
 *   3. Read content.xml. First pass: collect `<style:style>` -> [RunStyle] for
 *      bold / italic / underline. Second pass: walk `<office:text>`:
 *        `<text:h text:outline-level="1">` -> chapter break + title
 *        `<text:h text:outline-level="N">` (N=2..6) -> [Block.Heading]
 *        `<text:p>` -> [Block.Paragraph] with runs from nested `<text:span>`
 *        Inline: `<text:tab>` -> tab, `<text:line-break>` -> newline,
 *                `<text:s text:c="N">` -> N spaces.
 *
 * Everything else (`<text:list>`, `<table:table>`, `<draw:frame>`, ...) is
 * silently dropped â€” unsupported elements degrade gracefully without errors.
 * Style inheritance (`style:parent-style-name`) is not resolved; LibreOffice's
 * automatic styles are usually flat so direct properties cover the common case.
 */
class OdtParser(
    private val tmpDir: File? = null,
) : BookParser {

    override fun parse(input: InputStream): Document {
        val tmp = File.createTempFile("wbooks-odt-", ".odt", tmpDir)
        try {
            tmp.outputStream().buffered().use { out -> input.copyTo(out) }
            ZipFile(tmp).use { zip -> return parseZip(zip) }
        } finally {
            tmp.delete()
        }
    }

    private fun parseZip(zip: ZipFile): Document {
        val (title, author) = parseMeta(zip.readTextEntry("meta.xml"))
        val contentXml = zip.readTextEntry("content.xml")
            ?: error("ODT: missing content.xml")
        val doc = Jsoup.parse(contentXml, "", Parser.xmlParser())
        val styles = collectStyles(doc)
        val body = doc.selectFirst("office|text")
            ?: error("ODT: content.xml has no <office:text>")
        return Document(title = title, author = author, chapters = splitIntoChapters(body, styles))
    }

    private fun parseMeta(xml: String?): Pair<String, String?> {
        if (xml.isNullOrEmpty()) return "" to null
        val d = Jsoup.parse(xml, "", Parser.xmlParser())
        val title = d.selectFirst("dc|title")?.text()?.trim().orEmpty()
        val author = (d.selectFirst("meta|initial-creator")?.text()
            ?: d.selectFirst("dc|creator")?.text())
            ?.trim()?.takeIf { it.isNotEmpty() }
        return title to author
    }

    private fun collectStyles(doc: org.jsoup.nodes.Document): Map<String, RunStyle> {
        val out = mutableMapOf<String, RunStyle>()
        for (s in doc.select("style|style")) {
            val name = s.attr("style:name")
            if (name.isEmpty()) continue
            val tp = s.selectFirst("style|text-properties") ?: continue
            val bold = tp.attr("fo:font-weight") == "bold"
            val italic = tp.attr("fo:font-style") == "italic"
            val underline = tp.attr("style:text-underline-style")
                .let { it.isNotEmpty() && it != "none" }
            if (bold || italic || underline) {
                out[name] = RunStyle(bold = bold, italic = italic, underline = underline)
            }
        }
        return out
    }

    private fun splitIntoChapters(body: Element, styles: Map<String, RunStyle>): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var pendingTitle: String? = null
        var pendingBlocks = mutableListOf<Block>()
        fun flush() {
            if (pendingBlocks.isNotEmpty() || pendingTitle != null) {
                chapters += Chapter(title = pendingTitle, blocks = pendingBlocks.toList())
                pendingBlocks = mutableListOf()
                pendingTitle = null
            }
        }
        for (child in body.children()) {
            when (child.tagName().lowercase()) {
                "text:h" -> {
                    val level = child.attr("text:outline-level").toIntOrNull()?.coerceIn(1, 6) ?: 1
                    val text = child.text().trim()
                    if (level == 1) {
                        flush()
                        pendingTitle = text.takeIf { it.isNotBlank() }
                    } else if (text.isNotBlank()) {
                        pendingBlocks += Block.Heading(level, text)
                    }
                }
                "text:p" -> {
                    val runs = mutableListOf<Run>()
                    collectRuns(child, RunStyle(), styles, runs)
                    if (runs.isNotEmpty()) pendingBlocks += Block.Paragraph(runs)
                }
                // text:list, table:table, draw:frame, etc. silently skipped
            }
        }
        flush()
        if (chapters.isEmpty()) chapters += Chapter(title = null, blocks = emptyList())
        return chapters
    }

    private fun collectRuns(
        node: Node,
        style: RunStyle,
        styles: Map<String, RunStyle>,
        out: MutableList<Run>,
    ) {
        when (node) {
            is TextNode -> if (node.text().isNotEmpty()) out += Run(node.text(), style)
            is Element -> when (node.tagName().lowercase()) {
                "text:tab" -> out += Run("\t", style)
                "text:line-break" -> out += Run("\n", style)
                "text:s" -> {
                    val count = node.attr("text:c").toIntOrNull()?.coerceAtLeast(1) ?: 1
                    out += Run(" ".repeat(count), style)
                }
                "text:span" -> {
                    val spanStyle = styles[node.attr("text:style-name")]
                    val next = if (spanStyle == null) style else RunStyle(
                        bold = style.bold || spanStyle.bold,
                        italic = style.italic || spanStyle.italic,
                        underline = style.underline || spanStyle.underline,
                    )
                    for (c in node.childNodes()) collectRuns(c, next, styles, out)
                }
                else -> for (c in node.childNodes()) collectRuns(c, style, styles, out)
            }
        }
    }

}
