package com.fredapp.wbooksutil

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.InputStream
import java.io.StringWriter

/**
 * Experimental PDF → HTML conversion for the utility app.
 *
 * PDF has no semantic structure (no paragraphs, no headings — just glyphs at
 * coordinates), so this is heuristic. We sniff bold/italic from the PostScript
 * font name and promote larger-than-median runs to `<h1>`/`<h2>`/`<h3>`.
 * Output targets the watch's HtmlParser, which understands `<h1-6>`, `<p>`,
 * `<strong>`, `<em>`.
 *
 * Scanned image PDFs produce empty or garbage text — the warning dialog in
 * MainActivity tells the user up front. We don't OCR.
 */
object PdfConverter {

    data class Result(
        val html: String,
        val pageCount: Int,
        val textChars: Int,
    )

    fun convert(input: InputStream, title: String): Result {
        return PDDocument.load(input).use { doc ->
            val stripper = FormattingStripper().apply {
                sortByPosition = true
            }
            val sink = StringWriter()
            stripper.writeText(doc, sink)
            val html = buildHtml(title, stripper.finishAndBuildBody())
            Result(
                html = html,
                pageCount = doc.numberOfPages,
                textChars = stripper.totalChars,
            )
        }
    }

    private fun buildHtml(title: String, body: String): String {
        val safeTitle = escape(title)
        return buildString {
            append("<!doctype html>\n<html><head><meta charset=\"utf-8\"><title>")
            append(safeTitle)
            append("</title></head><body>\n")
            append(body)
            append("</body></html>\n")
        }
    }

    private class FormattingStripper : PDFTextStripper() {

        private data class StyledRun(val text: String, val bold: Boolean, val italic: Boolean)
        private data class PendingPara(val runs: MutableList<StyledRun>, val sizes: MutableList<Float>)

        private val paragraphs = mutableListOf<PendingPara>()
        private var current: PendingPara? = null
        private val allCharSizes = mutableListOf<Float>()
        var totalChars: Int = 0
            private set

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (text.isEmpty()) return
            val pos = textPositions.firstOrNull()
            val fontName = pos?.font?.name.orEmpty()
            val bold = looksBold(fontName)
            val italic = looksItalic(fontName)
            val size = pos?.fontSizeInPt ?: 0f

            totalChars += text.length
            if (size > 0f) {
                // sample once per run, weighted by char count
                repeat(text.length.coerceAtMost(64)) { allCharSizes += size }
            }

            val para = ensureParagraph()
            para.runs += StyledRun(text, bold, italic)
            if (size > 0f) para.sizes += size
        }

        override fun writeLineSeparator() {
            // Join wrapped lines within a paragraph with a single space, not a newline,
            // so words don't get glued together.
            current?.runs?.let {
                if (it.isNotEmpty() && !it.last().text.endsWith(' ')) {
                    it += StyledRun(" ", bold = false, italic = false)
                }
            }
        }

        override fun writeParagraphSeparator() {
            flushParagraph()
        }

        override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
            flushParagraph()
            super.endPage(page)
        }

        private fun ensureParagraph(): PendingPara {
            val c = current
            if (c != null) return c
            val fresh = PendingPara(mutableListOf(), mutableListOf())
            current = fresh
            return fresh
        }

        private fun flushParagraph() {
            val p = current ?: return
            current = null
            // collapse pure-whitespace paragraphs
            if (p.runs.none { it.text.any { ch -> !ch.isWhitespace() } }) return
            paragraphs += p
        }

        fun finishAndBuildBody(): String {
            flushParagraph()
            if (paragraphs.isEmpty()) {
                return "<p><em>No extractable text in this PDF. " +
                    "It may be a scanned-image document.</em></p>\n"
            }
            val bodySize = medianBodySize()
            val h1Cut = bodySize * 1.6f
            val h2Cut = bodySize * 1.3f
            val h3Cut = bodySize * 1.1f
            val sb = StringBuilder(paragraphs.size * 64)
            for (p in paragraphs) {
                val sized = p.sizes.maxOrNull() ?: bodySize
                val tag = when {
                    sized >= h1Cut -> "h1"
                    sized >= h2Cut -> "h2"
                    sized >= h3Cut -> "h3"
                    else -> "p"
                }
                sb.append('<').append(tag).append('>')
                if (tag == "p") {
                    appendInline(sb, p.runs)
                } else {
                    // Headings: drop inline styling — extra bold on h1 looks silly
                    // and most heading fonts already register as bold.
                    val plain = p.runs.joinToString("") { it.text }
                    sb.append(escape(plain.trim()))
                }
                sb.append("</").append(tag).append(">\n")
            }
            return sb.toString()
        }

        private fun appendInline(sb: StringBuilder, runs: List<StyledRun>) {
            // Coalesce adjacent runs that share style to keep markup small.
            val merged = mutableListOf<StyledRun>()
            for (r in runs) {
                val last = merged.lastOrNull()
                if (last != null && last.bold == r.bold && last.italic == r.italic) {
                    merged[merged.lastIndex] = last.copy(text = last.text + r.text)
                } else {
                    merged += r
                }
            }
            for (r in merged) {
                val text = escape(r.text)
                when {
                    r.bold && r.italic -> sb.append("<strong><em>").append(text).append("</em></strong>")
                    r.bold -> sb.append("<strong>").append(text).append("</strong>")
                    r.italic -> sb.append("<em>").append(text).append("</em>")
                    else -> sb.append(text)
                }
            }
        }

        private fun medianBodySize(): Float {
            if (allCharSizes.isEmpty()) return 12f
            val sorted = allCharSizes.sorted()
            return sorted[sorted.size / 2]
        }

        companion object {
            private fun looksBold(fontName: String): Boolean {
                val n = fontName.lowercase()
                return n.contains("bold") || n.contains("black") || n.contains("heavy")
            }

            private fun looksItalic(fontName: String): Boolean {
                val n = fontName.lowercase()
                return n.contains("italic") || n.contains("oblique")
            }
        }
    }

    private fun escape(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> sb.append(c)
        }
        return sb.toString()
    }
}
