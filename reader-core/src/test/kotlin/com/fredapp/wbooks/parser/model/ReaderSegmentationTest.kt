package com.fredapp.wbooks.parser.model

import com.fredapp.wbooks.data.position.BookPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSegmentationTest {

    private fun docOf(vararg paragraphs: String): Document =
        Document(
            title = "T",
            author = null,
            chapters = listOf(
                Chapter(
                    title = null,
                    blocks = paragraphs.map { Block.Paragraph(listOf(Run(it))) },
                ),
            ),
        )

    @Test
    fun `splits sentences at sentence and clause boundaries`() {
        val sentences = docOf("The cat sat down. Then it ran away, very fast indeed.").segmentSentences()
        assertEquals(
            listOf("The cat sat down.", "Then it ran away,", "very fast indeed."),
            sentences.map { it.text },
        )
    }

    @Test
    fun `keeps decimals and abbreviations intact`() {
        // "3.14" and "Mr.Smith" have no trailing space after the period, so they
        // must not be split; the real boundary is the closing period.
        val sentences = docOf("Pi is 3.14 and Mr.Smith agreed with the whole class.").segmentSentences()
        assertEquals(listOf("Pi is 3.14 and Mr.Smith agreed with the whole class."), sentences.map { it.text })
    }

    @Test
    fun `does not emit tiny fragments as their own sentence`() {
        // "Go." is below MIN_FRAGMENT_SPACES, so it stays attached to what follows.
        val sentences = docOf("Go. The long journey had finally begun for them.").segmentSentences()
        assertEquals(listOf("Go. The long journey had finally begun for them."), sentences.map { it.text })
    }

    @Test
    fun `sentence positions carry chapter block and sub index`() {
        val sentences = docOf("One sentence here now. Two sentences here now.").segmentSentences()
        assertEquals(BookPosition(0, 0, 0), sentences[0].position)
        assertEquals(BookPosition(0, 0, 1), sentences[1].position)
    }

    @Test
    fun `tokenize splits on whitespace and tags word positions`() {
        val words = docOf("alpha beta gamma").tokenizeWords()
        assertEquals(listOf("alpha", "beta", "gamma"), words.map { it.text })
        assertEquals(BookPosition(0, 0, 0), words[0].position)
        assertEquals(BookPosition(0, 0, 2), words[2].position)
    }

    @Test
    fun `indexAtOrAfter finds first item at or after the target`() {
        val words = docOf("alpha beta gamma delta").tokenizeWords()
        assertEquals(0, words.indexAtOrAfter(BookPosition.START))
        assertEquals(2, words.indexAtOrAfter(BookPosition(0, 0, 2)))
        // Past the end clamps to the last index.
        assertEquals(words.lastIndex, words.indexAtOrAfter(BookPosition(9, 9, 9)))
    }

    @Test
    fun `focalIndex follows the standard RSVP table`() {
        assertEquals(0, focalIndex("a"))
        assertEquals(1, focalIndex("cats"))
        assertEquals(2, focalIndex("reading"))
        assertEquals(3, focalIndex("complicated"))
        assertEquals(4, focalIndex("incomprehensible"))
    }
}
