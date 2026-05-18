package com.wbooks.parser

import com.wbooks.data.book.BookFormat
import com.wbooks.parser.model.Document
import java.io.InputStream

/** A parser that lowers one source format into the parser-neutral [Document] model. */
interface BookParser {
    fun parse(input: InputStream): Document
}

fun parserFor(format: BookFormat): BookParser = when (format) {
    BookFormat.EPUB -> EpubParser()
    BookFormat.TXT -> TxtParser()
    BookFormat.FB2 -> Fb2Parser()
    BookFormat.HTML -> HtmlParser()
}
