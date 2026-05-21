package com.fredapp.wbooks.transfer

import android.content.res.AssetManager
import com.fredapp.wbooks.data.book.BookFormat
import com.fredapp.wbooks.data.settings.FontChoice
import com.fredapp.wbooks.data.settings.ReaderSettings
import com.fredapp.wbooks.data.settings.ReadingMode
import com.fredapp.wbooks.data.settings.SettingsRepository
import com.fredapp.wbooks.data.settings.ThemeChoice
import com.fredapp.wbooks.data.telemetry.CrashReportingPref
import com.fredapp.wbooks.util.uniqueFile
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
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
 * Books live under [booksDir]. Top-level subdirectories are treated as folders
 * for sorting, matching the watch and companion UIs.
 *
 * [onBookDeleted] is called (on the NanoHTTPD thread) with each book's ID after
 * it is removed via the web UI so callers can clean up associated DataStore state.
 */
class UploadServer(
    port: Int,
    private val booksDir: File,
    private val pin: String,
    private val settingsRepository: SettingsRepository,
    private val crashReportingPref: CrashReportingPref,
    private val assets: AssetManager,
    private val onBookDeleted: (bookId: String) -> Unit = {},
    private val onBookMoved: (fromBookId: String, toBookId: String) -> Unit = { _, _ -> },
) : NanoHTTPD(port) {

    private val pinBytes = pin.toByteArray(Charsets.UTF_8)
    private val failedAttempts = ArrayDeque<Long>()

    override fun serve(session: IHTTPSession): Response = try {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        when {
            uri == "/" -> when (session.method) {
                Method.GET -> renderIndex(queryParam(session, "msg"))
                else -> methodNotAllowed()
            }
            uri == "/upload" -> handleUpload(session)
            uri == "/delete" -> handleDelete(session)
            uri == "/mkdir" -> handleMkdir(session)
            uri == "/move" -> handleMove(session)
            uri == "/settings" -> handleSettings(session)
            // PDF.js shipped in the APK (assets/pdfjs/). Static, unauthenticated:
            // it's a copy of Mozilla's library, not user data.
            uri.startsWith("/pdfjs/") && session.method == Method.GET -> servePdfJsAsset(uri)
            else -> notFound()
        }
    } catch (e: Exception) {
        io.sentry.Sentry.captureException(e)
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server error: ${e.message}")
    }

    // --- handlers ---

    private fun renderIndex(flash: String): Response {
        val allEntries = booksDir.walkTopDown().filter { it != booksDir }.toList()
        val topFolders = allEntries
            .filter { it.isDirectory && it.parentFile?.canonicalPath == booksDir.canonicalPath }
            .sortedBy { it.name.lowercase() }
        val rootBooks = allEntries
            .filter { it.isFile && it.parentFile?.canonicalPath == booksDir.canonicalPath && BookFormat.fromExtension(it.extension) != null }
            .sortedBy { it.name.lowercase() }

        val rows = StringBuilder()
        var fIdx = 0

        for (dir in topFolders) {
            val folderRel = dir.relativeTo(booksDir).invariantSeparatorsPath
            val folderRelEsc = htmlEscape(folderRel)
            val folderBooks = allEntries
                .filter { it.isFile && it.parentFile?.canonicalPath == dir.canonicalPath && BookFormat.fromExtension(it.extension) != null }
                .sortedBy { it.name.lowercase() }
            val tbId = "f$fIdx"; fIdx++
            rows.append("""<tr class="folder-row drop-zone" data-folder="$folderRelEsc" onclick="toggleFolder('$tbId')">""")
            rows.append("""<td><span id="ch_$tbId" class="chevron">&#x25B6;</span> <span class="folder-icon">folder</span> $folderRelEsc/ <span class="cnt">(${folderBooks.size})</span><div class="drop-hint">Drop files here to upload to this folder</div></td>""")
            rows.append("""<td>folder</td><td><form method="post" action="/delete" class="inline" data-confirm="Delete folder $folderRelEsc and all its books?" onsubmit="event.stopPropagation();return confirmAndAttachPin(this)"><input type="hidden" name="path" value="$folderRelEsc"><button>delete</button></form></td></tr>""")
            rows.append("""<tbody id="$tbId" style="display:none">""")
            for (book in folderBooks) {
                val rel = book.relativeTo(booksDir).invariantSeparatorsPath
                val relEsc = htmlEscape(rel)
                val size = htmlEscape(humanBytes(book.length()))
                rows.append(bookRow(rel, relEsc, size, topFolders, folderRel))
            }
            rows.append("</tbody>")
        }

        val rootId = "root"
        rows.append("""<tr class="folder-row root-row drop-zone" data-folder="" onclick="toggleFolder('$rootId')">""")
        rows.append("""<td><span id="ch_$rootId" class="chevron open">&#x25B6;</span> <span class="folder-icon">folder</span> Root <span class="cnt">(${rootBooks.size})</span><div class="drop-hint">Drop files here to upload to Root</div></td>""")
        rows.append("""<td>root</td><td></td></tr>""")
        rows.append("""<tbody id="$rootId">""")
        for (book in rootBooks) {
            val rel = book.relativeTo(booksDir).invariantSeparatorsPath
            val relEsc = htmlEscape(rel)
            val size = htmlEscape(humanBytes(book.length()))
            rows.append(bookRow(rel, relEsc, size, topFolders, ""))
        }
        if (rootBooks.isEmpty()) {
            rows.append("""<tr><td class="empty-root" colspan="3">Root is empty. Drop files here to upload them at the top level.</td></tr>""")
        }
        rows.append("</tbody>")

        val flashHtml = if (flash.isNotEmpty())
            """<p class="flash">${htmlEscape(flash)}</p>""" else ""
        val settingsHtml = renderSettingsPanel()
        val html = """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>wBooks transfer</title>
            <style>
              body{font-family:system-ui,sans-serif;max-width:760px;margin:24px auto;padding:0 12px;}
              table{width:100%;border-collapse:collapse;margin-top:12px}
              td{padding:6px 4px;border-bottom:1px solid #eee;vertical-align:middle}
              form.inline{display:inline}
              form.move{display:inline-flex;gap:4px;align-items:center;margin-right:6px}
              .row{margin:12px 0}
              input[type=text],input[type=password],input[type=number],select{padding:6px}
              button{padding:6px 12px}
              .note{color:#666;font-size:0.85em}
              .flash{background:#e8f5e9;border:1px solid #a5d6a7;border-radius:4px;padding:8px 12px;margin:8px 0;}
              .folder-row{cursor:pointer;background:#fff8d8;font-weight:650;box-shadow:inset 0 0 0 1px #ead278;}
              .root-row{background:#fff2b7;}
              .folder-row:hover,.folder-row.drag-over{background:#ffe58a;}
              .folder-icon{display:inline-flex;align-items:center;justify-content:center;background:#e5ad22;color:#3e2a00;border-radius:5px;padding:2px 7px;margin-right:4px;font-size:0.72em;text-transform:uppercase;box-shadow:0 1px 3px rgba(0,0,0,0.22);}
              .drop-hint{font-size:0.78em;color:#775b00;font-weight:500;margin:2px 0 0 26px;}
              .chevron{display:inline-block;transition:transform 0.15s;}
              .chevron.open{transform:rotate(90deg);}
              .cnt{font-weight:normal;color:#666;font-size:0.9em;}
              .book-indent{padding-left:28px;}
              .empty-root{color:#777;font-style:italic;padding-left:28px;}
              .settings{margin-top:24px;padding:14px;border:1px solid #ddd;border-radius:10px;background:#fafafa}
              .settings-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:12px;margin-top:10px}
              .setting label{display:block;font-weight:650;margin-bottom:4px}
              .setting small{display:block;color:#666;margin-top:3px}
              .checkbox-row{display:flex;align-items:center;gap:8px;margin-top:22px}
              .swatches{display:flex;gap:6px;flex-wrap:wrap}
              .swatch{width:30px;height:30px;border-radius:50%;border:2px solid #777;cursor:pointer}
              .swatch.selected{outline:3px solid #111}
              .modal{position:fixed;inset:0;background:rgba(0,0,0,0.55);display:none;align-items:center;justify-content:center;z-index:100}
              .modal.show{display:flex}
              .modal-card{background:#fff;color:#111;padding:18px 20px;border-radius:8px;max-width:480px;width:calc(100% - 24px);box-shadow:0 10px 36px rgba(0,0,0,0.35)}
              .modal-card h3{margin-top:0}
              .modal-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:14px}
              .pdf-note{color:#666;font-size:0.85em;margin:6px 0 0}
              @media (prefers-color-scheme: dark) {
                body{background:#111;color:#eee}
                td{border-bottom-color:#333}
                .folder-row{background:#4a3a13;box-shadow:inset 0 0 0 1px #8b6a1b;}
                .root-row{background:#5a4314;}
                .folder-row:hover,.folder-row.drag-over{background:#6a5019;}
                .folder-icon{background:#d1961b;color:#241700;box-shadow:0 1px 4px rgba(0,0,0,0.55);}
                .drop-hint,.cnt,.note,.empty-root{color:#c9b783}
                input,select,button{background:#222;color:#eee;border:1px solid #555}
                .flash{background:#18351d;border-color:#426b45}
                .settings{background:#181818;border-color:#3a3a3a}
                .setting small{color:#b8b8b8}
                .swatch.selected{outline-color:#fff}
                .modal-card{background:#1c1c1c;color:#eee}
                .pdf-note{color:#b8b8b8}
              }
            </style>
            <!--
              PDF.js (browser-side PDF-to-HTML conversion). Bundled in the APK
              under assets/pdfjs/ and served by the watch itself - no internet
              needed, no CDN trust. Keeps pdfbox-android (~8 MB) out of the watch
              while staying self-contained on the LAN.
            -->
            <script src="/pdfjs/pdf.min.js"></script>
            <script>
              function toggleFolder(id) {
                var tb = document.getElementById(id);
                var ch = document.getElementById('ch_' + id);
                var open = tb.style.display === '';
                tb.style.display = open ? 'none' : '';
                if (ch) { if (open) ch.classList.remove('open'); else ch.classList.add('open'); }
              }
              function attachPin(form) {
                var p = document.getElementById('pin').value;
                form.action = form.getAttribute('action').split('?')[0] + '?pin=' + encodeURIComponent(p);
                return true;
              }
              function confirmAndAttachPin(form) {
                if (!confirm(form.dataset.confirm)) return false;
                return attachPin(form);
              }
              async function submitUpload(e) {
                e.preventDefault();
                await uploadFiles(e.target.querySelector('input[type=file]').files, e.target.querySelector('input[name=folder]').value.trim());
              }
              // Sticky for the page's lifetime — first PDF upload shows the
              // warning modal; subsequent ones skip straight to conversion.
              // Reset on reload, which is the right scope for "this session".
              var pdfWarningAcknowledged = false;
              async function uploadFiles(files, folder) {
                var pin = document.getElementById('pin').value;
                if (!files.length) return;
                var arr = Array.prototype.slice.call(files);
                var hasPdf = arr.some(function(f){return /\.pdf$/i.test(f.name);});
                if (hasPdf && !pdfWarningAcknowledged) {
                  var ok = await confirmPdfWarning();
                  if (!ok) return;
                  pdfWarningAcknowledged = true;
                }
                var prepared = [];
                for (var i = 0; i < arr.length; i++) {
                  var f = arr[i];
                  if (/\.pdf$/i.test(f.name)) {
                    try {
                      var html = await convertPdfToHtml(f);
                      var base = f.name.replace(/\.pdf$/i, '');
                      prepared.push(new File([html], base + ' [PDF].html', {type:'text/html'}));
                    } catch (e) {
                      alert('PDF conversion failed for ' + f.name + ': ' + (e && e.message ? e.message : e));
                      return;
                    }
                  } else {
                    prepared.push(f);
                  }
                }
                var fd = new FormData();
                if (folder) fd.append('folder', folder);
                for (var j = 0; j < prepared.length; j++) {
                  fd.append('file' + j, prepared[j], prepared[j].name);
                }
                try {
                  var resp = await fetch('/upload?pin=' + encodeURIComponent(pin), {method:'POST', body: fd});
                  if (!resp.ok) { alert('Upload failed: ' + resp.status); return; }
                } catch(err) { alert('Upload error: ' + err); return; }
                location.href = '/';
              }
              // ----- PDF conversion (mirrors PdfConverter.kt on phone) -----
              // PDF.js served from the watch APK at /pdfjs/. Heuristics: font
              // name contains bold/italic/oblique/black/heavy -> run styling;
              // per-paragraph max font size >= 1.6x/1.3x/1.1x the document
              // median promotes to h1/h2/h3. Page boundaries break paragraphs.
              var pdfjsWorkerReady = false;
              function ensurePdfjs() {
                if (typeof pdfjsLib === 'undefined') {
                  throw new Error('PDF.js failed to load. Reload the page and try again.');
                }
                if (!pdfjsWorkerReady) {
                  pdfjsLib.GlobalWorkerOptions.workerSrc = '/pdfjs/pdf.worker.min.js';
                  pdfjsWorkerReady = true;
                }
              }
              async function convertPdfToHtml(file) {
                ensurePdfjs();
                var buf = await file.arrayBuffer();
                var pdf = await pdfjsLib.getDocument({data: buf}).promise;
                var items = [];
                var sizes = [];
                for (var p = 1; p <= pdf.numPages; p++) {
                  var page = await pdf.getPage(p);
                  var tc = await page.getTextContent();
                  for (var k = 0; k < tc.items.length; k++) {
                    var it = tc.items[k];
                    if (!it.str) continue;
                    var fname = it.fontName || '';
                    try {
                      var f = page.commonObjs.get(it.fontName);
                      if (f && f.name) fname = f.name;
                    } catch (e) { /* font not registered yet - fall back to raw id */ }
                    var bold = /bold|black|heavy/i.test(fname);
                    var italic = /italic|oblique/i.test(fname);
                    var sz = it.height || Math.abs((it.transform && it.transform[3]) || (it.transform && it.transform[0]) || 12);
                    items.push({text: it.str, bold: bold, italic: italic, size: sz, page: p, eol: !!it.hasEOL});
                    sizes.push(sz);
                  }
                }
                sizes.sort(function(a,b){return a-b;});
                var median = sizes.length ? sizes[Math.floor(sizes.length/2)] : 12;
                return buildPdfHtml(items, median, file.name.replace(/\.pdf$/i, ''));
              }
              function buildPdfHtml(items, median, title) {
                var h1c = median * 1.6, h2c = median * 1.3, h3c = median * 1.1;
                var paras = [];
                var curr = [];
                var currMax = 0;
                var prevPage = null;
                function flush() {
                  var txt = curr.map(function(r){return r.text;}).join('').replace(/\s+/g,' ').trim();
                  if (txt) paras.push({runs: mergePdfRuns(curr), max: currMax});
                  curr = []; currMax = 0;
                }
                for (var i = 0; i < items.length; i++) {
                  var it = items[i];
                  if (prevPage !== null && prevPage !== it.page) flush();
                  curr.push({text: it.text, bold: it.bold, italic: it.italic});
                  if (it.size > currMax) currMax = it.size;
                  if (it.eol) curr.push({text: ' ', bold: false, italic: false});
                  prevPage = it.page;
                }
                flush();
                var html = '<!doctype html>\n<html><head><meta charset="utf-8"><title>' + escPdfHtml(title) + '</title></head><body>\n';
                if (paras.length === 0) {
                  html += '<p><em>No extractable text in this PDF. It may be a scanned-image document.</em></p>\n';
                } else {
                  for (var i2 = 0; i2 < paras.length; i2++) {
                    var p = paras[i2];
                    var tag = 'p';
                    if (p.max >= h1c) tag = 'h1';
                    else if (p.max >= h2c) tag = 'h2';
                    else if (p.max >= h3c) tag = 'h3';
                    html += '<' + tag + '>';
                    if (tag === 'p') {
                      html += renderPdfRuns(p.runs);
                    } else {
                      html += escPdfHtml(p.runs.map(function(r){return r.text;}).join('').replace(/\s+/g,' ').trim());
                    }
                    html += '</' + tag + '>\n';
                  }
                }
                html += '</body></html>\n';
                return html;
              }
              function mergePdfRuns(runs) {
                var out = [];
                for (var i = 0; i < runs.length; i++) {
                  var r = runs[i];
                  var last = out.length ? out[out.length-1] : null;
                  if (last && last.bold === r.bold && last.italic === r.italic) {
                    last.text += r.text;
                  } else {
                    out.push({text: r.text, bold: r.bold, italic: r.italic});
                  }
                }
                return out;
              }
              function renderPdfRuns(runs) {
                var s = '';
                for (var i = 0; i < runs.length; i++) {
                  var r = runs[i];
                  var t = escPdfHtml(r.text);
                  if (r.bold && r.italic) s += '<strong><em>' + t + '</em></strong>';
                  else if (r.bold) s += '<strong>' + t + '</strong>';
                  else if (r.italic) s += '<em>' + t + '</em>';
                  else s += t;
                }
                return s;
              }
              function escPdfHtml(s) {
                return String(s).replace(/[&<>"]/g, function(c){
                  return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'})[c];
                });
              }
              function confirmPdfWarning() {
                return new Promise(function(resolve) {
                  var modal = document.getElementById('pdf-modal');
                  modal.classList.add('show');
                  document.getElementById('pdf-modal-cancel').onclick = function() {
                    modal.classList.remove('show'); resolve(false);
                  };
                  document.getElementById('pdf-modal-ok').onclick = function() {
                    modal.classList.remove('show'); resolve(true);
                  };
                });
              }
              function installDropZones() {
                document.querySelectorAll('.drop-zone').forEach(function(zone) {
                  zone.addEventListener('dragover', function(e) {
                    if (e.dataTransfer && e.dataTransfer.types && Array.prototype.indexOf.call(e.dataTransfer.types, 'Files') >= 0) {
                      e.preventDefault();
                      zone.classList.add('drag-over');
                    }
                  });
                  zone.addEventListener('dragleave', function() { zone.classList.remove('drag-over'); });
                  zone.addEventListener('drop', function(e) {
                    if (!e.dataTransfer || !e.dataTransfer.files.length) return;
                    e.preventDefault();
                    e.stopPropagation();
                    zone.classList.remove('drag-over');
                    uploadFiles(e.dataTransfer.files, zone.dataset.folder || '');
                  });
                });
              }
              window.addEventListener('DOMContentLoaded', installDropZones);
              function setColor(value) {
                document.getElementById('textColorArgb').value = value;
                document.querySelectorAll('.swatch').forEach(function(s) {
                  s.classList.toggle('selected', s.dataset.value === value);
                });
              }
            </script>
            </head><body>
            <h1>wBooks transfer</h1>
            $flashHtml
            <div class="row">
              <label>PIN: <input id="pin" type="password" autocomplete="off" inputmode="numeric"></label>
              <span class="note">Shown on the watch.</span>
            </div>
            <form method="post" action="/upload" enctype="multipart/form-data" class="row"
                  onsubmit="submitUpload(event)">
              <label>Folder (optional): <input type="text" name="folder" placeholder="e.g. fiction"></label>
              <input type="file" name="file" multiple accept=".epub,.txt,.fb2,.html,.htm,.xhtml,.docx,.odt,.pdf,application/pdf">
              <button>Upload</button>
              <p class="pdf-note">PDF support is experimental - the browser converts to HTML before upload. Scanned PDFs convert to nothing useful (no OCR).</p>
            </form>
            <form method="post" action="/mkdir" class="row" onsubmit="return attachPin(this)">
              <label>New folder: <input type="text" name="name" required></label>
              <button>Create</button>
            </form>
            <table>$rows</table>
            $settingsHtml
            <div id="pdf-modal" class="modal" role="dialog" aria-modal="true">
              <div class="modal-card">
                <h3>Experimental: convert PDF</h3>
                <p>PDF support is experimental.</p>
                <ul>
                  <li>If the PDF contains real text, it will be converted and sent to the watch with basic formatting (paragraphs, headings, bold, italic).</li>
                  <li>If the PDF only contains scanned images, the result will be empty or garbled - there is no OCR.</li>
                </ul>
                <p>Converted PDFs appear in your library marked <strong>[PDF]</strong> after the title.</p>
                <p class="pdf-note">Parsing runs in this browser using PDF.js, served from the watch over your LAN - no internet needed.</p>
                <div class="modal-actions">
                  <button type="button" id="pdf-modal-cancel">Cancel</button>
                  <button type="button" id="pdf-modal-ok">Convert</button>
                </div>
              </div>
            </div>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun handleUpload(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        gatePin(session)?.let { return it }
        // NanoHTTPD writes uploaded files to temp paths and populates this map keyed by the form field.
        // The JS upload function uses unique field names (file0, file1, â€¦) so all files survive in
        // this map even when multiple are uploaded at once. Runs only after PIN passes so
        // unauthenticated peers can't fill storage by spamming uploads.
        val tempFiles = HashMap<String, String>()
        session.parseBody(tempFiles)
        val params = session.parameters

        val folder = cleanTopLevelFolder(params["folder"]?.firstOrNull().orEmpty())
            ?: return badRequest("folder must be a single folder name")
        val targetDir = if (folder.isEmpty()) booksDir else File(booksDir, folder)
        if (!targetDir.isInsideBooksDir()) {
            return forbidden("folder escapes books dir")
        }
        targetDir.mkdirs()

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
        return redirectToIndex(if (written > 0) "Uploaded $written file(s)" else "No supported files found in upload")
    }

    private fun handleDelete(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        gatePin(session)?.let { return it }
        val params = parsedForm(session)
        val path = params["path"]?.firstOrNull().orEmpty()
        if (path.isBlank()) return badRequest("path required")
        val target = File(booksDir, path)
        if (!target.isInsideBooksDir() || target.isBooksRoot() || !target.exists()) {
            return notFound()
        }
        if (target.isDirectory) {
            // Collect book IDs before deleting so we can clean up associated data.
            val bookIds = target.walkTopDown()
                .filter { it.isFile && BookFormat.fromExtension(it.extension) != null }
                .map { it.relativeTo(booksDir).invariantSeparatorsPath }
                .toList()
            target.deleteRecursively()
            bookIds.forEach { onBookDeleted(it) }
        } else {
            target.delete()
            onBookDeleted(path)
        }
        return redirectToIndex("Deleted $path")
    }

    private fun handleMkdir(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        gatePin(session)?.let { return it }
        val params = parsedForm(session)
        val name = cleanTopLevelFolder(params["name"]?.firstOrNull().orEmpty())
            ?: return badRequest("folder must be a single folder name")
        if (name.isEmpty()) return badRequest("folder name required")
        val target = File(booksDir, name)
        if (!target.isInsideBooksDir() || target.isBooksRoot()) {
            return forbidden("name escapes books dir")
        }
        target.mkdirs()
        return redirectToIndex("Created $name")
    }

    private fun handleMove(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        gatePin(session)?.let { return it }
        val params = parsedForm(session)
        val fromPath = params["from"]?.firstOrNull().orEmpty()
        if (fromPath.isBlank()) return badRequest("source path required")
        val from = File(booksDir, fromPath)
        val toFolder = cleanTopLevelFolder(params["to"]?.firstOrNull().orEmpty())
            ?: return badRequest("folder must be a single folder name")
        val toDir = if (toFolder.isEmpty()) booksDir else File(booksDir, toFolder)
        if (!from.isInsideBooksDir() ||
            !toDir.isInsideBooksDir() ||
            !from.exists() ||
            !from.isFile ||
            BookFormat.fromExtension(from.extension) == null) {
            return notFound()
        }
        toDir.mkdirs()
        if (from.parentFile?.canonicalFile == toDir.canonicalFile) {
            return redirectToIndex("Moved")
        }
        val dest = uniqueFile(toDir, from.name)
        if (!from.renameTo(dest)) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Move failed")
        }
        onBookMoved(from.relativeTo(booksDir).invariantSeparatorsPath, dest.relativeTo(booksDir).invariantSeparatorsPath)
        return redirectToIndex("Moved")
    }

    private fun handleSettings(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        gatePin(session)?.let { return it }
        val params = parsedForm(session)
        runBlocking {
            settingsRepository.update { current ->
                current.copy(
                    mode = enumParam(params, "mode", current.mode),
                    font = enumParam(params, "font", current.font),
                    theme = enumParam(params, "theme", current.theme),
                    textSizeSp = intParam(params, "textSizeSp", current.textSizeSp)
                        .coerceIn(ReaderSettings.TEXT_SIZE_RANGE),
                    sentenceTextSizeSp = intParam(params, "sentenceTextSizeSp", current.sentenceTextSizeSp)
                        .coerceIn(ReaderSettings.SENTENCE_TEXT_SIZE_RANGE),
                    textColorArgb = intParam(params, "textColorArgb", current.textColorArgb),
                    autoscrollEnabled = params["autoscrollEnabled"]?.firstOrNull() == "on",
                    autoscrollSpeed = intParam(params, "autoscrollSpeed", current.autoscrollSpeed)
                        .coerceIn(ReaderSettings.AUTOSCROLL_SPEED_RANGE),
                    screenBrightness = intParam(params, "screenBrightness", current.screenBrightness)
                        .coerceIn(ReaderSettings.SCREEN_BRIGHTNESS_RANGE),
                    speedreadWpm = intParam(params, "speedreadWpm", current.speedreadWpm)
                        .coerceIn(ReaderSettings.WPM_RANGE),
                )
            }
        }
        crashReportingPref.setEnabled(params["crashReportingEnabled"]?.firstOrNull() == "on")
        return redirectToIndex("Settings saved")
    }

    // --- helpers ---

    private fun bookRow(
        rel: String,
        relEsc: String,
        size: String,
        topFolders: List<File>,
        currentFolder: String,
    ): String {
        val options = buildString {
            val rootSelected = if (currentFolder.isEmpty()) " selected" else ""
            append("""<option value=""$rootSelected>Root</option>""")
            for (dir in topFolders) {
                val folder = dir.relativeTo(booksDir).invariantSeparatorsPath
                val folderEsc = htmlEscape(folder)
                val selected = if (folder == currentFolder) " selected" else ""
                append("""<option value="$folderEsc"$selected>$folderEsc</option>""")
            }
        }
        return """<tr><td class="book-indent">$relEsc</td><td>$size</td><td><form method="post" action="/move" class="move" onsubmit="return attachPin(this)"><input type="hidden" name="from" value="$relEsc"><select name="to">$options</select><button>move</button></form><form method="post" action="/delete" class="inline" data-confirm="Delete $relEsc?" onsubmit="return confirmAndAttachPin(this)"><input type="hidden" name="path" value="$relEsc"><button>delete</button></form></td></tr>"""
    }

    private fun renderSettingsPanel(): String {
        val s = runBlocking { settingsRepository.snapshot() }
        val crash = crashReportingPref.enabled.value
        val swatches = ReaderSettings.TEXT_COLOR_PALETTE.joinToString("") { color ->
            val value = color.toString()
            val selected = if (color == s.textColorArgb) " selected" else ""
            """<button type="button" class="swatch$selected" data-value="$value" style="background:${argbCss(color)}" title="${argbHex(color)}" onclick="setColor('$value')"></button>"""
        }
        return """
            <section class="settings">
              <h2>Watch settings</h2>
              <p class="note">These controls read from and save to the watch. The watch remains authoritative.</p>
              <form method="post" action="/settings" onsubmit="return attachPin(this)">
                <div class="settings-grid">
                  ${selectSetting("Reading mode", "mode", ReadingMode.entries.map { it.name }, s.mode.name)}
                  ${selectSetting("Theme", "theme", ThemeChoice.entries.map { it.name }, s.theme.name)}
                  ${selectSetting("Font", "font", FontChoice.entries.map { it.name }, s.font.name)}
                  ${numberSetting("Text size", "textSizeSp", s.textSizeSp, ReaderSettings.TEXT_SIZE_RANGE)}
                  ${numberSetting("Sentence text size", "sentenceTextSizeSp", s.sentenceTextSizeSp, ReaderSettings.SENTENCE_TEXT_SIZE_RANGE)}
                  <div class="setting">
                    <label>Text color</label>
                    <input id="textColorArgb" type="hidden" name="textColorArgb" value="${s.textColorArgb}">
                    <div class="swatches">$swatches</div>
                  </div>
                  ${numberSetting("Autoscroll speed", "autoscrollSpeed", s.autoscrollSpeed, ReaderSettings.AUTOSCROLL_SPEED_RANGE)}
                  ${numberSetting("Screen brightness", "screenBrightness", s.screenBrightness, ReaderSettings.SCREEN_BRIGHTNESS_RANGE, "%")}
                  ${numberSetting("Speed-read WPM", "speedreadWpm", s.speedreadWpm, ReaderSettings.WPM_RANGE)}
                  ${checkboxSetting("Autoscroll", "autoscrollEnabled", s.autoscrollEnabled)}
                  ${checkboxSetting("Crash reports", "crashReportingEnabled", crash)}
                </div>
                <p><button>Save settings</button></p>
              </form>
            </section>
        """.trimIndent()
    }

    private fun selectSetting(label: String, name: String, options: List<String>, selected: String): String {
        val optionHtml = options.joinToString("") { value ->
            val sel = if (value == selected) " selected" else ""
            """<option value="$value"$sel>${htmlEscape(value.lowercase().replaceFirstChar { it.titlecase() })}</option>"""
        }
        return """<div class="setting"><label>$label</label><select name="$name">$optionHtml</select></div>"""
    }

    private fun numberSetting(label: String, name: String, value: Int, range: IntRange, suffix: String = ""): String =
        """<div class="setting"><label>$label</label><input type="number" name="$name" value="$value" min="${range.first}" max="${range.last}"><small>${range.first}-${range.last}$suffix</small></div>"""

    private fun checkboxSetting(label: String, name: String, checked: Boolean): String {
        val attr = if (checked) " checked" else ""
        return """<div class="setting checkbox-row"><input type="checkbox" name="$name"$attr><label>$label</label></div>"""
    }

    private inline fun <reified E : Enum<E>> enumParam(params: Map<String, List<String>>, name: String, fallback: E): E =
        params[name]?.firstOrNull()?.let { raw -> runCatching { enumValueOf<E>(raw) }.getOrNull() } ?: fallback

    private fun intParam(params: Map<String, List<String>>, name: String, fallback: Int): Int =
        params[name]?.firstOrNull()?.toIntOrNull() ?: fallback

    private fun cleanTopLevelFolder(raw: String): String? {
        val folder = raw.trim().trim('/', '\\')
        if (folder.contains('/') || folder.contains('\\')) return null
        return folder
    }

    private fun argbCss(argb: Int): String = "#%06X".format(argb and 0x00FFFFFF)

    private fun argbHex(argb: Int): String = "#%08X".format(argb)

    private fun File.isInsideBooksDir(): Boolean =
        canonicalFile.toPath().startsWith(booksDir.canonicalFile.toPath())

    private fun File.isBooksRoot(): Boolean =
        canonicalFile == booksDir.canonicalFile

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

    private fun pinFromQuery(session: IHTTPSession): String = queryParam(session, "pin")

    private fun queryParam(session: IHTTPSession, name: String): String {
        val qs = session.queryParameterString ?: return ""
        for (kv in qs.split('&')) {
            val eq = kv.indexOf('=')
            val key = if (eq < 0) kv else kv.substring(0, eq)
            if (key == name) {
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


    private fun redirectToIndex(flash: String): Response {
        // 303 See Other so the browser issues a fresh GET / instead of resubmitting the form.
        val resp = newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "")
        resp.addHeader("Location", "/?msg=" + URLEncoder.encode(flash, "UTF-8"))
        return resp
    }

    private fun forbidden(msg: String) =
        newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", msg)

    private fun notFound() =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")

    /**
     * Stream a file from [assets] under `pdfjs/`. The route gate already restricts
     * the URI prefix to `/pdfjs/`, but we also reject any `..` segment as defence
     * in depth so a clever browser can't escape into other asset paths.
     */
    private fun servePdfJsAsset(uri: String): Response {
        val rel = uri.removePrefix("/pdfjs/")
        if (rel.isEmpty() || rel.contains("..") || rel.startsWith("/")) return notFound()
        val assetPath = "pdfjs/$rel"
        return try {
            val stream = assets.open(assetPath)
            val mime = when {
                rel.endsWith(".js") -> "application/javascript; charset=utf-8"
                rel.endsWith(".map") -> "application/json"
                else -> "application/octet-stream"
            }
            // Caches across page reloads. The file is part of the APK so it only
            // changes when the user updates the app, at which point the URL still
            // points to the new bytes (no immutable hash in the name yet).
            val resp = newChunkedResponse(Response.Status.OK, mime, stream)
            resp.addHeader("Cache-Control", "public, max-age=86400")
            resp
        } catch (_: java.io.IOException) {
            notFound()
        }
    }

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
