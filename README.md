# wBooks

An Android Wear OS ebook reader. Targets standalone watches running Wear OS 3 or later (min SDK 30). Every interaction works with touch alone; a rotating bezel or crown is treated as a bonus, never a requirement.

## Status

Feature-complete with library organization, chapter navigation, reading stats, and folder management. The library ships pre-populated with one book of each supported format (Project Gutenberg public-domain editions).

## Watch compatibility

Anything running Wear OS 3 or later should work. The list below is not exhaustive — every model that meets the runtime requirements is in scope — but it covers the watches most people are likely on.

| Watch | OS at launch | Rotary input | Notes |
| --- | --- | --- | --- |
| Google Pixel Watch / Watch 2 / Watch 3 | Wear OS 3.5 / 4 / 5 | Crown (rotates) | Crown drives the rotary modifier alongside touch. |
| Samsung Galaxy Watch 4, Watch 5, Watch 5 Pro | Wear OS 3 / 4 (One UI Watch) | Touch bezel | Bezel scroll via the touch-ring; works as bezel where the modifier expects rotary events. |
| Samsung Galaxy Watch 4 Classic, Watch 6 Classic | Wear OS 3 / 4 | Physical rotating bezel | Native rotary; the dev watch is the SM-R965F (Watch 6 Classic). |
| Samsung Galaxy Watch 6, Watch 7, Watch FE | Wear OS 4 / 5 | Touch bezel | Same as Watch 5. |
| Samsung Galaxy Watch Ultra | Wear OS 5 | Touch bezel + Quick button | Touch primary; bezel works for scrolling. |
| Mobvoi TicWatch Pro 5, Pro 5 Enduro | Wear OS 3 / 4 | Crown | Crown rotation feeds the rotary modifier. |
| Mobvoi TicWatch E3 / E-series | Wear OS 3 | Touch only | Fully supported via touch — the test case for the touch-first design. |
| OnePlus Watch 2, Watch 2R | Wear OS 4 | Two physical side buttons (no rotary) | Touch only for in-app scrolling. |
| Fossil Gen 6 (Wellness Edition) | Wear OS 3 (upgrade) | Crown | Crown rotates. |
| Skagen Falster Gen 6, Michael Kors Gen 6 | Wear OS 3 (upgrade) | Crown | Same chassis as Fossil Gen 6. |
| Xiaomi Watch 2, Watch 2 Pro | Wear OS 3 / 4 | Crown (button) | Crown is a button, not rotary; scroll by touch. |

If a watch runs Wear OS 3.0 or later (API 30+) it should install and work; this is just where coverage has been mentally tested. Watch faces with very small viewports (< 360 px round) may need text-size tweaks; the existing slider goes down to 10 sp which covers most of them.

Using a watch with a bezel is recommended but not required for smooth scrolling. There is also autoread functionality and screen scrolling available for those without it.

## Architecture

