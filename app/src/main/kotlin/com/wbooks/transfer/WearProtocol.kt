package com.wbooks.transfer

/**
 * Paths exchanged between the watch ([BookReceiverService]) and the phone
 * companion. Kept simple: list/delete go over [com.google.android.gms.wearable.MessageClient]
 * with JSON payloads; upload goes over [com.google.android.gms.wearable.ChannelClient]
 * with the filename URL-encoded into the channel path.
 *
 * Duplicated as-is in `:companion`'s `WearProtocol.kt` — there's no shared module
 * and the constants are few enough that mirroring them is cheaper than introducing
 * one. Keep both files in sync when adding paths.
 */
internal object WearProtocol {
    /** Phone -> watch (empty payload). Watch replies on the same path with [LibraryListJson]. */
    const val PATH_LIST = "/wbooks/library/list"

    /** Phone -> watch. Payload: JSON `{"id":"<bookId>"}`. Reply on the same path with [LibraryListJson]. */
    const val PATH_DELETE = "/wbooks/library/delete"

    /** ChannelClient path prefix. Real path: `/wbooks/upload/<urlencoded-filename>`. */
    const val PATH_UPLOAD_PREFIX = "/wbooks/upload/"
}

/**
 * Plain JSON shapes traded over the protocol. Hand-encoded as strings rather than
 * dragging in a JSON library — the schema is small and stable.
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
