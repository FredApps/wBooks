# Changelog

All notable changes to wBooks are documented in this file.

## [Unreleased]

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

- **PDF support**: not implemented (rendering format, not suitable for reflow on small screens)
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
