package com.wbooks.parser

import com.wbooks.parser.model.Chapter
import com.wbooks.parser.model.Document
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
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
 *   3. Parse the OPF (Jsoup XML mode): dc:title, dc:creator, manifest id→href, spine order.
 *   4. For each spine entry, resolve its XHTML bytes from the zip and lower with [HtmlParser].
 */
class EpubParser(
    private val tmpDir: File? = null,
) : BookParser {

    private val htmlParser = HtmlParser()

    override fun parse(input: InputStream): Document {
        val tmp = File.createTempFile("wbooks-epub-", ".epub", tmpDir).apply { deleteOnExit() }
        try {
            tmp.outputStream().buffered().use { out -> input.copyTo(out) }
            ZipFile(tmp).use { zip ->
                return parseZip(zip)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun parseZip(zip: ZipFile): Document {
        val containerXml = readEntryAsString(zip, "META-INF/container.xml")
            ?: error("EPUB: missing META-INF/container.xml")
        val opfPath = parseContainer(containerXml)
        val opfXml = readEntryAsString(zip, opfPath) ?: error("EPUB: missing OPF at $opfPath")
        val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
        val opf = parseOpf(opfXml)

        val chapters = opf.spineHrefs.mapNotNull { href ->
            val full = joinEpubPath(opfDir, href)
            val xhtml = readEntryAsString(zip, full) ?: return@mapNotNull null
            Chapter(title = null, blocks = htmlParser.blocksOf(xhtml))
        }

        return Document(
            title = opf.title.orEmpty(),
            author = opf.creator,
            chapters = chapters,
        )
    }

    private fun readEntryAsString(zip: ZipFile, name: String): String? {
        val entry = zip.getEntry(name.trimStart('/')) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    private data class OpfData(
        val title: String?,
        val creator: String?,
        val spineHrefs: List<String>,
    )

    private fun parseContainer(xml: String): String {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val rootfile = doc.selectFirst("rootfile")
            ?: error("EPUB: container.xml has no <rootfile>")
        return rootfile.attr("full-path").trimStart('/')
    }

    private fun parseOpf(xml: String): OpfData {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())

        // dc:title and dc:creator — Jsoup uses pipe syntax for namespace prefixes.
        val title = doc.selectFirst("dc|title")?.text()?.takeIf { it.isNotBlank() }
        val creator = doc.selectFirst("dc|creator")?.text()?.takeIf { it.isNotBlank() }

        val hrefById = doc.select("manifest > item").associate { it.attr("id") to it.attr("href") }
        val spineHrefs = doc.select("spine > itemref").mapNotNull { hrefById[it.attr("idref")] }

        return OpfData(title = title, creator = creator, spineHrefs = spineHrefs)
    }

    /** Resolve a spine href relative to the OPF's directory, normalising any `../`. */
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
