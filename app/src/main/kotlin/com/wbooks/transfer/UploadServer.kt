package com.wbooks.transfer

import com.wbooks.data.book.BookFormat
import fi.iki.elonen.NanoHTTPD
import java.io.File

/**
 * Tiny HTTP server bound to the watch's Wi-Fi address. Backs the "File transfer"
 * setting: the user toggles it from Settings, opens the displayed URL in a browser,
 * enters the displayed PIN, and uploads / deletes / sorts books.
 *
 * Security model: unauthenticated on the local network. Every mutating endpoint
 * requires the right PIN as a form field. The PIN is regenerated each time the
 * server starts so a captured screenshot of a previous PIN is useless.
 *
 * Books live under [booksDir]. Subdirectories are treated as folders for sorting;
 * arbitrary nesting is allowed but the UI flattens it for now.
 */
class UploadServer(
    port: Int,
    private val booksDir: File,
    private val pin: String,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = try {
        when (session.uri.trimEnd('/').ifEmpty { "/" }) {
            "/" -> when (session.method) {
                Method.GET -> renderIndex()
                else -> methodNotAllowed()
            }
            "/upload" -> handleUpload(session)
            "/delete" -> handleDelete(session)
            "/mkdir" -> handleMkdir(session)
            "/move" -> handleMove(session)
            else -> notFound()
        }
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server error: ${e.message}")
    }

    // --- handlers ---

    private fun renderIndex(): Response {
        val rows = StringBuilder()
        val entries = booksDir.walkTopDown()
            .filter { it != booksDir }
            .sortedWith(compareBy({ !it.isDirectory }, { it.relativeTo(booksDir).invariantSeparatorsPath.lowercase() }))
            .toList()
        for (f in entries) {
            val rel = f.relativeTo(booksDir).invariantSeparatorsPath
            if (f.isDirectory) {
                rows.append(
                    """<tr><td>&#x1F4C1; $rel/</td><td>folder</td>
                       <td><form method="post" action="/delete" onsubmit="return confirm('Delete folder $rel?')">
                           <input type="hidden" name="path" value="$rel">
                           <input type="hidden" name="pin" value="">
                           <button onclick="this.previousElementSibling.value=document.getElementById('pin').value">delete</button>
                       </form></td></tr>"""
                )
            } else if (BookFormat.fromExtension(f.extension) != null) {
                val size = humanBytes(f.length())
                rows.append(
                    """<tr><td>$rel</td><td>$size</td>
                       <td><form method="post" action="/delete" onsubmit="return confirm('Delete $rel?')">
                           <input type="hidden" name="path" value="$rel">
                           <input type="hidden" name="pin" value="">
                           <button onclick="this.previousElementSibling.value=document.getElementById('pin').value">delete</button>
                       </form></td></tr>"""
                )
            }
        }
        val html = """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>wBooks transfer</title>
            <style>
              body{font-family:system-ui,sans-serif;max-width:640px;margin:24px auto;padding:0 12px;}
              table{width:100%;border-collapse:collapse;margin-top:12px}
              td{padding:6px 4px;border-bottom:1px solid #eee;vertical-align:middle}
              form.inline{display:inline}
              .row{margin:12px 0}
              input[type=text],input[type=password]{padding:6px}
              button{padding:6px 12px}
              .note{color:#666;font-size:0.85em}
            </style></head><body>
            <h1>wBooks transfer</h1>
            <div class="row">
              <label>PIN: <input id="pin" type="password" autocomplete="off" inputmode="numeric"></label>
              <span class="note">Shown on the watch.</span>
            </div>
            <form method="post" action="/upload" enctype="multipart/form-data" class="row"
                  onsubmit="this.querySelector('[name=pin]').value=document.getElementById('pin').value">
              <input type="hidden" name="pin" value="">
              <label>Folder (optional): <input type="text" name="folder" placeholder="e.g. fiction"></label>
              <input type="file" name="file" multiple accept=".epub,.txt,.fb2,.html,.htm,.xhtml">
              <button>Upload</button>
            </form>
            <form method="post" action="/mkdir" class="row"
                  onsubmit="this.querySelector('[name=pin]').value=document.getElementById('pin').value">
              <input type="hidden" name="pin" value="">
              <label>New folder: <input type="text" name="name" required></label>
              <button>Create</button>
            </form>
            <table>$rows</table>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun handleUpload(session: IHTTPSession): Response {
        require(session.method == Method.POST) { return methodNotAllowed() }
        // NanoHTTPD writes uploaded files to temp paths and populates this map keyed by the form field.
        val tempFiles = HashMap<String, String>()
        session.parseBody(tempFiles)
        val params = session.parameters
        gatePin(params)?.let { return it }

        val folder = params["folder"]?.firstOrNull().orEmpty().trim().trim('/', '\\')
        val targetDir = if (folder.isEmpty()) booksDir else File(booksDir, folder).apply { mkdirs() }
        if (!targetDir.canonicalPath.startsWith(booksDir.canonicalPath)) {
            return forbidden("folder escapes books dir")
        }

        var written = 0
        for ((field, tempPath) in tempFiles) {
            val originalName = params[field]?.firstOrNull() ?: continue
            if (originalName.isBlank()) continue
            val ext = originalName.substringAfterLast('.', "")
            if (BookFormat.fromExtension(ext) == null) continue
            val safeName = originalName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val dest = uniqueFile(targetDir, safeName)
            File(tempPath).copyTo(dest, overwrite = false)
            written++
        }
        return redirectToIndex(if (written > 0) "Uploaded $written file(s)" else "No supported files")
    }

    private fun handleDelete(session: IHTTPSession): Response {
        require(session.method == Method.POST) { return methodNotAllowed() }
        val params = parsedForm(session)
        gatePin(params)?.let { return it }
        val path = params["path"]?.firstOrNull().orEmpty()
        val target = File(booksDir, path)
        if (!target.canonicalPath.startsWith(booksDir.canonicalPath) || !target.exists()) {
            return notFound()
        }
        if (target.isDirectory) target.deleteRecursively() else target.delete()
        return redirectToIndex("Deleted $path")
    }

    private fun handleMkdir(session: IHTTPSession): Response {
        require(session.method == Method.POST) { return methodNotAllowed() }
        val params = parsedForm(session)
        gatePin(params)?.let { return it }
        val name = params["name"]?.firstOrNull().orEmpty().trim().trim('/', '\\')
        if (name.isEmpty()) return badRequest("folder name required")
        val target = File(booksDir, name)
        if (!target.canonicalPath.startsWith(booksDir.canonicalPath)) {
            return forbidden("name escapes books dir")
        }
        target.mkdirs()
        return redirectToIndex("Created $name")
    }

    private fun handleMove(session: IHTTPSession): Response {
        require(session.method == Method.POST) { return methodNotAllowed() }
        val params = parsedForm(session)
        gatePin(params)?.let { return it }
        val from = File(booksDir, params["from"]?.firstOrNull().orEmpty())
        val toDir = File(booksDir, params["to"]?.firstOrNull().orEmpty().trim('/', '\\'))
        if (!from.canonicalPath.startsWith(booksDir.canonicalPath) ||
            !toDir.canonicalPath.startsWith(booksDir.canonicalPath) ||
            !from.exists()) {
            return notFound()
        }
        toDir.mkdirs()
        val dest = uniqueFile(toDir, from.name)
        from.renameTo(dest)
        return redirectToIndex("Moved")
    }

    // --- helpers ---

    private fun parsedForm(session: IHTTPSession): Map<String, List<String>> {
        session.parseBody(HashMap())
        return session.parameters
    }

    /** Returns null when the PIN matches, or a 403 Response when it doesn't. */
    private fun gatePin(params: Map<String, List<String>>): Response? {
        val supplied = params["pin"]?.firstOrNull()
        return if (supplied == pin) null else forbidden("bad PIN")
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 2
        while (true) {
            candidate = File(dir, "$base ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun redirectToIndex(@Suppress("UNUSED_PARAMETER") flash: String): Response {
        // 303 See Other so the browser issues a fresh GET / instead of resubmitting the form.
        val resp = newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "")
        resp.addHeader("Location", "/")
        return resp
    }

    private fun forbidden(msg: String) =
        newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", msg)

    private fun notFound() =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")

    private fun badRequest(msg: String) =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", msg)

    private fun methodNotAllowed() =
        newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed")

    private fun humanBytes(n: Long): String = when {
        n >= 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024.0)
        n >= 1024 -> "%.0f KB".format(n / 1024.0)
        else -> "$n B"
    }
}

/** Generate a fresh 4-digit PIN as a string with leading zeros. */
internal fun generatePin(): String =
    (java.security.SecureRandom().nextInt(10_000)).toString().padStart(4, '0')
