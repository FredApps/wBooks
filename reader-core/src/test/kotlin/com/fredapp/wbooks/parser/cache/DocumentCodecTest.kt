package com.fredapp.wbooks.parser.cache

import com.fredapp.wbooks.parser.model.Block
import com.fredapp.wbooks.parser.model.Chapter
import com.fredapp.wbooks.parser.model.Document
import com.fredapp.wbooks.parser.model.Run
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentCodecTest {

    @Test
    fun roundTripsDocument() {
        val original = Document(
            title = "Title",
            author = "Author",
            chapters = listOf(
                Chapter(
                    title = "Chapter",
                    blocks = listOf(
                        Block.Heading(level = 1, text = "Chapter"),
                        Block.Paragraph(listOf(Run("Hello"))),
                        Block.Code(language = "kt", text = "println(\"Hello\")"),
                    ),
                ),
            ),
        )

        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { DocumentCodec.write(it, original) }
        }.toByteArray()

        val decoded = DataInputStream(ByteArrayInputStream(bytes)).use { DocumentCodec.read(it) }

        assertEquals(original, decoded)
    }

    @Test
    fun rejectsCorruptChapterCountBeforeAllocation() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeString("Title")
                data.writeByte(0)
                data.writeInt(Int.MAX_VALUE)
            }
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            DataInputStream(ByteArrayInputStream(bytes)).use { DocumentCodec.read(it) }
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}
