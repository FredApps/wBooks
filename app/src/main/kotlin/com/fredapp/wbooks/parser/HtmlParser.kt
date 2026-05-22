package com.fredapp.wbooks.parser

import android.util.Base64
import com.fredapp.wbooks.parser.model.Block
import com.fredapp.wbooks.parser.model.Chapter
import com.fredapp.wbooks.parser.model.Document
import com.fredapp.wbooks.parser.model.Run
import com.fredapp.wbooks.parser.model.RunStyle
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.InputStream

/**
 * Resolves a (possibly relative) image `src` into encoded bytes plus an
 * optional mime hint. Returns `null` to drop the image. The base href passed
 * alongside is the location of the document the `<img>` was read from, so
 * resolvers can normalise relative paths (e.g. EPUB chapter dir + src).
 */
typealias ImageResolver = (src: String, baseHref: String) -> Pair<ByteArray, String?>?

/**
 * HTML / xhtml. Walks the body, mapping a curated subset of tags into [Block]/[Run].
 *
 * `<pre><code>` blocks become [Block.Code]; the rendering layer is responsible for
 * applying syntax colouring to them. Language is taken from `class="language-xxx"`
 * (Pandoc/Pygments convention) when present.
 */
class HtmlParser(
    private val onProgress: (Int) -> Unit = {},
    /**
     * Hook for parsers that have a binary side-channel (e.g. EpubParser with a
     * ZipFile). For the standalone HTML path this stays null and we fall back
     * to the built-in `data:` URI resolver, so self-contained HTML books with
     * inline base64 images still render. Relative/external URLs are dropped.
     */
    private val imageResolver: ImageResolver? = null,
) : BookParser {

    /** Combined resolver: try the caller's resolver first, then `data:` URIs. */
    private val effectiveImageResolver: ImageResolver = { src, base ->
        imageResolver?.invoke(src, base) ?: decodeDataUri(src)
    }
    override fun parse(input: InputStream): Document {
        onProgress(15)
        val doc = Jsoup.parse(input, Charsets.UTF_8.name(), "")
        onProgress(55)
        return fromJsoup(doc)
    }

    /** Parse already-decoded HTML text. Used by [EpubParser] when feeding spine chapters. */
    fun parse(html: String): Document {
        onProgress(45)
        return fromJsoup(Jsoup.parse(html))
    }

    /** Convert a single XHTML body into a list of [Block]s suitable for one [Chapter]. */
    internal fun blocksOf(html: String, baseHref: String = ""): List<Block> {
        val out = mutableListOf<Block>()
        walk(Jsoup.parse(html).body(), out, baseHref)
        return out
    }

    private fun fromJsoup(doc: org.jsoup.nodes.Document): Document {
        val title = doc.title().ifBlank { doc.selectFirst("h1")?.text().orEmpty() }
        val blocks = mutableListOf<Block>()
        walk(doc.body(), blocks, baseHref = "")
        onProgress(90)
        return Document(title = title, author = null, chapters = listOf(Chapter(null, blocks)))
    }

    private fun walk(root: Element, out: MutableList<Block>, baseHref: String) {
        for (child in root.children()) {
            when (child.tagName().lowercase()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val level = child.tagName().substring(1).toInt()
                    out += Block.Heading(level, child.text())
                }
                "hr" -> out += Block.Divider
                "p" -> {
                    // A <p> may wrap an <img>; emit the image alongside the
                    // text rather than dropping it.
                    for (img in child.select("img")) appendImage(img, out, baseHref)
                    val r = runs(child)
                    if (r.any { it.text.isNotBlank() }) out += Block.Paragraph(r)
                }
                "img" -> appendImage(child, out, baseHref)
                "figure" -> walk(child, out, baseHref)
                "pre" -> {
                    val codeEl = child.selectFirst("code") ?: child
                    val lang = codeEl.classNames().firstOrNull { it.startsWith("language-") }
                        ?.removePrefix("language-")
                    out += Block.Code(language = lang, text = codeEl.wholeText())
                }
                "blockquote", "div", "section", "article" -> walk(child, out, baseHref)
                "ul", "ol" -> {
                    // TODO real list rendering; for now flatten each <li> to a paragraph
                    for (li in child.select("> li")) out += Block.Paragraph(runs(li))
                }
                else -> {
                    // Unknown block: if it contains block-level children, recurse; else treat as paragraph.
                    if (child.children().any { it.isBlock() }) walk(child, out, baseHref)
                    else out += Block.Paragraph(runs(child))
                }
            }
        }
    }

    private fun appendImage(img: Element, out: MutableList<Block>, baseHref: String) {
        val src = img.attr("src").takeIf { it.isNotBlank() } ?: return
        val resolved = effectiveImageResolver(src, baseHref) ?: return
        val (bytes, mime) = resolved
        if (bytes.isEmpty()) return
        out += Block.Image(bytes = bytes, mime = mime, alt = img.attr("alt"))
    }

    /**
     * Decode `data:image/<sub>;base64,<payload>` URIs. Non-base64 data URIs
     * (URL-encoded inline) are rejected because they only ever carry SVG /
     * text payloads in practice, which BitmapFactory can't decode anyway.
     */
    private fun decodeDataUri(src: String): Pair<ByteArray, String?>? {
        if (!src.startsWith("data:")) return null
        val comma = src.indexOf(',')
        if (comma < 0) return null
        val header = src.substring(5, comma) // strip "data:"
        if (!header.contains(";base64")) return null
        val mime = header.substringBefore(';').ifBlank { null }
        val payload = src.substring(comma + 1)
        return try {
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            bytes to mime
        } catch (e: IllegalArgumentException) {
            null
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
