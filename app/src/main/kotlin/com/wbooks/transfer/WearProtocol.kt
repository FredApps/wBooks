package com.wbooks.transfer

/**
 * Paths exchanged between the watch ([BookReceiverService]) and the phone
 * companion. Kept simple: list/delete go over [com.google.android.gms.wearable.MessageClient]
 * with JSON payloads; upload goes over [com.google.android.gms.wearable.ChannelClient]
 * with the filename URL-encoded into the channel path.
 *
 * **Mirrored in `companion/src/main/kotlin/com/wbooks/companion/WearProtocol.kt`** —
 * there's no shared module; the constants are few enough that mirroring them is
 * cheaper than introducing one. When adding or renaming a path, update both files.
 */
internal object WearProtocol {
    /** Phone -> watch (empty payload). Watch replies on the same path with [LibraryListJson]. */
    const val PATH_LIST = "/wbooks/library/list"

    /** Phone -> watch. Payload: JSON `{"id":"<bookId>"}`. Reply on the same path with [LibraryListJson]. */
    const val PATH_DELETE = "/wbooks/library/delete"

    /** ChannelClient path prefix. Real path: `/wbooks/upload/<urlencoded-filename>`. */
    const val PATH_UPLOAD_PREFIX = "/wbooks/upload/"

    /** Phone -> watch (empty payload). Watch replies with [StatsJson]. */
    const val PATH_STATS = "/wbooks/stats"
}

/**
 * Plain JSON shapes traded over the protocol. Hand-encoded as strings rather than
 * dragging in a JSON library — the schema is small and stable.
 *
 * Encoding invariant (relied on by `companion`'s `LibraryListJson.decode`): we never
 * emit `\uXXXX` escapes. Non-ASCII characters are written as raw UTF-8 bytes; only
 * control chars below 0x20 are escaped as `\uXXXX`. The decoder doesn't handle
 * `\uXXXX`, so if you change the encoder to emit them, update the decoder too.
 */
internal object LibraryListJson {
    /** Build `{"books":[{"id":"...","title":"...","format":"EPUB"},...]}`. */
    fun encode(books: List<BookSummary>): String {
        val sb = StringBuilder("""{"books":[""")
        books.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            sb.append("""{"id":""").append(jsonString(b.id))
              .append(""","title":""").append(jsonString(b.title))
              .append(""","format":""").append(jsonString(b.format))
              .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }
}

internal data class BookSummary(val id: String, val title: String, val format: String)

/**
 * Encoder for [com.wbooks.data.stats.ReadingStatsRepository.Summary]. Same
 * "no \uXXXX escapes" invariant as [LibraryListJson]; the phone-side decoder
 * is in `companion`'s WearProtocol.kt.
 */
internal object StatsJson {
    fun encode(s: com.wbooks.data.stats.ReadingStatsRepository.Summary): String {
        val sb = StringBuilder()
        sb.append("""{"totalMs":""").append(s.totalMs)
        sb.append(""","todayMs":""").append(s.todayMs)
        sb.append(""","booksFinished":""").append(s.booksFinished)
        sb.append(""","daily":[""")
        s.recentDaily.forEachIndexed { i, d ->
            if (i > 0) sb.append(',')
            sb.append("""{"date":""").append(quote(d.date.toString()))
            sb.append(""","ms":""").append(d.ms).append('}')
        }
        sb.append("""],"wpm":[""")
        s.recentWpm.forEachIndexed { i, w ->
            if (i > 0) sb.append(',')
            sb.append("""{"ts":""").append(w.timestampMs)
            sb.append(""","wpm":""").append(w.wpm).append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }
}

private fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) when (c) {
        '\\' -> sb.append("\\\\")
        '"' -> sb.append("\\\"")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
    }
    sb.append('"')
    return sb.toString()
}
