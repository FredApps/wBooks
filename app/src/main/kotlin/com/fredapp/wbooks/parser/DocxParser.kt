package com.fredapp.wbooks.parser

import com.fredapp.wbooks.parser.model.Block
import com.fredapp.wbooks.parser.model.Chapter
import com.fredapp.wbooks.parser.model.Document
import com.fredapp.wbooks.parser.model.Run
import com.fredapp.wbooks.parser.model.RunStyle
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import kotlin.math.roundToInt

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
 * silently dropped â€” graceful degradation, no errors thrown for unsupported
 * elements. If the document contains no Heading 1, the body becomes a single
 * untitled chapter.
 */
class DocxParser(
    private val tmpDir: File? = null,
    private val onProgress: (Int) -> Unit = {},
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
        val (title, author) = zip.getEntry("docProps/core.xml")?.let { entry ->
            zip.getInputStream(entry).use { parseCore(it) }
        } ?: ("" to null)
        onProgress(15)
        val docEntry = zip.getEntry("word/document.xml")
            ?: error("DOCX: missing word/document.xml")
        val chapters = zip.getInputStream(docEntry).use {
            splitIntoChapters(ProgressInputStream(it, docEntry.size, 15, 90, onProgress))
        }
        onProgress(90)
        return Document(title = title, author = author, chapters = chapters)
    }

    private fun parseCore(input: InputStream): Pair<String, String?> {
        val handler = object : DefaultHandler() {
            var title = ""
            var author: String? = null
            private var target: String? = null

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                target = when (tag(localName, qName)) {
                        "title" -> "title"
                        "creator" -> "creator"
                        else -> null
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                val text = String(ch, start, length)
                when (target) {
                    "title" -> title += text
                    "creator" -> author = author.orEmpty() + text
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                target = null
            }
        }
        sax().newSAXParser().parse(input, handler)
        return handler.title.trim() to handler.author?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun splitIntoChapters(input: InputStream): List<Chapter> {
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

        val handler = object : DefaultHandler() {
            var sawBody = false
            private var inBody = false
            private var inParagraph = false
            private var inRun = false
            private var inRunProperties = false
            private var paragraphLevel: Int? = null
            private var paragraphRuns = mutableListOf<Run>()
            private var paragraphText = StringBuilder()
            private var runText = StringBuilder()
            private var runStyle = RunStyle()

            private fun flushRun() {
                if (runText.isEmpty()) return
                val text = runText.toString()
                paragraphText.append(text)
                paragraphRuns += Run(text, runStyle)
                runText = StringBuilder()
            }

            private fun finishParagraph() {
                val level = paragraphLevel
                val text = paragraphText.toString().trim()
                if (level == 1) {
                    flush()
                    pendingTitle = text.takeIf { it.isNotBlank() }
                } else if (level != null) {
                    if (text.isNotBlank()) pendingBlocks += Block.Heading(level, text)
                } else if (paragraphRuns.isNotEmpty()) {
                    pendingBlocks += Block.Paragraph(paragraphRuns.toList())
                }
                paragraphLevel = null
                paragraphRuns = mutableListOf()
                paragraphText = StringBuilder()
            }

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (tag(localName, qName)) {
                        "body" -> {
                            sawBody = true
                            inBody = true
                        }
                        "p" -> if (inBody) {
                            inParagraph = true
                            paragraphLevel = null
                            paragraphRuns = mutableListOf()
                            paragraphText = StringBuilder()
                        }
                        "pStyle" -> if (inParagraph) {
                            paragraphLevel = headingLevel(attributes.attr("val"))
                        }
                        "r" -> if (inParagraph) {
                            inRun = true
                            runText = StringBuilder()
                            runStyle = RunStyle()
                        }
                        "rPr" -> if (inRun) inRunProperties = true
                        "b" -> if (inRunProperties) runStyle = runStyle.copy(bold = true)
                        "i" -> if (inRunProperties) runStyle = runStyle.copy(italic = true)
                        "u" -> if (inRunProperties) runStyle = runStyle.copy(underline = true)
                        "tab" -> if (inRun) runText.append('\t')
                        "br" -> if (inRun) runText.append('\n')
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inRun) runText.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (tag(localName, qName)) {
                        "rPr" -> inRunProperties = false
                        "r" -> if (inRun) {
                            flushRun()
                            inRun = false
                            inRunProperties = false
                        }
                        "p" -> if (inParagraph) {
                            if (inRun) flushRun()
                            finishParagraph()
                            inParagraph = false
                            inRun = false
                            inRunProperties = false
                        }
                        "body" -> inBody = false
                }
            }
        }
        sax().newSAXParser().parse(input, handler)
        if (!handler.sawBody) error("DOCX: word/document.xml has no <w:body>")
        flush()
        if (chapters.isEmpty()) chapters += Chapter(title = null, blocks = emptyList())
        return chapters
    }

    private fun headingLevel(styleVal: String?): Int? {
        if (styleVal.isNullOrEmpty()) return null
        if (styleVal.equals("Title", ignoreCase = true)) return 1
        if (!styleVal.startsWith("Heading", ignoreCase = true)) return null
        return styleVal.substring("Heading".length).toIntOrNull()?.coerceIn(1, 6)
    }

    private fun sax() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
    }

    private fun tag(localName: String?, qName: String?): String =
        localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')

    private fun Attributes?.attr(localName: String): String? {
        if (this == null) return null
        for (i in 0 until length) {
            val name = getLocalName(i).takeIf { it.isNotEmpty() } ?: getQName(i).substringAfter(':')
            if (name == localName) {
                return getValue(i)
            }
        }
        return null
    }

    private class ProgressInputStream(
        private val delegate: InputStream,
        private val totalBytes: Long,
        private val startPercent: Int,
        private val endPercent: Int,
        private val onProgress: (Int) -> Unit,
    ) : InputStream() {
        private var bytesRead = 0L
        private var lastPercent = startPercent

        override fun read(): Int {
            val value = delegate.read()
            if (value != -1) report(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = delegate.read(buffer, offset, length)
            if (read > 0) report(read)
            return read
        }

        override fun close() {
            delegate.close()
        }

        private fun report(delta: Int) {
            if (totalBytes <= 0L) return
            bytesRead += delta
            val span = endPercent - startPercent
            val next = (startPercent + (bytesRead.toDouble() / totalBytes.toDouble() * span)).roundToInt()
                .coerceIn(startPercent, endPercent)
            if (next > lastPercent) {
                lastPercent = next
                onProgress(next)
            }
        }
    }
}
