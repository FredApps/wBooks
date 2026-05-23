# Changelog

All notable changes to wBooks are documented in this file.

## [Unreleased]

- Utility Project Gutenberg opens with separate Top most popular books and Recent releases sections; submitting a search replaces them, and clearing the search brings them back.
- Utility Project Gutenberg book rows now open an info dialog, and the Utility How to use content moved from Settings to a main-window help icon.
- Utility folder list now caps at half the screen with its own scrollbar so Root stays visible below large folder sets.
- Utility Project Gutenberg home now switches between Top most popular books and Recent releases at the top of the list; recent releases show release dates when Project Gutenberg provides them.
- Utility folder list height now shrinks as folders are removed, keeping Root directly below small folder sets and pinned no lower than half-screen for large sets.
- Utility Project Gutenberg listings now show file size, downloads can be canceled from the progress bar, and interrupted watch transfers are cleaned up instead of leaving partial books.
- Utility Project Gutenberg Add is disabled for books already on the watch, while canceled adds can be retried as an overwrite.
- Web and Utility drag sorting now use the same behavior: before/after drops inside a folder, first-position drops when moving into folders, and matching How to use notes.
- Utility Project Gutenberg listings now use the same author, optional true release date, format, and file-size layout for popular books, recent releases, and search results.
- Watch Search page now includes Project Gutenberg search, popular books, and recent releases directly on the watch.
- Web and Utility library views now show watch library storage used, free space, and total disk space.
- Utility hardware Back now follows the same page navigation as the software back arrows.

## [0.6.0] — 2026-05-22

### Added

**Bezel Navigation**
- Use rotary bezel/crown to scroll in Normal reading mode (tap or bezel—your choice)
- Adjust WPM in Speed Reading mode with bezel
- Adjust Settings menu and autoscroll speed with bezel
- Bezel step is responsive to reading pace

**Keep Awake Setting**
- New "Keep awake" toggle in Settings prevents screen from turning off
- Applies to Normal and Sentence modes only; Speed Reading uses device defaults
- Synced across watch, companion (Utility), and web interface

**Bookmarks Improvements**
- Bookmarks now scoped to the reading mode they were saved in
- Inline trash-can toggle to delete (no modal—hold bookmark to delete)
- Better bookmark labels and positioning on Tools page

**In-App Instructions & Help**
- New "How to use" screen on watch Settings, Utility app, and web interface
- Platform-specific guidance for each surface
- Shows on first launch; accessible from Settings menu

**Web Interface Security**
- PIN-gated LAN web upload interface (protects uploads on untrusted networks)
- One-time PIN prompt per session; subsequent uploads skip the prompt
- Web-side PDF.js conversion maintains experimental PDF support

**Folder Management Enhanced**
- Drag-and-drop folder assignment (watch, web, Utility)
- Long-press book → Move to Folder (watch surface)
- Collapsible folder UI; empty folders visible and syncable
- Folder limits documented (max 25 folders, 100 char names)

### Changed

- **Reader round-screen layouts:** Harden sentence mode, compact UI, finer sentence splits
- **Heading-aware ETA:** Reading time estimates now account for chapter boundaries and structure
- **Instant chapter jumps:** Tap chapter heading to jump directly to that chapter
- **Tap-to-scroll step:** Halved in Normal mode for finer control
- **Cold DOCX opens:** 2–3× faster; optimized layout and parsing
- **Settings menu:** Bezel scrolling enabled; layout refined for easier navigation
- **Dark-only theme:** Removed theme toggle—watch, web, and Utility are dark by design
- **Autoscroll speed:** Now adjustable with bezel while Speed Reading is active
- **Build config:** `applicationId` no longer tracked in repo; must supply via `local.properties`
- **Sentry init:** Auto-init disabled in manifest; manual init via `CrashReportingPref.initIfEnabled()`
- **README:** Crash-reporting section, local-config, and folder management docs expanded
- **Watch helper script:** Added `rebuild-reinstall-watch.ps1` for rapid iteration during development

### Fixed

- Keep awake doesn't boot user while they're actively reading
- Rotary focus claimed correctly after menu swipes
- Search results retain current reading mode across library actions
- Bookmark delete toggle works without modal confirmation
- Folder path handling hardened against edge cases
- Legacy bookmark formats cleaned up; stale entries removed
- ETA calculations guarded for empty documents
- Reader side-page (Tools/Settings) back navigation restored
- Book opening hang detection (fail fast instead of waiting indefinitely)
- Upload server graceful shutdown (no restart on force-kill)
- Settings edits published immediately across all surfaces
- Stale watch APK installs prevented (verify freshness before install)

