package com.fredapp.wbooks.parser

import com.fredapp.wbooks.parser.model.Block
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EpubParserTest {

    @Test
    fun resolvesEncodedImageHrefWithQueryAndFragment() {
        val imageBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
        )
        val epub = File.createTempFile("wbooks-image-href-", ".epub")
        try {
            ZipOutputStream(epub.outputStream().buffered()).use { zip ->
                zip.putText(
                    "META-INF/container.xml",
                    """
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """.trimIndent(),
                )
                zip.putText(
                    "OEBPS/content.opf",
                    """
                    <package xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <metadata><dc:title>Images</dc:title></metadata>
                      <manifest>
                        <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                        <item id="cover" href="Images/cover image.png" media-type="image/png"/>
                      </manifest>
                      <spine><itemref idref="chapter"/></spine>
                    </package>
                    """.trimIndent(),
                )
                zip.putText(
                    "OEBPS/chapter.xhtml",
                    """
                    <html><body>
                      <p><img src="Images/cover%20image.png?cache=1#frag" alt="cover"/></p>
                    </body></html>
                    """.trimIndent(),
                )
                zip.putBytes("OEBPS/Images/cover image.png", imageBytes)
            }

            val doc = EpubParser().parse(epub)
            val image = doc.chapters.single().blocks.singleOrNull() as? Block.Image

            assertNotNull(image)
            assertEquals("image/png", image!!.mime)
            assertEquals("cover", image.alt)
            assertArrayEquals(imageBytes, image.bytes)
        } finally {
            epub.delete()
        }
    }

    private fun ZipOutputStream.putText(name: String, value: String) {
        putBytes(name, value.toByteArray(Charsets.UTF_8))
    }

    private fun ZipOutputStream.putBytes(name: String, value: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(value)
        closeEntry()
    }
}
