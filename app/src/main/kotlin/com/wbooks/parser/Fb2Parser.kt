package com.wbooks.parser

import com.wbooks.parser.model.Document
import java.io.InputStream

/**
 * FictionBook 2 (.fb2). Single XML document — no ZIP container (the .fb2.zip variant is
 * handled by unwrapping the ZIP first, then feeding the inner XML to this parser).
 *
 * Schema we care about (under the FictionBook root):
 *   <description><title-info><book-title/><author><first-name/><last-name/></author>...
 *   <body>
 *     <section><title><p>...</p></title> <p>...</p> <empty-line/> ... </section>*
 *   </body>
 *
 * Inline elements: <emphasis> = italic, <strong> = bold, <code> = code run.
 * <empty-line/> maps to a [com.wbooks.parser.model.Block.Divider] when between paragraphs.
 *
 * Not implemented yet — XML parsing via android.util.Xml (KXML2 under the hood) keeps
 * memory low for large fb2 files.
 */
class Fb2Parser : BookParser {
    override fun parse(input: InputStream): Document {
        TODO("FB2 parsing not implemented yet")
    }
}