```
app/                             Watch app (Wear OS)
`-- src/main/kotlin/com/fredapp/wbooks/
    ├── MainActivity.kt               Compose host; auto-resumes last book
    ├── WBooksApp.kt                  Application class; copies seed books on first run
    ├── ui/
    │   ├── WBooksRoot.kt             Library  ←→  ReaderPager
    │   ├── theme/                    Light + dark Colors; SYSTEM follows the watch
    │   ├── library/                  Book list (landing screen)
    │   ├── reader/
    │   │   ├── ReaderPager.kt        HorizontalPager: Tools | Reader | Settings
    │   │   ├── ReaderScreen.kt       Dispatch on ReadingMode, handle Loading / Failed
    │   │   ├── NormalMode.kt         Paragraph layout, autoscroll, tap-to-page
    │   │   ├── SpeedReadMode.kt      RSVP with focal-letter highlight
    │   │   ├── SentenceMode.kt       One sentence at a time
    │   │   └── BlockRendering.kt     Shared Block → Composable mapping
    │   ├── secondary/                Page 0: search, bookmarks, chapters
    │   ├── settings/                 Page 2: font, colours, autoscroll, transfer, …
    │   ├── SearchResult.kt           Doc-wide search hit
    │   └── ReaderViewModel.kt        Single VM held by MainActivity
    ├── data/
    │   ├── book/                     Book + BookFormat
    │   ├── library/                  LibraryRepository (scans filesDir/books)
    │   ├── settings/                 ReaderSettings + DataStore-backed repo
    │   ├── position/                 BookPosition + per-book reading position
    │   ├── bookmarks/                Bookmark + per-book bookmark list
    │   └── changelog/                Hand-maintained CHANGELOG list
    ├── parser/
    │   ├── BookParser.kt             Interface + factory
    │   ├── model/                    Document / Chapter / Block / Run + Position helpers
    │   ├── TxtParser.kt              Plain text → blocks
    │   ├── HtmlParser.kt             Jsoup → blocks, with code blocks preserved
    │   ├── EpubParser.kt             ZIP → container → OPF spine → HtmlParser
    │   ├── Fb2Parser.kt              Jsoup XML over FictionBook
    │   ├── highlight/                Regex syntax colouring for <pre><code>
    │   └── cache/                    Binary DocumentCache with file fingerprint
    ├── transfer/
    │   ├── UploadServer.kt           NanoHTTPD endpoints, PIN-gated
    │   ├── UploadServerService.kt    Foreground service holding the server
    │   └── TransferController.kt     App-scope state (running / url / pin)
    └── tile/
        └── BooksTileService.kt       Combined library/resume + reading-time Wear tile

companion/                       Phone app (Android 7.0+, optional)
`-- src/main/kotlin/com/fredapp/wbooksutil/
    ├── MainActivity.kt               Book list + Gutenberg browser + stats dashboard
    ├── WatchRepository.kt            Wear Data Layer (MessageClient / ChannelClient)
    ├── GutenbergRepository.kt        OPDS search + download
    ├── SettingsScreen.kt             Watch-authoritative settings UI
    └── StatsScreen.kt                Reading statistics dashboard
```

## Design decisions

### Navigation: HorizontalPager, not custom gestures

Wear OS reserves swipe-from-left-edge for the system dismiss gesture. The app uses a three-page `HorizontalPager` (Tools | Reader | Settings, opens on the middle page) so the menus the spec calls out live on adjacent pages — and the system back-swipe at the left edge still exits the reader back to the library, which is the platform-correct behaviour.

### Input model: touch-first, bezel as bonus

The reader must work on watches without a rotating bezel or crown. Every interaction has a touch path:

| Mode | Touch | Bezel / crown (bonus) |
| --- | --- | --- |
| **Normal** | Tap = advance one page · Swipe up/down = smooth scroll (both directions) · Autoscroll, when on: tap toggles pause | Incremental scroll either direction |
| **Speed reading (RSVP)** | Tap = play / pause · When paused: `−25` / `+25` chips adjust WPM (Settings has a continuous slider) | Twist live = adjust WPM by ~5 wpm per detent |
| **Sentence** | Tap upper third = previous sentence · Tap lower two-thirds = next · Autoscroll, when on: tap toggles pause | Twist forward / back = next / previous sentence |
| **Library / Tools / Settings / Changelog / About** | Swipe to scroll, tap items | Twist to scroll on Library / Changelog / About |

The bezel doesn't unlock any features touch can't reach. Watches without one get the full app.

### Format support

