package com.fredapp.wbooks.parser

import com.fredapp.wbooks.parser.model.Chapter
import com.fredapp.wbooks.parser.model.Document
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * EPUB 2/3 parser.
 *
 * The stream is spooled to a temp file so we can open it as a [ZipFile] and read
 * entries on demand instead of holding the whole archive in memory. For an EPUB
 * of any size we only ever decompress one chapter's bytes at a time, which keeps
 * peak memory at roughly `parsed Document + one XHTML chapter` regardless of how
 * big the source file is.
 *
 * Pipeline:
 *   1. Spool the stream to [tmpDir] (defaults to JVM tmpdir).
 *   2. Read META-INF/container.xml to find the OPF rootfile.
 *   3. Parse the OPF (Jsoup XML mode): dc:title, dc:creator, manifest idâ†’href, spine order.
 *   4. For each spine entry, resolve its XHTML bytes from the zip and lower with [HtmlParser].
 */
class EpubParser(
    private val tmpDir: File? = null,
    private val onProgress: (Int) -> Unit = {},
) : BookParser {
    // The HTML parser is constructed per-archive once we have a ZipFile in
    // hand - each EPUB needs its own image resolver bound to that zip.

    override fun parse(input: InputStream): Document {
        val tmp = File.createTempFile("wbooks-epub-", ".epub", tmpDir)
        try {
            onProgress(10)
            tmp.outputStream().buffered().use { out -> input.copyTo(out) }
            onProgress(20)
            ZipFile(tmp).use { zip ->
                return parseZip(zip)
            }
        } finally {
            tmp.delete()
        }
    }

    fun parse(file: File): Document {
        onProgress(20)
        return ZipFile(file).use { zip -> parseZip(zip) }
    }

    private fun parseZip(zip: ZipFile): Document {
        val containerXml = zip.readTextEntry("META-INF/container.xml")
            ?: error("EPUB: missing META-INF/container.xml")
        onProgress(30)
        val opfPath = parseContainer(containerXml)
        val opfXml = zip.readTextEntry(opfPath) ?: error("EPUB: missing OPF at $opfPath")
        val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
        val opf = parseOpf(opfXml)
        onProgress(45)

        // Image resolver: src is interpreted relative to the chapter file
        // (baseHref), normalised, then looked up as a zip entry. Mime comes
        // from the manifest when we can match the path, else inferred from
        // the extension. We restrict to BitmapFactory-decodable types.
        val mimeByHref = opf.mimeByHref
        val resolver: ImageResolver = { src, baseHref ->
            val baseDir = baseHref.substringBeforeLast('/', missingDelimiterValue = "")
            val resolved = joinEpubPath(baseDir, cleanResourceHref(src).removePrefix("./"))
            val bytes = zip.readBinaryEntry(resolved)
            if (bytes == null) null
            else {
                val mime = mimeByHref[resolved]
                    ?: mimeByHref[resolved.removePrefix("$opfDir/")]
                    ?: guessMimeFromExt(resolved)
                if (isSupportedImageMime(mime)) bytes to mime else null
            }
        }
        val htmlParser = HtmlParser(imageResolver = resolver)

        val total = opf.spineHrefs.size.coerceAtLeast(1)
        val chapters = opf.spineHrefs.mapIndexedNotNull { index, href ->
            val full = joinEpubPath(opfDir, href)
            val xhtml = zip.readTextEntry(full) ?: return@mapIndexedNotNull null
            onProgress(45 + ((index + 1) * 40 / total))
            Chapter(title = null, blocks = htmlParser.blocksOf(xhtml, baseHref = full))
        }

        onProgress(90)
        return Document(
            title = opf.title.orEmpty(),
            author = opf.creator,
            chapters = chapters,
        )
    }

    private data class OpfData(
        val title: String?,
        val creator: String?,
        val spineHrefs: List<String>,
        /** Map of manifest href -> media-type. Used to identify image entries. */
        val mimeByHref: Map<String, String>,
    )

    /** Restrict to formats Android's `BitmapFactory` decodes natively. */
    private fun isSupportedImageMime(mime: String?): Boolean = when (mime?.lowercase()) {
        "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp", "image/bmp" -> true
        else -> false
    }

    private fun guessMimeFromExt(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> null
    }

    private fun cleanResourceHref(raw: String): String {
        val withoutAnchor = raw.substringBefore('#').substringBefore('?')
        return runCatching { URLDecoder.decode(withoutAnchor, Charsets.UTF_8.name()) }
            .getOrDefault(withoutAnchor)
    }

    private fun parseContainer(xml: String): String {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val rootfile = doc.selectFirst("rootfile")
            ?: error("EPUB: container.xml has no <rootfile>")
        return rootfile.attr("full-path").trimStart('/')
    }

    private fun parseOpf(xml: String): OpfData {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())

        // dc:title and dc:creator â€” Jsoup uses pipe syntax for namespace prefixes.
        val title = doc.selectFirst("dc|title")?.text()?.takeIf { it.isNotBlank() }
        val creator = doc.selectFirst("dc|creator")?.text()?.takeIf { it.isNotBlank() }

        val manifestItems = doc.select("manifest > item")
        val hrefById = manifestItems.associate { it.attr("id") to it.attr("href") }
        val mimeByHref = manifestItems.associate { it.attr("href") to it.attr("media-type") }
        val spineHrefs = doc.select("spine > itemref").mapNotNull { hrefById[it.attr("idref")] }

        return OpfData(title = title, creator = creator, spineHrefs = spineHrefs, mimeByHref = mimeByHref)
    }

    /**
     * Resolve a spine href relative to the OPF's directory, normalising any `../`.
     * The result is used purely as a [ZipFile] entry key (not a filesystem path),
     * so a malformed href can only fail the lookup â€” there is no zip-slip surface.
     */
    private fun joinEpubPath(opfDir: String, href: String): String {
        val raw = if (opfDir.isEmpty()) href else "$opfDir/$href"
        val parts = ArrayDeque<String>()
        for (segment in raw.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }
}
