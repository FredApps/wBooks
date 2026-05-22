# Changelog

All notable changes to wBooks are documented in this file.

## [Unreleased]

### Changed

- **Build config: `applicationId` no longer tracked in the repo.** Both
  `app/build.gradle.kts` and `companion/build.gradle.kts` now read
  `wbooks.applicationId` from `local.properties` via a new
  `requireLocalProperty()` helper that fails the configure phase with a
  pointer to the README when the key is missing. The literal
  `applicationId = "com.fredapp.wbooks"` line was also scrubbed from prior
  git history with `git filter-repo`; commit SHAs upstream of this entry
  were rewritten as a result.
- **README:** Crash-reporting section updated to reflect the actual
  init path — auto-init is disabled in the manifest and Sentry is brought up
  manually by `CrashReportingPref.initIfEnabled()` (gated on the
  user-facing "Crash reports" chip, which defaults to on). Local-config
  section renamed and reorganized; a forker-oriented note now explains
  that `applicationId` must be provided locally and how it differs from the
  Kotlin package name / Android namespace that remain in source.

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