- **epub** — ZIP walked into a `path → bytes` map; `META-INF/container.xml` → OPF rootfile; `<dc:title>`, `<dc:creator>`, `manifest`, ordered `spine`. Each spine XHTML runs through `HtmlParser`.
- **html / xhtml** — Jsoup. Headings, paragraphs, dividers, `<pre><code>` blocks (with language hint from `class="language-…"`). Inline `<b>/<strong>`, `<i>/<em>`, `<u>` map to run styles.
- **txt** — Blank lines split paragraphs; UPPERCASE short lines are treated as headings.
- **fb2** — Jsoup XML. `title-info` → title + author; `body > section` → Chapter; nested sections become level-bumped headings inline. `<emphasis>` italic, `<strong>` bold, `<empty-line/>` divider.
- **docx** — Office Open XML. `docProps/core.xml` → `dc:title` / `dc:creator`; `word/document.xml` → `<w:body>`. `<w:p>` with `pStyle=Heading1` starts a new chapter; `HeadingN` (N≥2) → heading block; otherwise paragraph. `<w:r>` styling: `<w:b/>`, `<w:i/>`, `<w:u/>`.
- **odt** — OpenDocument Text. `meta.xml` → title + author; `content.xml` → `<office:text>`. `<text:h text:outline-level=1>` starts a new chapter; deeper levels become heading blocks; `<text:p>` becomes a paragraph. Inline `<text:span>` styling is resolved against the document's automatic styles (`fo:font-weight`, `fo:font-style`, `style:text-underline-style`).

Unsupported elements in any format (tables, images, frames, fields, lists, drawings) are silently dropped — files that contain them still open, they just render only the prose.

### Storage

- Books are stored on the watch under `filesDir/books/`.
- Reader settings, per-book reading position, per-book bookmarks, and the last-opened book all live in DataStore Preferences.
- Parsed documents are cached under `cacheDir/parsed/` keyed by SHA-1 of the book id, fingerprinted by file size + mtime + schema version. Large EPUBs (Moby Dick has 1300+ XHTML chapters) reopen near-instantly after the first parse.

### Search

Search uses the platform `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` so voice input works on any Wear OS install with the standard speech recognizer (the wide majority). Results show inline on the Tools page; tapping one resets reading mode to Normal and jumps to the position.

### Library organization

Books can be organized into folders. Create folders, move books with drag-and-drop, and sync with the companion app:

- **On the watch**: Long-press a book → Move to folder. Folders appear at the top of the library. Tap a folder to browse its contents.
- **In the companion**: Drag-and-drop books between folders. Folder structure syncs to the watch via the Wear Data Layer. The "Utility" app on the phone provides a dedicated folder management interface.
- **Reading state**: Switching between folders or moving a book during active reading preserves your position and progress.

### Chapter navigation

Books with chapter headings (Heading 1 level in supported formats) support instant chapter jumps:

- **On the Tools page** (page 0): Chapter list shows section headings. Tap to jump instantly to that chapter.
- **Heading-aware ETA**: Tools page displays estimated reading time for the current chapter and for the entire book, aware of document structure. Estimates update as you read and adjust the text size / font.

### File transfer

wBooks is designed watch-first and works fully standalone. The watch has two optional transports for adding books:

