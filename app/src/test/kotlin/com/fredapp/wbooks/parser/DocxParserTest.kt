package com.fredapp.wbooks.parser

import com.fredapp.wbooks.parser.model.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parses the bundled jekyll-and-hyde.docx seed and checks the core promises
 * the [DocxParser] is supposed to keep: title + author from docProps/core.xml,
 * a non-trivial Heading-1-split chapter count, and prose blocks in those
 * chapters. If pandoc's output ever drifts these need updating, but a single
 * golden file is enough to catch namespace-selector regressions.
 */
class DocxParserTest {

    private val seed = File("src/main/assets/seed-books/jekyll-and-hyde.docx")

    @Test
    fun parses_title_and_author_from_core_props() {
        val doc = seed.inputStream().use { DocxParser().parse(it) }
        assertEquals("The Strange Case of Dr Jekyll and Mr Hyde", doc.title)
        assertEquals("Robert Louis Stevenson", doc.author)
    }

    @Test
    fun splits_into_multiple_chapters_on_heading_one() {
        val doc = seed.inputStream().use { DocxParser().parse(it) }
        // The Gutenberg edition has 10 chapters; we just assert >1 so the test
        // doesn't break if pandoc tweaks its heading style emission.
        assertTrue(
            "expected multiple chapters, got ${doc.chapters.size}",
            doc.chapters.size >= 2,
        )
    }

    @Test
    fun chapters_contain_prose_blocks() {
        val doc = seed.inputStream().use { DocxParser().parse(it) }
        val paragraphs = doc.chapters.sumOf { c ->
            c.blocks.count { it is Block.Paragraph }
        }
        assertTrue("expected paragraphs, got $paragraphs", paragraphs > 20)
    }

    @Test
    fun paragraphs_have_non_empty_runs() {
        val doc = seed.inputStream().use { DocxParser().parse(it) }
        val firstParagraph = doc.chapters.flatMap { it.blocks }
            .filterIsInstance<Block.Paragraph>()
            .first()
        val text = firstParagraph.runs.joinToString("") { it.text }
        assertTrue("first paragraph is empty: '$text'", text.isNotBlank())
    }
}
