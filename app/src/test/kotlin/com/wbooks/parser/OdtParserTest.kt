package com.wbooks.parser

import com.wbooks.parser.model.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parses the bundled time-machine.odt seed and checks the core promises
 * the [OdtParser] is supposed to keep: title + author from meta.xml, a
 * non-trivial Heading-1-split chapter count, and prose blocks in those
 * chapters. Mirrors [DocxParserTest] — one golden file catches the
 * common namespace-selector and style-resolution regressions.
 */
class OdtParserTest {

    private val seed = File("src/main/assets/seed-books/time-machine.odt")

    @Test
    fun parses_title_and_author_from_meta() {
        val doc = seed.inputStream().use { OdtParser().parse(it) }
        assertEquals("The Time Machine", doc.title)
        assertEquals("H. G. Wells", doc.author)
    }

    @Test
    fun splits_into_multiple_chapters_on_outline_level_one() {
        val doc = seed.inputStream().use { OdtParser().parse(it) }
        // The Gutenberg edition has 12 numbered chapters + an epilogue; assert
        // >1 to stay tolerant of pandoc's outline-level emission changes.
        assertTrue(
            "expected multiple chapters, got ${doc.chapters.size}",
            doc.chapters.size >= 2,
        )
    }

    @Test
    fun chapters_contain_prose_blocks() {
        val doc = seed.inputStream().use { OdtParser().parse(it) }
        val paragraphs = doc.chapters.sumOf { c ->
            c.blocks.count { it is Block.Paragraph }
        }
        assertTrue("expected paragraphs, got $paragraphs", paragraphs > 20)
    }

    @Test
    fun paragraphs_have_non_empty_runs() {
        val doc = seed.inputStream().use { OdtParser().parse(it) }
        val firstParagraph = doc.chapters.flatMap { it.blocks }
            .filterIsInstance<Block.Paragraph>()
            .first()
        val text = firstParagraph.runs.joinToString("") { it.text }
        assertTrue("first paragraph is empty: '$text'", text.isNotBlank())
    }
}