### Deprecated

- Legacy bookmark storage format removed

## [0.5.0] — 2026-05-21

### Added

**Experimental PDF support**
- Utility (companion) app: pick a PDF, see a warning dialog explaining the
  experimental nature, then have it converted to HTML on the phone using
  pdfbox-android before upload.
- LAN web UI: same conversion runs client-side in the browser using PDF.js
  bundled in the watch APK (`/pdfjs/`), served by the watch itself — no
  internet required.
- Heuristic formatting: bold/italic sniffed from PostScript font names;
  per-paragraph font size promotes runs to h1/h2/h3 against the document
  median.
- Converted PDFs land in the library marked `[PDF]` after the title on both
  the watch and the companion.
- The warning dialog only appears for the first PDF in a session; subsequent
  PDFs in the same Utility session or browser page convert without re-prompting.

## [0.1.0] — 2026-05-21

### Added

**Reading modes**
- Normal mode: paragraph layout, autoscroll, tap-to-page
- Speed reading (RSVP): focal-letter highlight, WPM adjustment
- Sentence mode: one sentence at a time, instant navigation

**Format support**
- EPUB (ZIP-based, with OPF spine support)
- HTML / XHTML
- Plain text (TXT)
- FictionBook 2 (FB2)
- Office Open XML (DOCX)
- OpenDocument Text (ODT)

**Library features**
- Folder organization: create folders, drag-and-drop books between folders
- Search: voice input via platform speech recognizer, full-document text search
- Bookmarks: per-book bookmark list, persistent
- Reading position: per-book reading position tracking
- Library sync: bi-directional sync with companion app

**Navigation**
- HorizontalPager: Tools | Reader | Settings (middle page on launch)
- Instant chapter navigation: jump to chapter headings via Tools page
- System back-swipe to exit reader

**Reading insights**
- Heading-aware ETA: estimated time to finish chapter and book
- Daily/30-day reading stats: reading minutes, WPM trends, books finished
- Reading pace tracking: exponential moving average per book

**Tiles and complications**
- Resume tile: last-opened book + instant resume
- Reading-time tile: today's accumulated reading minutes
- Watch-face complication: daily reading progress (SHORT_TEXT + RANGED_VALUE)

**File transfer**
- LAN upload server (NanoHTTPD): PIN-gated file upload over local network
- Phone companion app (wBooks Utility): file transfer, folder management, reading stats dashboard
- Wear Data Layer: watch-phone communication via MessageClient / ChannelClient

**Settings**
- Font size slider (10 sp to 40 sp)
- Color theme: Light, Dark, System (follows watch)
- Autoscroll speed
- File transfer server control
- Crash reporting opt-in

**Seed library**
- Six Project Gutenberg public-domain books pre-installed (one per format)
- User-deleted books stay deleted; reseedable via SEED_VERSION bump

**Input**
- Touch-first design: every feature works via touch alone
- Rotary input: bonus for watches with bezel/crown (not required)
- Voice search on any Wear OS with speech recognizer

**Performance**
- Document cache: parsed documents cached under `cacheDir/parsed/` with fingerprinting
- Quick reopen: large EPUBs reopen near-instantly after first parse
- Efficient rendering: block-based incremental layout

**Optional**
- Crash reporting via Sentry (disabled by default, opt-in via `local.properties`)

### Known limitations

- **PDF support**: not implemented as native format (rendering format, not suitable for reflow on small screens). Use companion app to convert PDFs to EPUB/HTML/TXT before uploading.
- **Unsupported elements**: tables, images, frames, fields, lists, drawings are silently dropped in supported formats
- **Network**: LAN upload requires watch and phone on same network
- **Companion**: phone app requires Wear OS 7+ on phone, paired watch with wBooks installed

### Architecture highlights

- Single-file reader architecture: one ViewModel shared across modes
- Wear Data Layer for phone-watch sync
- DataStore Preferences for settings, position, bookmarks
- Document cache with SHA-1 fingerprinting
- Extensible parser interface: BookParser + format-specific implementations
- Block-based document model: Document → Chapter → Block → Run for flexible rendering

---

**[Unreleased]**: Features in development, not yet released
**[0.1.0]**: Initial release with feature-complete reading app + companion
