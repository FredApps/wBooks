package com.wbooks.companion

/**
 * Mirror of `:app`'s `WearProtocol`. Kept in sync by convention — there's no
 * shared module. Two MessageClient request/response paths and one ChannelClient
 * path-prefix; if the watch app grows a path, add it here too.
 */
object WearProtocol {
    const val PATH_LIST = "/wbooks/library/list"
    const val PATH_DELETE = "/wbooks/library/delete"
    const val PATH_UPLOAD_PREFIX = "/wbooks/upload/"
}

data class BookSummary(val id: String, val title: String, val format: String)

/** Parser counterpart to `:app`'s `LibraryListJson.encode`. Minimal hand-rolled JSON reader. */
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
