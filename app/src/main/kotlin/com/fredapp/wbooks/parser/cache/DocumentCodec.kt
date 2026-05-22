package com.fredapp.wbooks.parser.cache

import com.fredapp.wbooks.parser.model.Block
import com.fredapp.wbooks.parser.model.Chapter
import com.fredapp.wbooks.parser.model.Document
import com.fredapp.wbooks.parser.model.Run
import com.fredapp.wbooks.parser.model.RunStyle
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Binary codec for [Document]. Explicit layout, no Java serialization â€” keeps the
 * cache stable across Kotlin / AGP upgrades that would shift synthetic class shapes.
 *
 * Layout:
 *   str title                                  â€” UTF-8, i32 length-prefixed
 *   opt-str author                             â€” i8 present flag, then str if 1
 *   i32 nChapters
 *     for each chapter:
 *       opt-str chapter.title
 *       i32 nBlocks
 *         for each block:
 *           i8 kind
 *             0 Heading   : i32 level, str text
 *             1 Paragraph : i32 nRuns, [str text, i8 styleFlags, opt-i32 colorArgb]*
 *             2 Divider   : (no payload)
 *             3 Code      : opt-str language, str text
 *
 * Style flags (i8): bit 0 = bold, bit 1 = italic, bit 2 = underline.
 *
 * Schema bumps that need a [DocumentCache.SCHEMA_VERSION] bump:
 *  - Adding, removing, or reordering any field on Document / Chapter / Block / Run / RunStyle.
 *  - Renumbering the block-kind tags.
 *  - Changing string encoding.
 */
internal object DocumentCodec {

    fun write(out: DataOutputStream, doc: Document) {
        writeString(out, doc.title)
        writeOptString(out, doc.author)
        out.writeInt(doc.chapters.size)
        for (chapter in doc.chapters) writeChapter(out, chapter)
    }

    fun read(input: DataInputStream): Document {
        val title = readString(input)
        val author = readOptString(input)
        val nChapters = input.readCount("chapter", MAX_CHAPTERS)
        val chapters = ArrayList<Chapter>(nChapters)
        repeat(nChapters) { chapters += readChapter(input) }
        return Document(title = title, author = author, chapters = chapters)
    }

    private fun writeChapter(out: DataOutputStream, chapter: Chapter) {
        writeOptString(out, chapter.title)
        out.writeInt(chapter.blocks.size)
        for (block in chapter.blocks) writeBlock(out, block)
    }

    private fun readChapter(input: DataInputStream): Chapter {
        val title = readOptString(input)
        val nBlocks = input.readCount("block", MAX_BLOCKS_PER_CHAPTER)
        val blocks = ArrayList<Block>(nBlocks)
        repeat(nBlocks) { blocks += readBlock(input) }
        return Chapter(title = title, blocks = blocks)
    }

    private fun writeBlock(out: DataOutputStream, block: Block) {
        when (block) {
            is Block.Heading -> {
                out.writeByte(BLOCK_HEADING)
                out.writeInt(block.level)
                writeString(out, block.text)
            }
            is Block.Paragraph -> {
                out.writeByte(BLOCK_PARAGRAPH)
                out.writeInt(block.runs.size)
                for (run in block.runs) writeRun(out, run)
            }
            Block.Divider -> out.writeByte(BLOCK_DIVIDER)
            is Block.Code -> {
                out.writeByte(BLOCK_CODE)
                writeOptString(out, block.language)
                writeString(out, block.text)
            }
            is Block.Image -> {
                out.writeByte(BLOCK_IMAGE)
                out.writeInt(block.bytes.size)
                out.write(block.bytes)
                writeOptString(out, block.mime)
                writeString(out, block.alt)
            }
        }
    }

    private fun readBlock(input: DataInputStream): Block {
        return when (val kind = input.readByte().toInt()) {
            BLOCK_HEADING -> Block.Heading(level = input.readInt(), text = readString(input))
            BLOCK_PARAGRAPH -> {
                val nRuns = input.readCount("run", MAX_RUNS_PER_PARAGRAPH)
                val runs = ArrayList<Run>(nRuns)
                repeat(nRuns) { runs += readRun(input) }
                Block.Paragraph(runs)
            }
            BLOCK_DIVIDER -> Block.Divider
            BLOCK_CODE -> Block.Code(language = readOptString(input), text = readString(input))
            BLOCK_IMAGE -> {
                val n = input.readInt()
                require(n in 0..MAX_IMAGE_BYTES) {
                    "DocumentCodec: image length $n out of valid range 0..$MAX_IMAGE_BYTES - cache file corrupt?"
                }
                val bytes = ByteArray(n)
                input.readFully(bytes)
                val mime = readOptString(input)
                val alt = readString(input)
                Block.Image(bytes = bytes, mime = mime, alt = alt)
            }
            else -> error("DocumentCodec: unknown block kind $kind")
        }
    }

    private fun writeRun(out: DataOutputStream, run: Run) {
        writeString(out, run.text)
        val flags = (if (run.style.bold) FLAG_BOLD else 0) or
            (if (run.style.italic) FLAG_ITALIC else 0) or
            (if (run.style.underline) FLAG_UNDERLINE else 0)
        out.writeByte(flags)
        val color = run.style.color
        if (color == null) {
            out.writeByte(0)
        } else {
            out.writeByte(1)
            out.writeInt(color)
        }
    }

    private fun readRun(input: DataInputStream): Run {
        val text = readString(input)
        val flags = input.readByte().toInt() and 0xFF
        val color = if (input.readByte().toInt() == 0) null else input.readInt()
        return Run(
            text = text,
            style = RunStyle(
                bold = flags and FLAG_BOLD != 0,
                italic = flags and FLAG_ITALIC != 0,
                underline = flags and FLAG_UNDERLINE != 0,
                color = color,
            ),
        )
    }

    private fun writeString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val n = input.readInt()
        require(n in 0..MAX_STRING_BYTES) {
            "DocumentCodec: string length $n out of valid range 0..$MAX_STRING_BYTES â€” cache file corrupt?"
        }
        val bytes = ByteArray(n)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataInputStream.readCount(label: String, max: Int): Int {
        val n = readInt()
        require(n in 0..max) {
            "DocumentCodec: $label count $n out of valid range 0..$max - cache file corrupt?"
        }
        return n
    }

    private fun writeOptString(out: DataOutputStream, s: String?) {
        if (s == null) {
            out.writeByte(0)
        } else {
            out.writeByte(1)
            writeString(out, s)
        }
    }

    private fun readOptString(input: DataInputStream): String? =
        if (input.readByte().toInt() == 0) null else readString(input)

    // Block kind tags. Changing any of these is a schema break.
    private const val BLOCK_HEADING = 0
    private const val BLOCK_PARAGRAPH = 1
    private const val BLOCK_DIVIDER = 2
    private const val BLOCK_CODE = 3
    private const val BLOCK_IMAGE = 4

    private const val FLAG_BOLD = 1
    private const val FLAG_ITALIC = 2
    private const val FLAG_UNDERLINE = 4

    private const val MAX_CHAPTERS = 10_000
    private const val MAX_BLOCKS_PER_CHAPTER = 500_000
    private const val MAX_RUNS_PER_PARAGRAPH = 10_000

    /** Guard against OOM on corrupt cache files. 8 MB is well above any realistic string. */
    private const val MAX_STRING_BYTES = 8 * 1024 * 1024

    /** Per-image cap. 24 MB is comfortably above a 4000x3000 PNG; anything more
     *  is almost certainly corrupt cache or a pathological book. */
    private const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
}
