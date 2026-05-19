package com.wbooks.companion

/**
 * Mirror of `app/src/main/kotlin/com/wbooks/transfer/WearProtocol.kt`. There's no
 * shared module; the constants are few enough that mirroring is cheaper than
 * extracting one. When adding or renaming a path, update both files.
 */
object WearProtocol {
    const val PATH_LIST = "/wbooks/library/list"
    const val PATH_DELETE = "/wbooks/library/delete"
    const val PATH_UPLOAD_PREFIX = "/wbooks/upload/"
    const val PATH_STATS = "/wbooks/stats"
}

data class BookSummary(val id: String, val title: String, val format: String)

data class StatsSummary(
    val totalMs: Long,
    val todayMs: Long,
    val booksFinished: Int,
    val daily: List<DailyEntry>,
    val wpm: List<WpmSample>,
) {
    data class DailyEntry(val date: String, val ms: Long)
    data class WpmSample(val timestampMs: Long, val wpm: Int)
}

/**
 * Decoder for the JSON shape `:app`'s `StatsJson.encode` produces. Same hand-
 * rolled approach as [LibraryListJson]; encoder never emits `\uXXXX` so we
 * don't parse it here either.
 *
 * Each `readLong` / `readStr` re-scans the input from the beginning — O(N·M)
 * over (input size · keys). Stays cheap while the payload is small (a 30-day
 * stats blob is well under 10 KB). If the schema grows, swap for a streaming
 * parser before this becomes a bottleneck.
 */
object StatsJson {
    fun decode(bytes: ByteArray): StatsSummary {
        val json = String(bytes, Charsets.UTF_8)
        return StatsSummary(
            totalMs = readLong(json, "totalMs"),
            todayMs = readLong(json, "todayMs"),
            booksFinished = readLong(json, "booksFinished").toInt(),
            daily = readArray(json, "daily") { obj ->
                StatsSummary.DailyEntry(
                    date = readStr(obj, "date"),
                    ms = readLong(obj, "ms"),
                )
            },
            wpm = readArray(json, "wpm") { obj ->
                StatsSummary.WpmSample(
                    timestampMs = readLong(obj, "ts"),
                    wpm = readLong(obj, "wpm").toInt(),
                )
            },
        )
    }

    private fun readLong(s: String, key: String): Long {
        val needle = "\"$key\""
        val k = s.indexOf(needle)
        if (k < 0) return 0
        val colon = s.indexOf(':', k + needle.length)
        if (colon < 0) return 0
        var i = colon + 1
        while (i < s.length && s[i].isWhitespace()) i++
        val sb = StringBuilder()
        if (i < s.length && (s[i] == '-' || s[i].isDigit())) {
            sb.append(s[i]); i++
            while (i < s.length && s[i].isDigit()) { sb.append(s[i]); i++ }
        }
        return sb.toString().toLongOrNull() ?: 0
    }

    private fun readStr(s: String, key: String): String {
        val needle = "\"$key\""
        val k = s.indexOf(needle)
        if (k < 0) return ""
        val q1 = s.indexOf('"', s.indexOf(':', k + needle.length) + 1)
        if (q1 < 0) return ""
        val sb = StringBuilder()
        var i = q1 + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '"') return sb.toString()
            if (c == '\\' && i + 1 < s.length) {
                when (val esc = s[i + 1]) { '"', '\\' -> sb.append(esc); else -> sb.append(esc) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private inline fun <T> readArray(s: String, key: String, parseObj: (String) -> T): List<T> {
        val needle = "\"$key\""
        val k = s.indexOf(needle)
        if (k < 0) return emptyList()
        val open = s.indexOf('[', k + needle.length)
        if (open < 0) return emptyList()
        val close = matchBracket(s, open)
        if (close < 0) return emptyList()
        val body = s.substring(open + 1, close).trim()
        if (body.isEmpty()) return emptyList()
        val out = mutableListOf<T>()
        var i = 0
        while (i < body.length) {
            val objStart = body.indexOf('{', i)
            if (objStart < 0) break
            val objEnd = matchBracket(body, objStart, '{', '}')
            if (objEnd < 0) break
            out += parseObj(body.substring(objStart, objEnd + 1))
            i = objEnd + 1
        }
        return out
    }

    private fun matchBracket(s: String, openIdx: Int, open: Char = '[', close: Char = ']'): Int {
        var depth = 0
        var i = openIdx
        while (i < s.length) {
            when (s[i]) {
                open -> depth++
                close -> { depth--; if (depth == 0) return i }
                '"' -> i = skipString(s, i)
            }
            i++
        }
        return -1
    }

    private fun skipString(s: String, quoteIdx: Int): Int {
        var i = quoteIdx + 1
        while (i < s.length) {
            when (s[i]) { '\\' -> i++; '"' -> return i }
            i++
        }
        return i
    }
}

/**
 * Parser counterpart to `:app`'s `LibraryListJson.encode`. Minimal hand-rolled JSON
 * reader — handles only the shape the watch encoder emits.
 *
 * Decoding invariant (matches the encoder): we do **not** parse `\uXXXX` escapes,
 * because the encoder never emits them — it writes non-ASCII as raw UTF-8 and only
 * escapes control chars below 0x20. If you change the encoder, update this too.
 */
object LibraryListJson {
    fun decode(bytes: ByteArray): List<BookSummary> {
        val json = String(bytes, Charsets.UTF_8)
        val arrStart = json.indexOf('[')
        val arrEnd = json.lastIndexOf(']')
        if (arrStart < 0 || arrEnd <= arrStart) return emptyList()
        val body = json.substring(arrStart + 1, arrEnd).trim()
        if (body.isEmpty()) return emptyList()
        val out = mutableListOf<BookSummary>()
        var i = 0
        while (i < body.length) {
            val objStart = body.indexOf('{', i)
            if (objStart < 0) break
            val objEnd = findMatchingBrace(body, objStart)
            if (objEnd < 0) break
            val obj = body.substring(objStart, objEnd + 1)
            out += BookSummary(
                id = readString(obj, "id"),
                title = readString(obj, "title"),
                format = readString(obj, "format"),
            )
            i = objEnd + 1
        }
        return out
    }

    private fun findMatchingBrace(s: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
                '"' -> i = skipString(s, i)
            }
            i++
        }
        return -1
    }

    private fun skipString(s: String, quoteIdx: Int): Int {
        var i = quoteIdx + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '"' -> return i
            }
            i++
        }
        return i
    }

    private fun readString(obj: String, key: String): String {
        val needle = "\"$key\""
        val k = obj.indexOf(needle)
        if (k < 0) return ""
        val colon = obj.indexOf(':', k + needle.length)
        if (colon < 0) return ""
        val q1 = obj.indexOf('"', colon + 1)
        if (q1 < 0) return ""
        val sb = StringBuilder()
        var i = q1 + 1
        while (i < obj.length) {
            val c = obj[i]
            if (c == '"') return sb.toString()
            if (c == '\\' && i + 1 < obj.length) {
                when (val esc = obj[i + 1]) {
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
