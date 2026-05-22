package com.fredapp.wbooks.transfer

import android.content.res.AssetManager
import com.fredapp.wbooks.data.book.BookFormat
import com.fredapp.wbooks.data.folder.FolderPolicy
import com.fredapp.wbooks.data.settings.FontChoice
import com.fredapp.wbooks.data.settings.ReaderSettings
import com.fredapp.wbooks.data.settings.ReadingMode
import com.fredapp.wbooks.data.settings.SettingsRepository
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
    private val onFolderRenamed: (oldFolder: String, newFolder: String) -> Unit = { _, _ -> },
) : NanoHTTPD(port) {

    private val pinBytes = pin.toByteArray(Charsets.UTF_8)
    private val failedAttempts = ArrayDeque<Long>()

    override fun serve(session: IHTTPSession): Response = try {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        when {
            uri == "/" -> when (session.method) {
                Method.GET -> renderIndex(session, queryParam(session, "msg"))
                else -> methodNotAllowed()
            }
            uri == "/upload" -> handleUpload(session)
            uri == "/delete" -> handleDelete(session)
            uri == "/mkdir" -> handleMkdir(session)
            uri == "/move" -> handleMove(session)
            uri == "/rename" -> handleRename(session)
            uri == "/settings" -> handleSettings(session)
            uri == "/pin-check" -> handlePinCheck(session)
            uri == "/health" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
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

    private fun renderIndex(session: IHTTPSession, flash: String): Response {
        if (!hasValidPin(session)) {
            // Tick the rate-limit counter so probing the index page is not a free oracle.
            // Don't surface the 429 here — just show the pin gate; throttling kicks in
            // for mutating endpoints. Also clear any stale cookie from a prior server run.
            if (pinFromRequest(session).isNotEmpty()) recordFailedAttempt()
            val resp = renderPinGate(flash)
            resp.addHeader("Set-Cookie", "$WEB_PIN_COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")
            return resp
        }
        val allEntries = booksDir.walkTopDown().filter { it != booksDir }.toList()
        val topFolders = allEntries
            .filter { it.isDirectory && it.parentFile?.canonicalPath == booksDir.canonicalPath }
            .sortedBy { it.name.lowercase() }
        val rootBooks = allEntries
            .filter { it.isFile && it.parentFile?.canonicalPath == booksDir.canonicalPath && BookFormat.fromExtension(it.extension) != null }
            .sortedBy { it.name.lowercase() }

        val library = StringBuilder()
        var fIdx = 0

        val rootId = "root"
        library.append("""<section class="library-section root-section drop-zone" data-folder="">""")
        library.append("""<button type="button" class="folder-head" onclick="toggleFolder('$rootId')">""")
        library.append("""<span class="folder-mark">root</span><span class="folder-title">Root</span><span class="count">${rootBooks.size} book${if (rootBooks.size == 1) "" else "s"}</span></button>""")
        library.append("""<div id="$rootId" class="book-list open">""")
        if (rootBooks.isEmpty()) {
            library.append("""<p class="empty-state">Drop files here to upload them at the top level.</p>""")
        } else {
            for (book in rootBooks) {
                val rel = book.relativeTo(booksDir).invariantSeparatorsPath
                val relEsc = htmlEscape(rel)
                val size = htmlEscape(humanBytes(book.length()))
                library.append(bookCard(book, rel, relEsc, size, ""))
            }
        }
        library.append("</div></section>")

        for (dir in topFolders) {
            val folderRel = dir.relativeTo(booksDir).invariantSeparatorsPath
            val folderRelEsc = htmlEscape(folderRel)
            val folderBooks = allEntries
                .filter { it.isFile && it.parentFile?.canonicalPath == dir.canonicalPath && BookFormat.fromExtension(it.extension) != null }
                .sortedBy { it.name.lowercase() }
            val tbId = "f$fIdx"; fIdx++
            library.append("""<section class="library-section drop-zone" data-folder="$folderRelEsc">""")
            library.append("""<div class="folder-shell">""")
            library.append("""<button type="button" class="folder-head" onclick="toggleFolder('$tbId')">""")
            library.append("""<span class="folder-mark">folder</span><span class="folder-title">$folderRelEsc</span><span class="count">${folderBooks.size} book${if (folderBooks.size == 1) "" else "s"}</span></button>""")
            library.append("""<div class="folder-actions">""")
            library.append("""<button type="button" onclick="renameFolder(${jsString(folderRel)})">Rename</button>""")
            library.append("""<form method="post" action="/delete" class="inline" data-confirm="Delete folder $folderRelEsc and all its books?" onsubmit="return confirmAndAttachPin(this)"><input type="hidden" name="path" value="$folderRelEsc"><button class="danger">Delete</button></form>""")
            library.append("""</div></div>""")
            library.append("""<div id="$tbId" class="book-list">""")
            if (folderBooks.isEmpty()) {
                library.append("""<p class="empty-state">Drop files here to upload to this folder.</p>""")
            } else {
                for (book in folderBooks) {
                    val rel = book.relativeTo(booksDir).invariantSeparatorsPath
                    val relEsc = htmlEscape(rel)
                    val size = htmlEscape(humanBytes(book.length()))
                    library.append(bookCard(book, rel, relEsc, size, folderRel))
                }
            }
            library.append("</div></section>")
        }

        val flashHtml = if (flash.isNotEmpty())
            """<p class="flash" role="status">${htmlEscape(flash)}</p>""" else ""
        val webSettings = runBlocking { settingsRepository.snapshot() }
        val settingsHtml = renderSettingsPanel(webSettings)
        val bodyStyle = "font-family:${webFontCss(webSettings.font)},system-ui,sans-serif;color:${argbCss(webSettings.textColorArgb)};background:#111;"
        val html = """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>wBooks transfer</title>
            <style>
              :root{--bg:#f3f0e8;--panel:#fffdf8;--panel-2:#f7f2e6;--control:#f2eadc;--ink:#211d18;--muted:#756b5e;--line:#ded2bf;--accent:#b35318;--accent-2:#1f6f69;--danger:#a83232;--shadow:0 14px 36px rgba(52,37,20,0.12)}
              *{box-sizing:border-box}
              body{margin:0;min-height:100vh;background:radial-gradient(circle at top left,#fff8e8 0,#f3f0e8 42%,#ebe7de 100%);font-family:system-ui,sans-serif;color:var(--ink)}
              .page{max-width:1120px;margin:0 auto;padding:28px 18px 44px}
              .hero{display:grid;grid-template-columns:1.5fr 1fr;gap:18px;align-items:stretch;margin-bottom:18px}
              .brand,.pin-card,.upload-card,.library-section,.settings{background:var(--panel);border:1px solid var(--line);border-radius:8px;box-shadow:var(--shadow)}
              .brand{padding:22px 24px}
              .brand h1{margin:0;font-size:2.1rem;line-height:1.05}
              .brand p{margin:10px 0 0;color:var(--muted);max-width:58ch}
              .pin-card{padding:18px;display:flex;flex-direction:column;gap:10px}
              .pin-card label,.upload-card label,.setting label{font-weight:700}
              .pin-row{display:flex;gap:8px}
              input[type=text],input[type=password],input[type=number],select{width:100%;border:1px solid var(--line);border-radius:6px;background:var(--control);padding:10px 11px;color:var(--ink)}
              button{border:1px solid var(--line);border-radius:6px;background:#fffaf1;color:var(--ink);padding:9px 12px;font-weight:700;cursor:pointer}
              button:hover{border-color:var(--accent);color:var(--accent)}
              button.primary{background:var(--accent);border-color:var(--accent);color:#fff}
              button.danger{color:var(--danger)}
              button[title]{width:36px;height:36px;padding:0;flex-shrink:0;display:flex;align-items:center;justify-content:center;font-size:1.1rem}
              .note,.pdf-note,.drop-copy,.empty-state,.meta,.setting small{color:var(--muted);font-size:0.9rem}
              .flash{background:#e8f5e9;border:1px solid #9fcca2;border-radius:8px;padding:10px 12px;margin:0 0 16px}
              .workspace{display:grid;grid-template-columns:330px 1fr;gap:18px;align-items:start}
              .upload-card{padding:18px;position:sticky;top:16px}
              .upload-card h2,.library h2,.settings h2{margin:0 0 12px}
              .upload-form{display:grid;gap:12px}
              .file-picker{border:1px dashed var(--accent);border-radius:8px;background:#fff8ed;padding:14px}
              .file-picker input{width:100%}
              .library{display:grid;gap:12px}
              .library-top{display:flex;justify-content:space-between;gap:12px;align-items:end}
              .library-top p{margin:0}
              .library-section{overflow:hidden}
              .library-section.drag-over,.file-picker.drag-over{outline:3px solid rgba(179,83,24,.24);background:#fff4e4}
              .folder-shell,.folder-head{display:flex;align-items:center;gap:10px}
              .folder-shell{justify-content:space-between;background:var(--panel-2);border-bottom:1px solid var(--line);padding:12px}
              .root-section>.folder-head{width:100%;background:var(--panel-2);border-bottom:1px solid var(--line);padding:12px}
              .folder-head{border:0;background:transparent;flex:1;text-align:left;padding:0}
              .folder-mark{position:relative;display:inline-flex;flex:0 0 auto;width:34px;height:24px;border-radius:3px 5px 5px 5px;background:linear-gradient(160deg,#f3c85a,#ce8c22);color:transparent;font-size:0;box-shadow:0 3px 8px rgba(86,48,8,.24)}
              .folder-mark:before{content:"";position:absolute;left:3px;top:-5px;width:16px;height:7px;border-radius:4px 4px 0 0;background:#f5d476}
              .folder-title{font-size:1.05rem;font-weight:800}
              .count{margin-left:auto;color:var(--muted);font-weight:600}
              .folder-actions,.book-actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
              .book-list{display:none;padding:10px;gap:8px}
              .book-list.open{display:grid}
              .book-card{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:10px;align-items:center;border:1px solid var(--line);border-radius:8px;background:#fff;padding:12px}
              .book-title{display:block;font-weight:800;overflow-wrap:anywhere}
              .book-path{display:block;color:var(--muted);font-size:.86rem;overflow-wrap:anywhere}
              form.inline{display:inline}
              .settings{margin-top:18px;padding:18px}
              .settings-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:12px;margin-top:10px}
              .setting{background:var(--panel-2);border:1px solid var(--line);border-radius:8px;padding:12px}
              .setting small{display:block;margin-top:4px}
              .checkbox-row{display:flex;align-items:center;gap:8px}
              .swatches{display:flex;gap:8px;flex-wrap:wrap}
              .swatch{width:32px;height:32px;border-radius:50%;border:2px solid var(--line);cursor:pointer}
              .swatch.selected{outline:3px solid var(--accent)}
              .modal{position:fixed;inset:0;background:rgba(0,0,0,0.55);display:none;align-items:center;justify-content:center;z-index:100}
              .modal.show{display:flex}
              .modal-card{background:var(--panel);color:var(--ink);padding:20px;border-radius:8px;max-width:520px;width:calc(100% - 24px);box-shadow:0 10px 36px rgba(0,0,0,0.35)}
              .modal-card h3{margin-top:0}
              .modal-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:14px}
              @media (prefers-color-scheme: dark) {
                :root{--bg:#111;--panel:#191919;--panel-2:#232323;--control:#222;--ink:#eee;--muted:#b8b8b8;--line:#3b3b3b;--accent:#df7f34;--accent-2:#6cc1ba;--shadow:0 14px 36px rgba(0,0,0,.35)}
                body{background:linear-gradient(135deg,#101010,#1c1a17 55%,#111)}
                input,select,button,.book-card{background:var(--control);color:#eee;border-color:#555}
                .file-picker{background:#211b15}
                .flash{background:#18351d;border-color:#426b45}
              }
              @media (max-width: 760px) {
                .hero,.workspace{grid-template-columns:1fr}
                .upload-card{position:static}
                .library-top{display:block}
                .book-card{grid-template-columns:1fr}
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
                var open = tb.classList.contains('open');
                tb.classList.toggle('open', !open);
              }
              function pinValue() { return (document.getElementById('pin').value || '').trim(); }
              function storePin(pin) {
                document.getElementById('pin').value = pin;
                sessionStorage.setItem('wbooksPin', pin);
              }
              var pendingPinResolve = null;
              function showPinModal() {
                return new Promise(function(resolve) {
                  pendingPinResolve = resolve;
                  var modal = document.getElementById('pin-modal');
                  var input = document.getElementById('pin-modal-input');
                  input.value = pinValue();
                  document.getElementById('pin-modal-error').textContent = '';
                  modal.classList.add('show');
                  setTimeout(function(){ input.focus(); input.select(); }, 0);
                });
              }
              function closePinModal(value) {
                var modal = document.getElementById('pin-modal');
                modal.classList.remove('show');
                if (pendingPinResolve) {
                  var resolve = pendingPinResolve;
                  pendingPinResolve = null;
                  resolve(value);
                }
              }
              async function ensurePin() {
                var pin = pinValue();
                if (pin) return pin;
                pin = await showPinModal();
                if (!pin) return null;
                storePin(pin);
                return pin;
              }
              function attachPin(form) {
                var p = pinValue();
                if (p) {
                  form.action = form.getAttribute('action').split('?')[0] + '?pin=' + encodeURIComponent(p);
                  return true;
                }
                showPinModal().then(function(pin) {
                  if (!pin) return;
                  storePin(pin);
                  form.action = form.getAttribute('action').split('?')[0] + '?pin=' + encodeURIComponent(pin);
                  form.submit();
                });
                return false;
              }
              function confirmAndAttachPin(form) {
                if (!confirm(form.dataset.confirm)) return false;
                return attachPin(form);
              }
              async function submitUpload(e) {
                e.preventDefault();
                await uploadFiles(e.target.querySelector('input[type=file]').files, '');
              }
              // Sticky for the page's lifetime — first PDF upload shows the
              // warning modal; subsequent ones skip straight to conversion.
              // Reset on reload, which is the right scope for "this session".
              var pdfWarningAcknowledged = false;
              async function uploadFiles(files, folder) {
                var pin = await ensurePin();
                if (pin == null) return;
                if (!files.length) return;
                var arr = Array.prototype.slice.call(files);
                var hasPdf = arr.some(function(f){return /\.pdf$/i.test(f.name);});
                if (!(await checkPin(pin))) return;
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
                  if (!resp.ok) {
                    var detail = await resp.text();
                    alert(resp.status === 403 ? 'Upload failed: wrong PIN.' : 'Upload stopped: ' + (detail || resp.status));
                    return;
                  }
                } catch(err) { alert('Upload error: ' + err); return; }
                location.href = resp.redirected ? resp.url : '/';
              }
              async function checkPin(pin) {
                if (!pin) pin = await ensurePin();
                if (!pin) return false;
                try {
                  var resp = await fetch('/pin-check?pin=' + encodeURIComponent(pin), {method:'GET'});
                  if (resp.ok) {
                    storePin(pin);
                    return true;
                  }
                  alert(resp.status === 403 ? 'Wrong PIN. Check the watch and try again.' : 'PIN check failed: ' + resp.status);
                  return false;
                } catch (err) {
                  alert('PIN check failed: ' + err);
                  return false;
                }
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
              function showInstructions() {
                var modal = document.getElementById('help-modal');
                modal.classList.add('show');
              }
              function closeInstructions() {
                var modal = document.getElementById('help-modal');
                modal.classList.remove('show');
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
                var pin = document.getElementById('pin');
                pin.value = sessionStorage.getItem('wbooksPin') || '';
                pin.addEventListener('input', function(){ sessionStorage.setItem('wbooksPin', pin.value.trim()); });
                document.getElementById('pin-modal-cancel').addEventListener('click', function(){ closePinModal(null); });
                document.getElementById('pin-modal-ok').addEventListener('click', function(){ closePinModal((document.getElementById('pin-modal-input').value || '').trim()); });
                document.getElementById('pin-modal-input').addEventListener('keydown', function(e) {
                  if (e.key === 'Enter') closePinModal((e.target.value || '').trim());
                  if (e.key === 'Escape') closePinModal(null);
                });
                var picker = document.querySelector('.file-picker');
                if (picker) {
                  picker.addEventListener('dragover', function(e){ e.preventDefault(); picker.classList.add('drag-over'); });
                  picker.addEventListener('dragleave', function(){ picker.classList.remove('drag-over'); });
                  picker.addEventListener('drop', function(e){
                    if (!e.dataTransfer || !e.dataTransfer.files.length) return;
                    e.preventDefault();
                    picker.classList.remove('drag-over');
                    uploadFiles(e.dataTransfer.files, '');
                  });
                }
              }
              window.addEventListener('DOMContentLoaded', installDropZones);
              var serverDisabledMessageShown = false;
              async function checkServerStillRunning() {
                if (serverDisabledMessageShown) return;
                try {
                  var resp = await fetch('/health', {cache:'no-store'});
                  if (resp.ok) return;
                } catch (e) {
                  // The watch closed the server, Wi-Fi dropped, or the page is stale.
                }
                serverDisabledMessageShown = true;
                alert('Webserver has been disabled');
              }
              window.addEventListener('DOMContentLoaded', function() {
                setInterval(checkServerStillRunning, 5000);
              });
              async function renameFolder(oldName) {
                var pin = await ensurePin();
                if (pin == null) return;
                var newName = prompt('Rename folder "' + oldName + '" to:', oldName);
                if (newName == null) return;
                newName = newName.trim();
                if (!newName || newName === oldName) return;
                var fd = new FormData();
                fd.append('from', oldName);
                fd.append('to', newName);
                fetch('/rename?pin=' + encodeURIComponent(pin), {method:'POST', body: fd})
                  .then(function(r){ if (!r.ok) alert('Rename failed: ' + r.status); location.href='/'; })
                  .catch(function(e){ alert('Rename error: ' + e); });
              }
              async function moveBook(rel, currentFolder) {
                var pin = await ensurePin();
                if (pin == null) return;
                var dest = prompt('Move "' + rel + '" to folder. Leave blank for Root:', currentFolder || '');
                if (dest == null) return;
                dest = dest.trim();
                if (dest === currentFolder) return;
                var fd = new FormData();
                fd.append('from', rel);
                fd.append('to', dest);
                try {
                  var resp = await fetch('/move?pin=' + encodeURIComponent(pin), {method:'POST', body: fd});
                  if (!resp.ok) {
                    var detail = await resp.text();
                    alert('Move stopped: ' + (detail || resp.status));
                    return;
                  }
                  location.href = resp.redirected ? resp.url : '/';
                } catch (err) {
                  alert('Move failed: ' + err);
                }
              }
              function setColor(value) {
                document.getElementById('textColorArgb').value = value;
                document.querySelectorAll('.swatch').forEach(function(s) {
                  s.classList.toggle('selected', s.dataset.value === value);
                });
                submitSettings(document.getElementById('settings-form'));
              }
              function submitSettings(form) {
                if (!attachPin(form)) return false;
                form.submit();
                return false;
              }
            </script>
            </head><body style="$bodyStyle">
            <main class="page">
              <section class="hero">
                <div class="brand">
                  <h1>wBooks web interface</h1>
                  <p>Send books to the watch, organize folders, and tune reader settings from this browser.</p>
                </div>
                <div class="pin-card">
                  <label for="pin">Watch PIN</label>
                  <div class="pin-row">
                    <input id="pin" type="password" autocomplete="off" inputmode="numeric" placeholder="Shown on watch">
                    <button type="button" onclick="checkPin(pinValue())">Check</button>
                    <button type="button" onclick="showInstructions()" title="How to use this interface">?</button>
                  </div>
                  <span class="note">Required for uploads, moves, deletes, folders, and settings.</span>
                </div>
              </section>
              $flashHtml
              <section class="workspace">
                <aside class="upload-card">
                  <h2>Add books</h2>
                  <form method="post" action="/upload" enctype="multipart/form-data" class="upload-form" onsubmit="submitUpload(event)">
                    <label class="file-picker">Choose or drop files<input type="file" name="file" multiple accept=".epub,.txt,.fb2,.html,.htm,.xhtml,.docx,.odt,.pdf,application/pdf"></label>
                    <button class="primary">Upload to watch</button>
                  </form>
                  <form method="post" action="/mkdir" class="upload-form" onsubmit="return attachPin(this)">
                    <label>New folder<input type="text" name="name" required maxlength="${FolderPolicy.MAX_NAME_LENGTH}" placeholder="Folder name"></label>
                    <button>Create folder</button>
                    <p class="note">Up to ${FolderPolicy.MAX_FOLDERS} top-level folders, ${FolderPolicy.MAX_NAME_LENGTH} characters each.</p>
                  </form>
                </aside>
                <section class="library">
                  <div class="library-top">
                    <div>
                      <h2>Library</h2>
                      <p class="note">${rootBooks.size + topFolders.sumOf { dir -> allEntries.count { it.isFile && it.parentFile?.canonicalPath == dir.canonicalPath && BookFormat.fromExtension(it.extension) != null } }} books across ${topFolders.size + 1} locations</p>
                    </div>
                    <p class="drop-copy">Drop files on any folder card to upload there.</p>
                  </div>
                  $library
                </section>
              </section>
              $settingsHtml
            </main>
            <div id="pdf-modal" class="modal" role="dialog" aria-modal="true">
              <div class="modal-card">
                <h3>Experimental: convert PDF</h3>
                <p><strong>PDFs are converted to HTML in this browser before upload. Scanned PDFs need OCR elsewhere.</strong></p>
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
            <div id="pin-modal" class="modal" role="dialog" aria-modal="true">
              <div class="modal-card">
                <h3>Enter watch PIN</h3>
                <p>The PIN is shown on the watch while the web interface is running.</p>
                <input id="pin-modal-input" type="password" autocomplete="off" inputmode="numeric" placeholder="Watch PIN">
                <p id="pin-modal-error" class="pdf-note" role="status"></p>
                <div class="modal-actions">
                  <button type="button" id="pin-modal-cancel">Cancel</button>
                  <button type="button" id="pin-modal-ok" class="primary">Continue</button>
                </div>
              </div>
            </div>
            <div id="help-modal" class="modal" role="dialog" aria-modal="true">
              <div class="modal-card" style="max-width: 620px; max-height: 80vh; overflow-y: auto;">
                <h3>How to use the web interface</h3>
                <p><strong>Get the PIN:</strong> Enable "File transfer" in watch Settings. The PIN appears both on the watch and in this card above.</p>

                <p><strong>Upload books:</strong> Choose files or drag them onto the "Choose or drop files" box. Click "Upload to watch" to place them in Root. Drag files onto a folder card to upload directly into that folder.</p>

                <p><strong>Supported formats:</strong> EPUB, TXT, FB2, HTML, DOCX, ODT, and PDF. All files are sent to the watch as soon as you upload—no separate sync step needed.</p>

                <p><strong>PDF conversion:</strong> PDFs are automatically converted to HTML in your browser using PDF.js (served from the watch—no internet required). The first PDF in a session shows a warning; subsequent ones convert silently.</p>

                <p><strong>Create folders:</strong> Type a folder name in the "New folder" field and click "Create folder". Books appear in Root until you move them. Folders are top-level only; you can have up to ${FolderPolicy.MAX_FOLDERS} folders, and each folder name can be up to ${FolderPolicy.MAX_NAME_LENGTH} characters.</p>

                <p><strong>Organize books:</strong> Drag book cards onto folder section headers to move them. The watch syncs the changes automatically.</p>

                <p><strong>Delete:</strong> Click the delete button on a book card or folder header. You'll be asked to confirm.</p>

                <p><strong>Rename folders:</strong> Folders can be renamed from the phone companion app or watch Settings. Folder names cannot contain path or reserved filesystem characters. The web interface shows live updates.</p>

                <p><strong>Adjust watch settings:</strong> Scroll down to "Settings" below the library. All changes sync to the watch immediately.</p>

                <p><strong>Touch-first design:</strong> The watch app works entirely with touch. If your watch has a rotary bezel or crown, you can use it to scroll—it's optional.</p>

                <p><strong>No internet needed:</strong> This interface runs on your local network. PDF.js and all book data stay on the watch.</p>

                <div class="modal-actions">
                  <button type="button" onclick="closeInstructions()">Close</button>
                </div>
              </div>
            </div>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun renderPinGate(flash: String): Response {
        val flashHtml = if (flash.isNotEmpty())
            """<p class="flash" role="status">${htmlEscape(flash)}</p>""" else ""
        val html = """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>wBooks web interface</title>
            <style>
              :root{--panel:#fffdf8;--ink:#211d18;--muted:#756b5e;--line:#ded2bf;--accent:#b35318;--danger:#a83232;--shadow:0 14px 36px rgba(52,37,20,0.14)}
              *{box-sizing:border-box}
              body{margin:0;min-height:100vh;display:grid;place-items:center;padding:20px;background:radial-gradient(circle at top left,#fff8e8 0,#f3f0e8 42%,#ebe7de 100%);font-family:system-ui,sans-serif;color:var(--ink)}
              .gate{width:min(420px,100%);background:var(--panel);border:1px solid var(--line);border-radius:8px;box-shadow:var(--shadow);padding:24px}
              h1{margin:0 0 8px;font-size:1.8rem;line-height:1.1}
              p{margin:0 0 18px;color:var(--muted)}
              label{display:block;font-weight:800;margin-bottom:8px}
              input{width:100%;border:1px solid var(--line);border-radius:6px;background:#fff;padding:12px;color:var(--ink);font-size:1rem}
              button{width:100%;border:1px solid var(--accent);border-radius:6px;background:var(--accent);color:#fff;padding:11px 12px;font-weight:800;cursor:pointer;margin-top:12px}
              .error{display:none;color:var(--danger);font-weight:700;margin-top:12px}
              .flash{background:#e8f5e9;border:1px solid #9fcca2;border-radius:8px;padding:10px 12px;margin:0 0 16px;color:var(--ink)}
              @media (prefers-color-scheme: dark) {
                :root{--panel:#191919;--ink:#eee;--muted:#b8b8b8;--line:#3b3b3b;--accent:#df7f34;--shadow:0 14px 36px rgba(0,0,0,.35)}
                body{background:linear-gradient(135deg,#101010,#1c1a17 55%,#111)}
                input{background:#222;color:#eee;border-color:#555}
                .flash{background:#18351d;border-color:#426b45}
              }
            </style>
            <script>
              async function unlock(e) {
                e.preventDefault();
                var pin = (document.getElementById('pin').value || '').trim();
                var err = document.getElementById('error');
                err.style.display = 'none';
                if (!pin) {
                  err.textContent = 'Enter the PIN shown on the watch.';
                  err.style.display = 'block';
                  return false;
                }
                try {
                  var resp = await fetch('/pin-check?pin=' + encodeURIComponent(pin), {method:'GET'});
                  if (!resp.ok) {
                    err.textContent = resp.status === 403 ? 'Wrong PIN. Check the watch and try again.' : 'PIN check failed: ' + resp.status;
                    err.style.display = 'block';
                    return false;
                  }
                  sessionStorage.setItem('wbooksPin', pin);
                  location.href = '/';
                } catch (ex) {
                  err.textContent = 'PIN check failed: ' + ex;
                  err.style.display = 'block';
                }
                return false;
              }
              window.addEventListener('DOMContentLoaded', function() {
                var pin = sessionStorage.getItem('wbooksPin') || '';
                if (pin) document.getElementById('pin').value = pin;
                document.getElementById('pin').focus();
              });
            </script>
            </head><body>
              <main class="gate">
                <h1>wBooks web interface</h1>
                <p>Enter the PIN shown on the watch to load the library and settings.</p>
                $flashHtml
                <form onsubmit="return unlock(event)">
                  <label for="pin">Watch PIN</label>
                  <input id="pin" type="password" autocomplete="off" inputmode="numeric" placeholder="4-digit PIN">
                  <button type="submit">Unlock</button>
                  <p id="error" class="error" role="alert"></p>
                </form>
              </main>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun handleUpload(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        gatePin(session)?.let { return it }
        // NanoHTTPD writes uploaded files to temp paths and populates this map keyed by the form field.
        // The JS upload function uses unique field names (file0, file1, ...) so all files survive in
        // this map even when multiple are uploaded at once. Runs only after PIN passes so
        // unauthenticated peers can't fill storage by spamming uploads.
        val tempFiles = HashMap<String, String>()
        session.parseBody(tempFiles)
        val params = session.parameters

        val existingFolders = currentTopFolderNames()
        val folderValidation = FolderPolicy.validateName(params["folder"]?.firstOrNull().orEmpty(), allowRoot = true)
        val requestedFolder = folderValidation.name ?: return badRequest(folderValidation.error ?: "invalid folder")
        val existingFolder = existingFolders.firstOrNull { it.equals(requestedFolder, ignoreCase = true) }
        val folder = existingFolder ?: requestedFolder
        if (folder.isNotEmpty() && existingFolder == null) {
            val createValidation = FolderPolicy.validateCreate(folder, existingFolders)
            if (!createValidation.isValid) return badRequest(createValidation.error ?: "invalid folder")
        }
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
            if (!dest.isInsideBooksDir() || dest.canonicalFile == booksDir.canonicalFile) continue
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
        val validation = FolderPolicy.validateCreate(params["name"]?.firstOrNull().orEmpty(), currentTopFolderNames())
        val name = validation.name ?: return badRequest(validation.error ?: "invalid folder")
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
        val validation = FolderPolicy.validateMoveTarget(params["to"]?.firstOrNull().orEmpty(), currentTopFolderNames())
        val toFolder = validation.name ?: return badRequest(validation.error ?: "invalid folder")
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

    private fun handleRename(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        gatePin(session)?.let { return it }
        val params = parsedForm(session)
        val from = params["from"]?.firstOrNull().orEmpty()
        val validation = FolderPolicy.validateRename(from, params["to"]?.firstOrNull().orEmpty(), currentTopFolderNames())
        val to = validation.name ?: return badRequest(validation.error ?: "invalid folder")
        if (from.isBlank() || to.isEmpty()) return badRequest("from and to required")
        if (from == to) return redirectToIndex("Renamed")
        val src = File(booksDir, from)
        val dest = File(booksDir, to)
        if (!src.isInsideBooksDir() || !dest.isInsideBooksDir() || src.isBooksRoot() ||
            !src.isDirectory || dest.exists()) {
            return badRequest("invalid rename target")
        }
        if (!src.renameTo(dest)) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Rename failed")
        onFolderRenamed(from, to)
        return redirectToIndex("Renamed $from -> $to")
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
                    textSizeSp = intParam(params, "textSizeSp", current.textSizeSp)
                        .coerceIn(ReaderSettings.TEXT_SIZE_RANGE),
                    sentenceTextSizeSp = intParam(params, "sentenceTextSizeSp", current.sentenceTextSizeSp)
                        .coerceIn(ReaderSettings.SENTENCE_TEXT_SIZE_RANGE),
                    textColorArgb = intParam(params, "textColorArgb", current.textColorArgb),
                    autoscrollEnabled = params["autoscrollEnabled"]?.firstOrNull() == "on",
                    autoscrollSpeed = intParam(params, "autoscrollSpeed", current.autoscrollSpeed)
                        .coerceIn(ReaderSettings.AUTOSCROLL_SPEED_RANGE),
                    speedreadWpm = intParam(params, "speedreadWpm", current.speedreadWpm)
                        .coerceIn(ReaderSettings.WPM_RANGE),
                    screenBrightness = intParam(params, "screenBrightness", current.screenBrightness)
                        .coerceIn(ReaderSettings.SCREEN_BRIGHTNESS_RANGE),
                    keepAwakeMinutes = intParam(params, "keepAwakeMinutes", current.keepAwakeMinutes)
                        .coerceIn(ReaderSettings.KEEP_AWAKE_MINUTES_RANGE),
                )
            }
        }
        crashReportingPref.setEnabled(params["crashReportingEnabled"]?.firstOrNull() == "on")
        return redirectToIndex("Settings saved")
    }

    // --- helpers ---

    private fun handlePinCheck(session: IHTTPSession): Response {
        if (session.method != Method.GET) return methodNotAllowed()
        gatePin(session)?.let { return it }
        val resp = newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
        resp.addHeader("Set-Cookie", "$WEB_PIN_COOKIE=${URLEncoder.encode(pin, "UTF-8")}; Path=/; HttpOnly; SameSite=Strict")
        return resp
    }

    private fun bookCard(
        book: File,
        rel: String,
        relEsc: String,
        size: String,
        currentFolder: String,
    ): String {
        val title = htmlEscape(book.name)
        return """
            <article class="book-card">
              <div>
                <span class="book-title">$title</span>
                <span class="book-path">$relEsc - $size</span>
              </div>
              <div class="book-actions">
                <button type="button" onclick="moveBook(${jsString(rel)}, ${jsString(currentFolder)})">Move</button>
                <form method="post" action="/delete" class="inline" data-confirm="Delete $relEsc?" onsubmit="return confirmAndAttachPin(this)">
                  <input type="hidden" name="path" value="$relEsc">
                  <button class="danger">Delete</button>
                </form>
              </div>
            </article>
        """.trimIndent()
    }

    private fun renderSettingsPanel(s: ReaderSettings): String {
        val crash = crashReportingPref.enabled.value
        val swatches = ReaderSettings.TEXT_COLOR_PALETTE.joinToString("") { color ->
            val value = color.toString()
            val selected = if (color == s.textColorArgb) " selected" else ""
            """<button type="button" class="swatch$selected" data-value="$value" style="background:${argbCss(color)}" title="${htmlEscape(colorName(color))} ${argbHex(color)}" aria-label="${htmlEscape(colorName(color))}" onclick="setColor('$value')"></button>"""
        }
        return """
            <section class="settings">
              <h2>Watch settings</h2>
              <p class="note">These controls read from and save to the watch. The watch remains authoritative.</p>
              <form id="settings-form" method="post" action="/settings" onsubmit="return attachPin(this)">
                <div class="settings-grid">
                  ${selectSetting("Reading mode", "mode", ReadingMode.entries.map { it.name }, s.mode.name)}
                  ${fontSetting(s.font)}
                  ${numberSetting("Text size", "textSizeSp", s.textSizeSp, ReaderSettings.TEXT_SIZE_RANGE)}
                  ${numberSetting("Sentence text size", "sentenceTextSizeSp", s.sentenceTextSizeSp, ReaderSettings.SENTENCE_TEXT_SIZE_RANGE)}
                  <div class="setting">
                    <label>Text color</label>
                    <input id="textColorArgb" type="hidden" name="textColorArgb" value="${s.textColorArgb}">
                    <div class="swatches">$swatches</div>
                  </div>
                  ${numberSetting("Autoscroll speed", "autoscrollSpeed", s.autoscrollSpeed, ReaderSettings.AUTOSCROLL_SPEED_RANGE)}
                  ${numberSetting("Speed-read WPM", "speedreadWpm", s.speedreadWpm, ReaderSettings.WPM_RANGE)}
                  ${numberSetting("Screen brightness", "screenBrightness", s.screenBrightness, ReaderSettings.SCREEN_BRIGHTNESS_RANGE, "%")}
                  ${numberSetting("Keep awake (min)", "keepAwakeMinutes", s.keepAwakeMinutes, ReaderSettings.KEEP_AWAKE_MINUTES_RANGE)}
                  ${checkboxSetting("Autoscroll", "autoscrollEnabled", s.autoscrollEnabled)}
                  ${checkboxSetting("Crash reports", "crashReportingEnabled", crash)}
                </div>
                <p><button>Save settings</button></p>
              </form>
            </section>
            ${renderInstructionsPanel()}
            ${renderChangelogPanel()}
            ${renderAboutPanel()}
        """.trimIndent()
    }

    private fun renderInstructionsPanel(): String = """
        <section class="settings">
          <h2>How to use</h2>
          <section>
            <h3>Opening a book</h3>
            <p>Swipe right to Search. Tap a book to open it. Swipe left to return to the library.</p>
          </section>
          <section>
            <h3>Reading modes</h3>
            <p><strong>Normal:</strong> Tap to page through. Swipe up/down for finer control.</p>
            <p><strong>Speed Reading:</strong> Tap to advance word by word. Adjust WPM in settings.</p>
            <p><strong>Sentence:</strong> One sentence at a time. Instant navigation.</p>
          </section>
          <section>
            <h3>Navigation</h3>
            <p>Swipe left while reading to open Tools page. Tap a chapter heading to jump instantly.</p>
            <p>Check reading time and see chapter progress at the top.</p>
          </section>
          <section>
            <h3>Bookmarks</h3>
            <p>Tap the bookmark icon (top right) while reading to save your position. View all bookmarks on the Tools page.</p>
          </section>
          <section>
            <h3>Settings</h3>
            <p>Swipe left from library to Settings. Adjust text size, color, reading mode, and autoscroll speed.</p>
            <p>Enable "Keep awake" to prevent the screen from dimming.</p>
          </section>
          <section>
            <h3>File transfer</h3>
            <p>Open Settings, toggle "File transfer" on. Use the phone companion app or visit the web address shown (from any browser on your network) to upload books.</p>
            <p>PDF files are converted to HTML automatically.</p>
          </section>
          <section>
            <h3>Folders</h3>
            <p>Create folders from the library, then long-press a book to move it. Use the companion app or web UI to drag books between folders.</p>
            <p>Folders are top-level only. You can have up to ${FolderPolicy.MAX_FOLDERS} folders, and each folder name can be up to ${FolderPolicy.MAX_NAME_LENGTH} characters. Names cannot contain path or reserved filesystem characters.</p>
          </section>
          <section>
            <h3>Touch-first design</h3>
            <p>Every feature works with touch. If your watch has a rotary bezel or crown, use it to scroll - it's optional and just speeds up navigation.</p>
          </section>
        </section>
    """.trimIndent()

    private fun renderChangelogPanel(): String {
        val items = com.fredapp.wbooks.data.changelog.CHANGELOG.joinToString("") { entry ->
            val notes = entry.notes.joinToString("") { "<li>${htmlEscape(it)}</li>" }
            """<section><h3>${htmlEscape(entry.version)} - ${htmlEscape(entry.date)}</h3><ul>$notes</ul></section>"""
        }
        return """
            <section class="settings">
              <h2>Changelog</h2>
              $items
            </section>
        """.trimIndent()
    }

    private fun renderAboutPanel(): String = """
        <section class="settings">
          <h2>About</h2>
          <p>wBooks - Wear OS ebook reader.</p>
          <p>Reads epub, txt, fb2, html, docx, and odt. The web interface can convert text-based PDFs to HTML before upload.</p>
          <p>This web interface runs on the watch over your local Wi-Fi; the URL and PIN are shown on the watch screen.</p>
          <p>Source: <a href="https://github.com/FredApps/wBooks">github.com/FredApps/wBooks</a> - GPLv3.</p>
          <p>Bundled Gutenberg texts are public domain in the United States; check your jurisdiction.</p>
        </section>
    """.trimIndent()

    private fun selectSetting(label: String, name: String, options: List<String>, selected: String): String {
        val optionHtml = options.joinToString("") { value ->
            val sel = if (value == selected) " selected" else ""
            """<option value="$value"$sel>${htmlEscape(value.lowercase().replaceFirstChar { it.titlecase() })}</option>"""
        }
        return """<div class="setting"><label>$label</label><select name="$name" onchange="submitSettings(this.form)">$optionHtml</select></div>"""
    }

    private fun fontSetting(selected: FontChoice): String {
        val optionHtml = FontChoice.entries.joinToString("") { value ->
            val sel = if (value == selected) " selected" else ""
            """<option value="${value.name}"$sel style="font-family:${webFontCss(value)},system-ui,sans-serif">${htmlEscape(value.familyName)}</option>"""
        }
        return """<div class="setting"><label>Font</label><select name="font" onchange="submitSettings(this.form)" style="font-family:${webFontCss(selected)},system-ui,sans-serif">$optionHtml</select></div>"""
    }

    private fun numberSetting(label: String, name: String, value: Int, range: IntRange, suffix: String = ""): String =
        """<div class="setting"><label>$label</label><input type="number" name="$name" value="$value" min="${range.first}" max="${range.last}" onchange="submitSettings(this.form)"><small>${range.first}-${range.last}$suffix</small></div>"""

    private fun checkboxSetting(label: String, name: String, checked: Boolean): String {
        val attr = if (checked) " checked" else ""
        return """<div class="setting checkbox-row"><input type="checkbox" name="$name"$attr onchange="submitSettings(this.form)"><label>$label</label></div>"""
    }

    private fun webFontCss(font: FontChoice): String = when (font) {
        FontChoice.DEFAULT -> "system-ui"
        FontChoice.SERIF -> "Georgia,serif"
        FontChoice.SANS -> "Arial"
        FontChoice.MONO -> "\"Courier New\",monospace"
        FontChoice.CURSIVE -> "cursive"
    }

    private inline fun <reified E : Enum<E>> enumParam(params: Map<String, List<String>>, name: String, fallback: E): E =
        params[name]?.firstOrNull()?.let { raw -> runCatching { enumValueOf<E>(raw) }.getOrNull() } ?: fallback

    private fun intParam(params: Map<String, List<String>>, name: String, fallback: Int): Int =
        params[name]?.firstOrNull()?.toIntOrNull() ?: fallback

    private fun currentTopFolderNames(): List<String> =
        booksDir.listFiles { f -> f.isDirectory }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    private fun argbCss(argb: Int): String = "#%06X".format(argb and 0x00FFFFFF)

    private fun argbHex(argb: Int): String = "#%08X".format(argb)

    private fun colorName(argb: Int): String = when (argb) {
        0xFFD4C19C.toInt() -> "Sepia"
        0xFFFFFFFF.toInt() -> "Cold white"
        0xFFB0B0B0.toInt() -> "Grey"
        0xFFE8E6E1.toInt() -> "Warm white"
        0xFF9CB5D4.toInt() -> "Pale blue"
        0xFFA8D49C.toInt() -> "Pale green"
        0xFFD49C9C.toInt() -> "Pale red"
        else -> "Custom"
    }

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
     * doesn't. PIN comes from the query string or the web-interface cookie so
     * this can run *before* parseBody;
     * the comparison is constant-time and a sliding-window counter rate-limits
     * brute force against the 10k-combo PIN space.
     */
    private fun gatePin(session: IHTTPSession): Response? {
        val supplied = pinFromRequest(session).toByteArray(Charsets.UTF_8)
        if (MessageDigest.isEqual(supplied, pinBytes)) return null
        val resp = if (recordFailedAttempt()) {
            newFixedLengthResponse(TOO_MANY_REQUESTS, "text/plain", "Too many bad PIN attempts; restart the server from the watch")
                .also { it.addHeader("Retry-After", (PIN_WINDOW_MS / 1000).toString()) }
        } else {
            forbidden("bad PIN")
        }
        // Clear any stale cookie so a browser doesn't keep hammering with a dead PIN.
        resp.addHeader("Set-Cookie", "$WEB_PIN_COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")
        return resp
    }

    private fun hasValidPin(session: IHTTPSession): Boolean =
        MessageDigest.isEqual(pinFromRequest(session).toByteArray(Charsets.UTF_8), pinBytes)

    private fun pinFromRequest(session: IHTTPSession): String =
        queryParam(session, "pin").ifEmpty { cookieParam(session, WEB_PIN_COOKIE) }

    private fun cookieParam(session: IHTTPSession, name: String): String {
        val cookie = session.headers["cookie"] ?: return ""
        for (part in cookie.split(';')) {
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            if (trimmed.substring(0, eq) == name) {
                return try {
                    URLDecoder.decode(trimmed.substring(eq + 1), "UTF-8")
                } catch (_: IllegalArgumentException) {
                    ""
                }
            }
        }
        return ""
    }

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
            // Hard cap so a throttled attacker can't grow the deque indefinitely
            // between window evictions.
            if (failedAttempts.size >= MAX_PIN_FAILURES * 4) return true
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

    private fun jsString(s: String): String {
        val out = StringBuilder(s.length + 2)
        out.append('"')
        for (c in s) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) {
                    out.append("\\u%04x".format(c.code))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
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
        redirectToIndex("Stopped: $msg")

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
        private const val WEB_PIN_COOKIE = "wbooks_pin"

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
