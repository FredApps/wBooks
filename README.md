# wBooks

An Android Wear OS ebook reader. Targets standalone watches running Wear OS 3 or later (min SDK 30). Every interaction works with touch alone; a rotating bezel or crown is treated as a bonus, never a requirement.

## Status

Feature-complete against the original spec apart from the deferred phone companion app. The library ships pre-populated with one book of each supported format (Project Gutenberg public-domain editions).

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

## Architecture

```
app/
└── src/main/kotlin/com/wbooks/
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
    │   └── cache/                    DocumentCache: ObjectOutputStream w/ fingerprint
    ├── transfer/
    │   ├── UploadServer.kt           NanoHTTPD endpoints, PIN-gated
    │   ├── UploadServerService.kt    Foreground service holding the server
    │   └── TransferController.kt     App-scope state (running / url / pin)
    └── tile/
        └── ResumeTileService.kt      "Resume reading" Wear tile
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

- **txt** — Blank lines split paragraphs; UPPERCASE short lines are treated as headings.
- **html / xhtml** — Jsoup. Headings, paragraphs, dividers, `<pre><code>` blocks (with language hint from `class="language-…"`). Inline `<b>/<strong>`, `<i>/<em>`, `<u>` map to run styles.
- **epub** — ZIP walked into a `path → bytes` map; `META-INF/container.xml` → OPF rootfile; `<dc:title>`, `<dc:creator>`, `manifest`, ordered `spine`. Each spine XHTML runs through `HtmlParser`.
- **fb2** — Jsoup XML. `title-info` → title + author; `body > section` → Chapter; nested sections become level-bumped headings inline. `<emphasis>` italic, `<strong>` bold, `<empty-line/>` divider.

### PDF support: will not be implemented

PDF is a rendering format, not a content format. It encodes exact pixel positions and fonts, not logical structure (chapters, paragraphs, sections). On a small round Wear OS screen with variable text sizes and reflow, PDF either requires either shrinking to unreadable sizes or horizontal scrolling to see full lines — both worsen the reading experience. The supported formats (TXT, HTML, EPUB, FB2) preserve semantic structure and reflow naturally to any screen size. Adding PDF would contradict the app's design principle that every text should be readable at any font size on any watch.

### Storage

- Books are stored on the watch under `filesDir/books/`.
- Reader settings, per-book reading position, per-book bookmarks, and the last-opened book all live in DataStore Preferences.
- Parsed documents are cached under `cacheDir/parsed/` keyed by SHA-1 of the book id, fingerprinted by file size + mtime + schema version. Large EPUBs (Moby Dick has 1300+ XHTML chapters) reopen near-instantly after the first parse.

### Search

Search uses the platform `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` so voice input works on any Wear OS install with the standard speech recognizer (the wide majority). Results show inline on the Tools page; tapping one resets reading mode to Normal and jumps to the position.

### File transfer

Built-in HTTP upload server (NanoHTTPD) toggleable from Settings. Runs as a foreground service so it survives screen-off; persistent notification shows the URL + PIN. Unauthenticated on LAN, every mutating endpoint gated by a 4-digit PIN that regenerates per start. A phone companion app using the Wear Data Layer API is deferred to v2.

### Resume tile

`ResumeTileService` shows the title of the last-opened book and a Resume chip; tapping launches `MainActivity`, which auto-resumes via the same path that runs on cold launch. No special intent extras — the "last opened" state is shared via DataStore.

### Seed library

On first install, four Project Gutenberg public-domain editions are copied from `assets/seed-books/` into `filesDir/books/` so the library isn't empty:

- *Moby Dick* — Herman Melville (Gutenberg #2701) · EPUB
- *Pride and Prejudice* — Jane Austen (#1342) · TXT
- *The Adventures of Sherlock Holmes* — Arthur Conan Doyle (#1661) · HTML
- *The Yellow Wallpaper* — Charlotte Perkins Gilman (#1952) · FB2

A `.seed-version` marker prevents re-copying; user-deleted books stay deleted. Bumping `SEED_VERSION` in `WBooksApp` re-seeds on next launch.

## Build

Prerequisites:
- JDK 21 or later (Microsoft OpenJDK or another distribution)
- Android SDK with API level 36 (set as `compileSdk` in build configuration)

Set environment variables and build:

```powershell
$env:JAVA_HOME    = "path/to/jdk"
$env:ANDROID_HOME = "path/to/android-sdk"
.\gradlew.bat assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Install on the watch

Connect to your watch over ADB and install the built APK:

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$apk = "app/build/outputs/apk/debug/app-debug.apk"

& $adb connect <watch-ip>:<port>
& $adb install -r $apk
```

Replace `<watch-ip>:<port>` with your watch's ADB connection details (e.g., `192.168.1.100:5555`).