- **LAN upload server** (NanoHTTPD, built-in). Toggleable from Settings. Runs as a foreground service so it survives screen-off; persistent notification shows the URL + PIN. Every mutating endpoint is gated by a 4-digit PIN (regenerated per start) checked before the request body is parsed; a sliding-window counter trips the endpoint after 10 wrong PINs / 60 s. Works over any local network without a phone.
- **Phone companion** (`:companion` module, separate APK). Optional, requires a paired Wear OS phone. Talks to the watch via the Wear Data Layer: `MessageClient.sendRequest` for list/delete, `ChannelClient` for streaming file uploads. The watch advertises a `wbooks_receiver` capability so the phone can discover any paired node automatically — no pairing UI, no PIN. See [Companion app](#companion-app) below.

You don't need a phone to use wBooks. The LAN server is always available on the watch itself. The companion is an alternative transport for users with a paired phone — pick whichever fits your workflow.

### Companion app (wBooks Utility)

A small Material 3 phone app (`:companion`, minSdk 24, branded as "wBooks Utility") that mirrors the watch's library and provides utilities:

**Library management:**
- Tap **+** → SAF picker → file streams to the watch via `ChannelClient`.
- Long-press on a book → delete from the watch.
- Pull-down / Refresh → re-fetch the library.
- **Folder management**: Drag-and-drop books between folders. Create folders. Folder structure syncs to the watch in real-time.
- **Project Gutenberg browser** (🔍 in the top app bar): search PG's OPDS catalogue, tap **Send** on a result to download + push to the watch in one step. Prefers EPUB, falls back to TXT.

**Reading insights:**
- **Reading-stats dashboard** (📅 in the top app bar): total / today / books-finished cards, plus a 30-day daily-minutes bar chart and a WPM-trend line chart. Data fetched from the watch via `MessageClient.sendRequest("/wbooks/stats")`.

Both APKs are required only to use the companion transport. The watch app alone is fully functional via the LAN server.

### Books tile + complication

`BooksTileService` combines reading time, the last-opened book title, and a Resume chip. The chip opens the reader, while tapping the tile background opens the library by passing `MainActivity.EXTRA_SHOW_LIBRARY`.

`ReadingTimeComplicationService` is a watch-face complication that supports `SHORT_TEXT` ("Xm") and `RANGED_VALUE` (progress toward a 30-minute daily goal). It reads from `ReadingStatsRepository`, which accumulates daily totals from the reader's `DisposableEffect` start/end pair.

### Time-to-finish estimate

`ReadingPaceRepository` keeps a per-book exponential moving average of ms-per-block-advance — every time the renderer reports a new position we feed it the inter-position interval (outliers above 60 s / below 0.5 s are dropped as idle / double-tap glitches). The Tools page shows `~12m in chapter / ~2h 40m in book` derived from remaining-block counts.

### Developer tools

Developer watch shortcuts live outside this repo so wBooks remains focused on the reader and companion apps.

### Seed library

On first install, six Project Gutenberg public-domain editions are copied from `assets/seed-books/` into `filesDir/books/` so the library isn't empty:

- *Moby Dick* — Herman Melville (Gutenberg #2701) · EPUB
- *Pride and Prejudice* — Jane Austen (#1342) · TXT
- *The Adventures of Sherlock Holmes* — Arthur Conan Doyle (#1661) · HTML
- *The Yellow Wallpaper* — Charlotte Perkins Gilman (#1952) · FB2
- *The Strange Case of Dr Jekyll and Mr Hyde* — Robert Louis Stevenson (#43) · DOCX
- *The Time Machine* — H. G. Wells (#35) · ODT

A `.seed-version` marker prevents re-copying; user-deleted books stay deleted. Bumping `SEED_VERSION` in `WBooksApp` re-seeds on next launch.

### Crash reporting (optional)

Both the watch app and phone companion include the Sentry SDK with auto-init. Crash reporting is **optional** and disabled by default.

**To enable crash reporting:**

1. Create a [Sentry](https://sentry.io) account and project
2. Add your DSN to `local.properties` (in the project root, gitignored):
   ```properties
   sentry.dsn=https://<key>@<region>.sentry.io/<project-id>
   ```
3. Rebuild:
   ```powershell
   .\gradlew.bat assembleDebug
   ```

**If DSN is missing or empty:** The SDK is included but disabled (logs a warning at startup, drops all events).

**In Sentry UI:** Events are tagged `environment=watch` (from the watch APK) and `environment=phone` (from the companion) so you can filter them separately.

## Development Setup

### Prerequisites

- **JDK 21 or later** (Microsoft OpenJDK, Temurin, or another distribution)
- **Android SDK API level 36** (set as `compileSdk`)
- **Android Studio** (2024.1+) recommended, but not required if building from the command line

### Environment variables

Set these in your shell profile or for the current session:

```powershell
# Windows (PowerShell)
$env:JAVA_HOME    = "path/to/jdk"
$env:ANDROID_HOME = "path/to/android-sdk"

# macOS / Linux (bash)
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
```

### Optional: Local configuration

Create `local.properties` in the project root (gitignored) for optional settings:

```properties
# Sentry crash reporting (optional; build works without this)
sentry.dsn=https://<key>@<region>.sentry.io/<project-id>
```

If not set, the Sentry SDK is included but no-ops (logs a warning, drops events).

### Build from command line

```powershell
# Watch app
.\gradlew.bat assembleDebug

# Phone companion app
.\gradlew.bat companion:assembleDebug
```

Output:
- Watch: `app/build/outputs/apk/debug/app-debug.apk`
- Phone: `companion/build/outputs/apk/debug/companion-debug.apk`

### IDE Setup (Android Studio)

1. Open the project root in Android Studio
2. File → Project Structure → Project Settings → JDK — select your JDK 21
3. File → Settings → SDK Manager → check API 36 is installed
4. Tools → AVD Manager — optional, for testing on emulator

### Running tests

```powershell
# Run all tests (unit + integration)
.\gradlew.bat test

# Run watch app tests only
.\gradlew.bat app:test

# Run companion app tests only
.\gradlew.bat companion:test
```

Tests cover the parser modules (DocxParser, OdtParser) and transfer protocols. Run before submitting changes.

### Emulator setup (optional)

For Wear OS testing without a physical device:

1. AVD Manager → Create Virtual Device
2. Select a Wear OS image (e.g., "Wear OS 5" on Round 390 x 390)
3. Select API 30 or later
4. Launch the emulator

Then:

```powershell
.\gradlew.bat installDebug  # Installs to the running emulator
```

The emulator is useful for quick iteration but doesn't fully replicate watch hardware (screen size, battery, screen-off behavior).

## Install on the watch

### Pairing

1. On the watch, go to **Settings** → **System** → **Developer options**. If Developer options is not visible, tap **Build number** 7 times to unlock it.
2. Enable **ADB debugging**.
3. Go to **Settings** → **System** → **About** and note the watch's IP address.
4. Connect your development machine to the same network as the watch.

### Install

Connect to your watch over ADB and install the built APK:

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$apk = "app/build/outputs/apk/debug/app-debug.apk"

& $adb connect <watch-ip>:<port>
& $adb install -r $apk
```

Replace `<watch-ip>:<port>` with your watch's ADB connection details (e.g., `192.168.1.100:5555` or `192.168.1.100:5037` depending on your watch).

## Known Issues and Limitations

### PDF support: not implemented as a native format

PDF is a rendering format, not a content format. It encodes exact pixel positions and fonts, not logical structure (chapters, paragraphs, sections). On a small round Wear OS screen with variable text sizes and reflow, native PDF requires either shrinking to unreadable sizes or horizontal scrolling to see full lines — both worsen the reading experience.

**Workaround:** If you have PDFs you want to read on wBooks, the companion app (wBooks Utility) can convert PDFs to HTML, EPUB, or TXT before uploading them to the watch. Tools like:
- Command-line: `pandoc`, `pdftotext`, `calibre`
- Web-based: PDF-to-HTML converters, e-book converters

Convert your PDF, then upload the result via the companion app's file picker or Gutenberg browser.

**Why not native support:** The supported formats (TXT, HTML, EPUB, FB2, DOCX, ODT) preserve semantic structure and reflow naturally to any screen size. Adding native PDF would contradict the app's design principle that every text should be readable at any font size on any watch.

## Support the project

If you enjoy wBooks, consider supporting development via GitHub Sponsors or Ko-fi.

## Contributing

wBooks is open to contributions. See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on reporting issues, code style, testing, and submitting pull requests.

## License

Source code is licensed under [GNU General Public License v3.0](LICENSE).

The bundled seed books are public-domain editions from Project Gutenberg. Users are solely responsible for ensuring any books they add to the library are legally obtained and used in accordance with applicable copyright laws in their jurisdiction. This app does not impose licensing restrictions on user-imported content.
