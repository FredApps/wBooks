package com.fredapp.wbooks.parser

import com.fredapp.wbooks.data.book.BookFormat
import com.fredapp.wbooks.parser.model.Document
import java.io.InputStream

/** A parser that lowers one source format into the parser-neutral [Document] model. */
interface BookParser {
    fun parse(input: InputStream): Document
}

fun parserFor(format: BookFormat, onProgress: (Int) -> Unit = {}): BookParser = when (format) {
    BookFormat.EPUB -> EpubParser(onProgress = onProgress)
    BookFormat.TXT -> TxtParser(onProgress = onProgress)
    BookFormat.FB2 -> Fb2Parser(onProgress = onProgress)
    BookFormat.HTML -> HtmlParser(onProgress = onProgress)
    BookFormat.DOCX -> DocxParser(onProgress = onProgress)
    BookFormat.ODT -> OdtParser(onProgress = onProgress)
}
