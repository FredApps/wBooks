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
}

data class BookSummary(val id: String, val title: String, val format: String)

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
