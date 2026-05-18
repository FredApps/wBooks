package com.wbooks.parser

import com.wbooks.parser.model.Block
import com.wbooks.parser.model.Chapter
import com.wbooks.parser.model.Document
import com.wbooks.parser.model.Run
import com.wbooks.parser.model.RunStyle
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Office Open XML word documents (.docx).
 *
 * Pipeline:
 *   1. Spool the stream to [tmpDir] (defaults to JVM tmpdir) so we can read the
 *      archive as a [ZipFile] without holding it in memory.
 *   2. Read docProps/core.xml for `dc:title` / `dc:creator`.
 *   3. Read word/document.xml. Inside `<w:body>`:
 *        `<w:p>` with `<w:pStyle w:val="Heading1">` -> chapter break + title
 *        `<w:p>` with `<w:pStyle w:val="HeadingN">` (N=2..6) -> [Block.Heading]
 *        `<w:p>` otherwise -> [Block.Paragraph] with runs from each `<w:r>`
 *        `<w:r>` styling: `<w:b/>`, `<w:i/>`, `<w:u/>` map to [RunStyle];
 *        `<w:t>` provides text, `<w:tab/>` / `<w:br/>` map to inline characters.
 *
 * Everything else under `<w:body>` (tables, sectPr, drawings, fields, ...) is
 * silently dropped — graceful degradation, no errors thrown for unsupported
 * elements. If the document contains no Heading 1, the body becomes a single
 * untitled chapter.
 */
class DocxParser(
    private val tmpDir: File? = null,
) : BookParser {

    override fun parse(input: InputStream): Document {
        val tmp = File.createTempFile("wbooks-docx-", ".docx", tmpDir)
        try {
            tmp.outputStream().buffered().use { out -> input.copyTo(out) }
            ZipFile(tmp).use { zip -> return parseZip(zip) }
        } finally {
            tmp.delete()
        }
    }

    private fun parseZip(zip: ZipFile): Document {
        val (title, author) = parseCore(readEntry(zip, "docProps/core.xml"))
        val docXml = readEntry(zip, "word/document.xml")
            ?: error("DOCX: missing word/document.xml")
        val doc = Jsoup.parse(docXml, "", Parser.xmlParser())
        val body = doc.selectFirst("w|body")
            ?: error("DOCX: word/document.xml has no <w:body>")
        return Document(title = title, author = author, chapters = splitIntoChapters(body))
    }

    private fun parseCore(xml: String?): Pair<String, String?> {
        if (xml.isNullOrEmpty()) return "" to null
        val d = Jsoup.parse(xml, "", Parser.xmlParser())
        val title = d.selectFirst("dc|title")?.text()?.trim().orEmpty()
        val author = d.selectFirst("dc|creator")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        return title to author
    }

    private fun splitIntoChapters(body: Element): List<Chapter> {
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
            if (!child.tagName().equals("w:p", ignoreCase = true)) continue
            val level = headingLevel(child)
            if (level == 1) {
                flush()
                pendingTitle = paragraphText(child).takeIf { it.isNotBlank() }
            } else if (level != null) {
                val text = paragraphText(child)
                if (text.isNotBlank()) pendingBlocks += Block.Heading(level, text)
            } else {
                val runs = paragraphRuns(child)
                if (runs.isNotEmpty()) pendingBlocks += Block.Paragraph(runs)
            }
        }
        flush()
        if (chapters.isEmpty()) chapters += Chapter(title = null, blocks = emptyList())
        return chapters
    }

    private fun headingLevel(p: Element): Int? {
        val styleVal = p.selectFirst("w|pPr > w|pStyle")
            ?.attr("w:val")?.ifEmpty { null }
            ?: return null
        if (styleVal.equals("Title", ignoreCase = true)) return 1
        if (!styleVal.startsWith("Heading", ignoreCase = true)) return null
        return styleVal.substring("Heading".length).toIntOrNull()?.coerceIn(1, 6)
    }

    private fun paragraphText(p: Element): String =
        p.select("w|t").joinToString("") { it.wholeText() }.trim()

    private fun paragraphRuns(p: Element): List<Run> {
        val out = mutableListOf<Run>()
        for (r in p.select("w|r")) {
            val rPr = r.selectFirst("w|rPr")
            val style = RunStyle(
                bold = rPr?.selectFirst("w|b") != null,
                italic = rPr?.selectFirst("w|i") != null,
                underline = rPr?.selectFirst("w|u") != null,
            )
            for (n in r.children()) {
                when (n.tagName().lowercase()) {
                    "w:t" -> n.wholeText().takeIf { it.isNotEmpty() }
                        ?.let { out += Run(it, style) }
                    "w:tab" -> out += Run("\t", style)
                    "w:br" -> out += Run("\n", style)
                    // w:drawing, w:fldChar, w:instrText, etc. silently dropped
                }
            }
        }
        return out
    }

    private fun readEntry(zip: ZipFile, name: String): String? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
    }
}
