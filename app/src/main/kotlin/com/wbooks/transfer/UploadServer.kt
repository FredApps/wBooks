package com.wbooks.transfer

import com.wbooks.data.book.BookFormat
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Tiny HTTP server bound to the watch's Wi-Fi address. Backs the web interface
 * setting: the user toggles it from Settings, opens the displayed URL in a browser,
 * enters the displayed PIN, and uploads / deletes / sorts books.
 *
 * Security model: unauthenticated on the local network. Every mutating endpoint
 * requires the right PIN, supplied as the `pin` query-string parameter so it can
 * be checked *before* NanoHTTPD spools the request body (multipart uploads in
 * particular). The PIN is regenerated each time the server starts so a captured
 * screenshot of a previous PIN is useless. A sliding-window counter trips the
 * endpoint after [MAX_PIN_FAILURES] wrong attempts inside [PIN_WINDOW_MS] to
 * make a 10k-combo brute force impractical.
 *
 * Books live under [booksDir]. Subdirectories are treated as folders for sorting;
 * arbitrary nesting is allowed but the UI flattens it for now.
 */
class UploadServer(
    port: Int,
    private val booksDir: File,
    private val pin: String,
) : NanoHTTPD(port) {

    private val pinBytes = pin.toByteArray(Charsets.UTF_8)
    private val failedAttempts = ArrayDeque<Long>()

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
        io.sentry.Sentry.captureException(e)
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
            val relEsc = htmlEscape(rel)
            // The `?pin=` query string is appended by the page's JS on submit so the
            // server can gate before parseBody runs (multipart spool-to-disk would
            // otherwise happen pre-auth). Confirm messages live in data-confirm so the
            // filename never has to be escaped for a JS string context.
            if (f.isDirectory) {
                rows.append(
                    """<tr><td>&#x1F4C1; $relEsc/</td><td>folder</td>
                       <td><form method="post" action="/delete"
                                 data-confirm="Delete folder $relEsc?"
                                 onsubmit="return confirmAndAttachPin(this)">
                           <input type="hidden" name="path" value="$relEsc">
                           <button>delete</button>
                       </form></td></tr>"""
                )
            } else if (BookFormat.fromExtension(f.extension) != null) {
                val size = htmlEscape(humanBytes(f.length()))
                rows.append(
                    """<tr><td>$relEsc</td><td>$size</td>
                       <td><form method="post" action="/delete"
                                 data-confirm="Delete $relEsc?"
                                 onsubmit="return confirmAndAttachPin(this)">
                           <input type="hidden" name="path" value="$relEsc">
                           <button>delete</button>
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
            </style>
            <script>
              function attachPin(form) {
                var p = document.getElementById('pin').value;
                form.action = form.getAttribute('action').split('?')[0] + '?pin=' + encodeURIComponent(p);
                return true;
              }
              function confirmAndAttachPin(form) {
                if (!confirm(form.dataset.confirm)) return false;
                return attachPin(form);
              }
            </script>
            </head><body>
            <h1>wBooks transfer</h1>
            <div class="row">
              <label>PIN: <input id="pin" type="password" autocomplete="off" inputmode="numeric"></label>
              <span class="note">Shown on the watch.</span>
            </div>
            <form method="post" action="/upload" enctype="multipart/form-data" class="row"
                  onsubmit="return attachPin(this)">
              <label>Folder (optional): <input type="text" name="folder" placeholder="e.g. fiction"></label>
              <input type="file" name="file" multiple accept=".epub,.txt,.fb2,.html,.htm,.xhtml,.docx,.odt">
              <button>Upload</button>
            </form>
            <form method="post" action="/mkdir" class="row" onsubmit="return attachPin(this)">
              <label>New folder: <input type="text" name="name" required></label>
              <button>Create</button>
            </form>
            <table>$rows</table>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun handleUpload(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        gatePin(session)?.let { return it }
        // NanoHTTPD writes uploaded files to temp paths and populates this map keyed by the form field.
        // Runs only after PIN passes so unauth peers can't fill storage by spamming uploads.
        val tempFiles = HashMap<String, String>()
        session.parseBody(tempFiles)
        val params = session.parameters

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
        if (session.method != Method.POST) return methodNotAllowed()
        gatePin(session)?.let { return it }
        val params = parsedForm(session)
        val path = params["path"]?.firstOrNull().orEmpty()
        val target = File(booksDir, path)
        if (!target.canonicalPath.startsWith(booksDir.canonicalPath) || !target.exists()) {
            return notFound()
        }
        if (target.isDirectory) target.deleteRecursively() else target.delete()
        return redirectToIndex("Deleted $path")
    }

    private fun handleMkdir(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        gatePin(session)?.let { return it }
        val params = parsedForm(session)
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
        if (session.method != Method.POST) return methodNotAllowed()
        gatePin(session)?.let { return it }
        val params = parsedForm(session)
        val from = File(booksDir, params["from"]?.firstOrNull().orEmpty())
        val toDir = File(booksDir, params["to"]?.firstOrNull().orEmpty().trim('/', '\\'))
        if (!from.canonicalPath.startsWith(booksDir.canonicalPath) ||
            !toDir.canonicalPath.startsWith(booksDir.canonicalPath) ||
            !from.exists()) {
            return notFound()
        }
        toDir.mkdirs()
        val dest = uniqueFile(toDir, from.name)
        if (!from.renameTo(dest)) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Move failed")
        }
        return redirectToIndex("Moved")
    }

    // --- helpers ---

    private fun parsedForm(session: IHTTPSession): Map<String, List<String>> {
        session.parseBody(HashMap())
        return session.parameters
    }

    /**
     * Returns null when the PIN matches, or an error Response (403 / 429) when it
     * doesn't. PIN comes from the query string so this can run *before* parseBody;
     * the comparison is constant-time and a sliding-window counter rate-limits
     * brute force against the 10k-combo PIN space.
     */
    private fun gatePin(session: IHTTPSession): Response? {
        val supplied = pinFromQuery(session).toByteArray(Charsets.UTF_8)
        if (MessageDigest.isEqual(supplied, pinBytes)) return null
        return if (recordFailedAttempt()) {
            newFixedLengthResponse(TOO_MANY_REQUESTS, "text/plain", "Too many bad PIN attempts; restart the server from the watch")
                .also { it.addHeader("Retry-After", (PIN_WINDOW_MS / 1000).toString()) }
        } else {
            forbidden("bad PIN")
        }
    }

    private fun pinFromQuery(session: IHTTPSession): String {
        val qs = session.queryParameterString ?: return ""
        for (kv in qs.split('&')) {
            val eq = kv.indexOf('=')
            val key = if (eq < 0) kv else kv.substring(0, eq)
            if (key == "pin") {
                val raw = if (eq < 0) "" else kv.substring(eq + 1)
                return try {
                    URLDecoder.decode(raw, "UTF-8")
                } catch (_: IllegalArgumentException) {
                    ""
                }
            }
        }
        return ""
    }

    /** True if this attempt has just exceeded the rate-limit threshold. */
    private fun recordFailedAttempt(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(failedAttempts) {
            while (failedAttempts.isNotEmpty() && now - failedAttempts.first() > PIN_WINDOW_MS) {
                failedAttempts.removeFirst()
            }
            failedAttempts.addLast(now)
            return failedAttempts.size > MAX_PIN_FAILURES
        }
    }

    private fun htmlEscape(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> out.append(c)
            }
        }
        return out.toString()
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

    companion object {
        private const val MAX_PIN_FAILURES = 10
        private const val PIN_WINDOW_MS = 60_000L

        // NanoHTTPD 2.3.1's Response.Status enum doesn't include 429.
        private val TOO_MANY_REQUESTS = object : Response.IStatus {
            override fun getDescription() = "429 Too Many Requests"
            override fun getRequestStatus() = 429
        }
    }
}

/** Generate a fresh 4-digit PIN as a string with leading zeros. */
internal fun generatePin(): String =
    (java.security.SecureRandom().nextInt(10_000)).toString().padStart(4, '0')
