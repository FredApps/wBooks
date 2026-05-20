package com.fredapp.wbooksutil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPDS feed parsing regression net. Uses a hand-rolled fixture that exercises:
 *
 * - Two real book entries (one EPUB-preferred, one TXT-only).
 * - A "navigation" entry with no acquisition link (must be skipped).
 * - A book with multiple acquisitions in order TXT, MOBI, EPUB to verify EPUB
 *   wins regardless of position.
 * - Root-relative href that must be resolved against the PG base.
 *
 * Tests are pure (no HTTP) â€” they call the internal [GutenbergRepository.parseFeed]
 * directly with a fixed XML string.
 */
class GutenbergRepositoryTest {

    private val repo = GutenbergRepository()

    @Test
    fun parses_book_entries_and_skips_navigation() {
        val books = repo.parseFeed(FIXTURE)
        assertEquals(2, books.size)
        assertEquals(listOf("Moby Dick", "Pride and Prejudice"), books.map { it.title })
    }

    @Test
    fun prefers_epub_over_txt_regardless_of_link_order() {
        val moby = repo.parseFeed(FIXTURE).first { it.title == "Moby Dick" }
        assertEquals("epub", moby.extension)
        assertTrue(
            "expected EPUB URL, got ${moby.downloadUrl}",
            moby.downloadUrl.endsWith(".epub"),
        )
    }

    @Test
    fun falls_back_to_txt_when_no_epub_available() {
        val pride = repo.parseFeed(FIXTURE).first { it.title == "Pride and Prejudice" }
        assertEquals("txt", pride.extension)
        assertTrue(pride.downloadUrl.endsWith(".txt"))
    }

    @Test
    fun resolves_root_relative_hrefs_against_base() {
        val pride = repo.parseFeed(FIXTURE).first { it.title == "Pride and Prejudice" }
        assertTrue(
            "expected absolute https URL, got ${pride.downloadUrl}",
            pride.downloadUrl.startsWith("https://www.gutenberg.org/"),
        )
        val afterScheme = pride.downloadUrl.removePrefix("https://")
        assertTrue(
            "expected single slash after host, got ${pride.downloadUrl}",
            !afterScheme.contains("//"),
        )
    }

    @Test
    fun extracts_author_and_summary() {
        val moby = repo.parseFeed(FIXTURE).first { it.title == "Moby Dick" }
        assertEquals("Herman Melville", moby.author)
        assertNotNull(moby.summary)
        assertTrue(
            "summary should contain key phrase, got: ${moby.summary}",
            moby.summary!!.contains("whaling voyage"),
        )
    }

    @Test
    fun missing_author_becomes_null() {
        val pride = repo.parseFeed(FIXTURE).first { it.title == "Pride and Prejudice" }
        assertNull(pride.author)
    }

    @Test
    fun empty_feed_returns_empty_list() {
        val empty = """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"></feed>"""
        assertEquals(emptyList<GutenbergBook>(), repo.parseFeed(empty))
    }

    @Test
    fun entry_without_supported_format_is_skipped() {
        val xml = """
            <?xml version="1.0"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>urn:gutenberg:999</id>
                <title>Only Mobi</title>
                <link rel="http://opds-spec.org/acquisition"
                      type="application/x-mobipocket-ebook"
                      href="/cache/epub/999/pg999.mobi"/>
              </entry>
            </feed>
        """.trimIndent()
        assertEquals(emptyList<GutenbergBook>(), repo.parseFeed(xml))
    }

    private companion object {
        /** Synthetic feed modelled on PG's OPDS shape â€” minimal but realistic. */
        val FIXTURE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"
                  xmlns:opds="http://opds-spec.org/2010/catalog">
              <id>http://www.gutenberg.org/ebooks.opds/</id>
              <title>Project Gutenberg</title>
              <entry>
                <id>urn:gutenberg:nav:popular</id>
                <title>Popular</title>
                <link rel="subsection" href="/ebooks/search/?sort_order=downloads"/>
              </entry>
              <entry>
                <id>urn:gutenberg:2701</id>
                <title>Moby Dick</title>
                <author><name>Herman Melville</name></author>
                <summary>A whaling voyage narrated by Ishmael, in pursuit of the white whale.</summary>
                <link rel="http://opds-spec.org/acquisition" type="text/plain; charset=utf-8"
                      href="https://www.gutenberg.org/cache/epub/2701/pg2701.txt"/>
                <link rel="http://opds-spec.org/acquisition" type="application/x-mobipocket-ebook"
                      href="https://www.gutenberg.org/cache/epub/2701/pg2701.mobi"/>
                <link rel="http://opds-spec.org/acquisition" type="application/epub+zip"
                      href="https://www.gutenberg.org/cache/epub/2701/pg2701.epub"/>
              </entry>
              <entry>
                <id>urn:gutenberg:1342</id>
                <title>Pride and Prejudice</title>
                <link rel="http://opds-spec.org/acquisition" type="text/plain; charset=utf-8"
                      href="/cache/epub/1342/pg1342.txt"/>
              </entry>
            </feed>
        """.trimIndent()
    }
}
