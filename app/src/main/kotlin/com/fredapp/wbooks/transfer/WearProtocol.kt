package com.fredapp.wbooks.transfer

/**
 * Paths exchanged between the watch ([BookReceiverService]) and the phone
 * companion. Kept simple: list/delete go over [com.google.android.gms.wearable.MessageClient]
 * with JSON payloads; upload goes over [com.google.android.gms.wearable.ChannelClient]
 * with the filename URL-encoded into the channel path.
 *
 * **Mirrored in `companion/src/main/kotlin/com/fredapp/wbooksutil/WearProtocol.kt`** -
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

    /** Phone -> watch (empty payload). Watch replies with [SettingsJson] full snapshot. */
    const val PATH_SETTINGS_GET = "/wbooks/settings/get"

    /**
     * Phone -> watch. Payload: JSON `{"key":"<name>","value":"<asString>"}`. The
     * value is always a string on the wire; the watch parses it based on the
     * known type of the named key. Watch replies on the same path with the new
     * full [SettingsJson] snapshot, so the phone can update its UI in one round-trip.
     */
    const val PATH_SETTINGS_SET = "/wbooks/settings/set"

    /** Phone -> watch. Payload: JSON `{"name":"<folderName>"}`. Reply: [LibraryListJson]. */
    const val PATH_MKDIR = "/wbooks/library/mkdir"

    /**
     * Phone -> watch. Payload: JSON `{"id":"<bookId>","folder":"<targetFolder>"}`.
     * `folder` is the destination directory name, or `""` for the root. Reply: [LibraryListJson].
     */
    const val PATH_MOVE = "/wbooks/library/move"
}

/**
 * Plain JSON shapes traded over the protocol. Hand-encoded as strings rather than
 * dragging in a JSON library â€" the schema is small and stable.
 *
 * Encoding invariant (relied on by `companion`'s `LibraryListJson.decode`): we never
 * emit `\uXXXX` escapes. Non-ASCII characters are written as raw UTF-8 bytes; only
 * control chars below 0x20 are escaped as `\uXXXX`. The decoder doesn't handle
 * `\uXXXX`, so if you change the encoder to emit them, update the decoder too.
 */
internal object LibraryListJson {
    /** Build `{"books":[{"id":"...","title":"...","format":"EPUB"},...],"folders":["..."]}`. */
    fun encode(books: List<BookSummary>, folders: List<String> = emptyList()): String {
        val sb = StringBuilder("""{"books":[""")
        books.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            sb.append("""{"id":""").append(jsonString(b.id))
              .append(""","title":""").append(jsonString(b.title))
              .append(""","format":""").append(jsonString(b.format))
              .append('}')
        }
        sb.append("""],"folders":[""")
        folders.forEachIndexed { i, folder ->
            if (i > 0) sb.append(',')
            sb.append(jsonString(folder))
        }
        sb.append("]}")
        return sb.toString()
    }
}

internal data class BookSummary(val id: String, val title: String, val format: String)

/**
 * Encoder for [com.fredapp.wbooks.data.stats.ReadingStatsRepository.Summary]. Same
 * "no \uXXXX escapes" invariant as [LibraryListJson]; the phone-side decoder
 * is in `companion`'s WearProtocol.kt.
 */
internal object StatsJson {
    fun encode(s: com.fredapp.wbooks.data.stats.ReadingStatsRepository.Summary): String {
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

/**
 * Wire shape for the full settings snapshot sent to the phone. Mirrors the
 * persisted [com.fredapp.wbooks.data.settings.ReaderSettings] plus the cross-app
 * crash-reporting opt-out. Enum-valued fields use their `.name` string so the
 * companion can defer decoding to its own duplicated enum types.
 */
internal data class SettingsSnapshot(
    val mode: String,
    val font: String,
    val textSizeSp: Int,
    val sentenceTextSizeSp: Int,
    val textColorArgb: Int,
    val autoscrollEnabled: Boolean,
    val autoscrollSpeed: Int,
    val screenBrightness: Int,
    val speedreadWpm: Int,
    val theme: String,
    val crashReportingEnabled: Boolean,
)

/**
 * Encoder for the full settings snapshot and parser for the
 * `{"key":..., "value":...}` partial-update requests the phone sends. Same
 * "no \uXXXX escapes" invariant as [LibraryListJson] â€" values that travel here
 * are enum names + numbers + booleans, never user text, so the constraint is
 * trivial to maintain.
 */
internal object SettingsJson {
    fun encode(s: SettingsSnapshot): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append(""""mode":""").append(jsonString(s.mode))
        sb.append(""","font":""").append(jsonString(s.font))
        sb.append(""","textSizeSp":""").append(s.textSizeSp)
        sb.append(""","sentenceTextSizeSp":""").append(s.sentenceTextSizeSp)
        sb.append(""","textColorArgb":""").append(s.textColorArgb)
        sb.append(""","autoscrollEnabled":""").append(s.autoscrollEnabled)
        sb.append(""","autoscrollSpeed":""").append(s.autoscrollSpeed)
        sb.append(""","screenBrightness":""").append(s.screenBrightness)
        sb.append(""","speedreadWpm":""").append(s.speedreadWpm)
        sb.append(""","theme":""").append(jsonString(s.theme))
        sb.append(""","crashReportingEnabled":""").append(s.crashReportingEnabled)
        sb.append('}')
        return sb.toString()
    }

    /** Returns (key, valueAsString) or null if the payload was malformed. */
    fun decodeSetRequest(bytes: ByteArray): Pair<String, String>? {
        val json = String(bytes, Charsets.UTF_8)
        val key = readString(json, "key").ifEmpty { return null }
        val value = readString(json, "value")
        return key to value
    }

    private fun readString(json: String, key: String): String {
        val needle = "\"$key\""
        val k = json.indexOf(needle)
        if (k < 0) return ""
        val colon = json.indexOf(':', k + needle.length)
        if (colon < 0) return ""
        val q1 = json.indexOf('"', colon + 1)
        if (q1 < 0) return ""
        val sb = StringBuilder()
        var i = q1 + 1
        while (i < json.length) {
            val c = json[i]
            if (c == '"') return sb.toString()
            if (c == '\\' && i + 1 < json.length) {
                when (val esc = json[i + 1]) {
                    '"', '\\', '/' -> sb.append(esc)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(esc)
                }
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
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
