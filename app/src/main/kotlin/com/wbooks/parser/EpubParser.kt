package com.wbooks.parser

import com.wbooks.parser.model.Document
import java.io.InputStream

/**
 * EPUB v2/v3. An EPUB is a ZIP containing:
 *   - META-INF/container.xml -> points at the OPF
 *   - OPF (package document) -> manifest of XHTML items + spine ordering + metadata
 *   - XHTML content files (one per chapter, roughly)
 *
 * Strategy (not yet implemented):
 *   1. Open as [java.util.zip.ZipInputStream], collect entries into memory map.
 *   2. Parse container.xml -> rootfile path.
 *   3. Parse OPF: extract title/creator, manifest items, spine itemrefs.
 *   4. For each spine entry, run the XHTML through [HtmlParser] and concat its chapter blocks.
 *
 * We deliberately don't pull a heavyweight epub library — JDK zip + Jsoup is enough and
 * keeps the APK small (important on watch).
 */
class EpubParser : BookParser {
    override fun parse(input: InputStream): Document {
        TODO("EPUB parsing not implemented yet")
    }
}
