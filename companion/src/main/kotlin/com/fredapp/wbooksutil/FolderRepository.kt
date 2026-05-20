package com.fredapp.wbooksutil

import android.content.Context
import java.util.UUID

data class Folder(val id: String, val name: String)

class FolderRepository(context: Context) {
    private val prefs = context.getSharedPreferences("wbooks_folders", Context.MODE_PRIVATE)

    fun getFolders(): List<Folder> = parseFolders(prefs.getString("folders", "[]") ?: "[]")

    fun createFolder(name: String): Folder {
        val folder = Folder(UUID.randomUUID().toString(), name.trim())
        val updated = getFolders() + folder
        prefs.edit().putString("folders", encodeFolders(updated)).apply()
        return folder
    }

    fun deleteFolder(id: String) {
        val folders = getFolders().filter { it.id != id }
        val assignments = getAssignments().filter { it.value != id }
        prefs.edit()
            .putString("folders", encodeFolders(folders))
            .putString("assignments", encodeAssignments(assignments))
            .apply()
    }

    fun getAssignments(): Map<String, String> =
        parseAssignments(prefs.getString("assignments", "{}") ?: "{}")

    fun assignBook(bookId: String, folderId: String?) {
        val updated = getAssignments().toMutableMap()
        if (folderId == null) updated.remove(bookId) else updated[bookId] = folderId
        prefs.edit().putString("assignments", encodeAssignments(updated)).apply()
    }

    fun cleanupAssignments(validBookIds: Set<String>) {
        val cleaned = getAssignments().filter { it.key in validBookIds }
        prefs.edit().putString("assignments", encodeAssignments(cleaned)).apply()
    }

    // --- Minimal hand-rolled JSON (matches style of WearProtocol.kt) ---

    private fun parseFolders(json: String): List<Folder> {
        val a = json.indexOf('['); val b = json.lastIndexOf(']')
        if (a < 0 || b <= a) return emptyList()
        val body = json.substring(a + 1, b).trim()
        if (body.isEmpty()) return emptyList()
        val out = mutableListOf<Folder>()
        var i = 0
        while (i < body.length) {
            val s = body.indexOf('{', i); if (s < 0) break
            val e = matchBrace(body, s); if (e < 0) break
            val obj = body.substring(s, e + 1)
            out += Folder(id = readStr(obj, "id"), name = readStr(obj, "name"))
            i = e + 1
        }
        return out
    }

    private fun encodeFolders(folders: List<Folder>): String {
        val sb = StringBuilder("[")
        folders.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(jsonStr(f.id))
                .append(",\"name\":").append(jsonStr(f.name)).append('}')
        }
        return sb.append(']').toString()
    }

    private fun parseAssignments(json: String): Map<String, String> {
        val s = json.indexOf('{'); val e = json.lastIndexOf('}')
        if (s < 0 || e <= s) return emptyMap()
        val body = json.substring(s + 1, e).trim()
        if (body.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < body.length) {
            val q1 = body.indexOf('"', i); if (q1 < 0) break
            val q2 = body.indexOf('"', q1 + 1); if (q2 < 0) break
            val key = body.substring(q1 + 1, q2)
            val colon = body.indexOf(':', q2 + 1); if (colon < 0) break
            val vq1 = body.indexOf('"', colon + 1); if (vq1 < 0) break
            val vq2 = body.indexOf('"', vq1 + 1); if (vq2 < 0) break
            out[key] = body.substring(vq1 + 1, vq2)
            i = vq2 + 1
        }
        return out
    }

    private fun encodeAssignments(map: Map<String, String>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            sb.append(jsonStr(k)).append(':').append(jsonStr(v))
            first = false
        }
        return sb.append('}').toString()
    }

    private fun readStr(obj: String, key: String): String {
        val needle = "\"$key\""
        val k = obj.indexOf(needle); if (k < 0) return ""
        val q1 = obj.indexOf('"', obj.indexOf(':', k + needle.length) + 1); if (q1 < 0) return ""
        val sb = StringBuilder(); var i = q1 + 1
        while (i < obj.length) {
            val c = obj[i]
            if (c == '"') return sb.toString()
            if (c == '\\' && i + 1 < obj.length) { sb.append(obj[i + 1]); i += 2 } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun matchBrace(s: String, openIdx: Int): Int {
        var depth = 0; var i = openIdx
        while (i < s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
                '"' -> { i++; while (i < s.length && s[i] != '"') { if (s[i] == '\\') i++; i++ } }
            }
            i++
        }
        return -1
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder().append('"')
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
