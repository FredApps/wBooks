package com.wbooks.parser

import com.wbooks.parser.model.Chapter
import com.wbooks.parser.model.Document
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * EPUB 2/3 parser.
 *
 * Pipeline:
 *   1. Walk the ZIP once, collecting every entry into a path -> bytes map.
 *   2. Read META-INF/container.xml to find the OPF rootfile.
 *   3. Parse the OPF (it's XML; we use Jsoup in XML mode so we keep one parser).
 *        - <metadata>     -> title / creator
 *        - <manifest>     -> id -> href map
 *        - <spine>        -> ordered list of itemref idref
 *   4. For each spine entry, resolve its XHTML bytes and feed them to [HtmlParser]
 *      to get a list of blocks; wrap each as a [Chapter] in document order.
 *
 * Memory note: holding the whole archive in memory is fine for novels (typically
 * 1-5 MB compressed). If we ever ship large illustrated EPUBs we should switch to
 * a backing temp file + java.util.zip.ZipFile for random access.
 */
class EpubParser : BookParser {

    private val htmlParser = HtmlParser()

    override fun parse(input: InputStream): Document {
        val entries = readAllEntries(input)

        val containerXml = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            ?: error("EPUB: missing META-INF/container.xml")
        val opfPath = parseContainer(containerXml)
        val opfBytes = entries[opfPath] ?: error("EPUB: missing OPF at $opfPath")
        val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
        val opf = parseOpf(opfBytes.toString(Charsets.UTF_8))

        val chapters = opf.spineHrefs.mapNotNull { href ->
            val full = joinEpubPath(opfDir, href)
            val bytes = entries[full] ?: return@mapNotNull null
            val blocks = htmlParser.blocksOf(bytes.toString(Charsets.UTF_8))
            Chapter(title = null, blocks = blocks)
        }

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
    )

    private fun readAllEntries(input: InputStream): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    out[entry.name.trimStart('/')] = zis.readBytes()
                }
                zis.closeEntry()
            }
        }
        return out
    }

    private fun parseContainer(xml: String): String {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val rootfile = doc.selectFirst("rootfile")
            ?: error("EPUB: container.xml has no <rootfile>")
        return rootfile.attr("full-path").trimStart('/')
    }

    private fun parseOpf(xml: String): OpfData {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())

        // dc:title and dc:creator — selectors with colons need escaping in Jsoup.
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
