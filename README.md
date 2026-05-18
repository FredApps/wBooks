# wBooks

An Android Wear OS ebook reader. Targets standalone watches running Wear OS 3 or later (min SDK 30) — Pixel Watch, Galaxy Watch 4/5/6, Mobvoi TicWatch, Fossil Gen 6+, and so on. Every interaction works with touch alone; a rotating bezel or crown is treated as a bonus.

## Status

Feature-complete against the original spec apart from the deferred phone companion app. The library ships pre-populated with one book of each supported format (Project Gutenberg public-domain editions).

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

### Syntax colouring

Code blocks inside HTML books go through `parser/highlight/SyntaxHighlighter` — a small generic regex tokenizer (keywords + strings + numbers + comments). Language-specific keyword sets can be added without touching callers.

### Storage

- Books on disk under `filesDir/books/`.
- Reader settings, per-book reading position, per-book bookmarks, and the last-opened book all live in DataStore Preferences.
- Parsed documents are cached under `cacheDir/parsed/` keyed by SHA-1 of the book id, fingerprinted by file size + mtime + schema version. Large EPUBs (Moby Dick has 1300+ XHTML chapters) reopen near-instantly after the first parse.
- No Room: every collection is small enough that key/value is fine.

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

```powershell
$env:JAVA_HOME    = "C:\Users\Administrator\OneDrive\Projects\WatchTalk\.tools\jdk"
$env:ANDROID_HOME = "C:\Users\Administrator\OneDrive\Projects\WatchTalk\.tools\android-sdk"
.\gradlew.bat assembleDebug
```

The build output goes to `C:\GradleTmp\wbooks-build\app\build\` rather than `app/build/` inside the project. OneDrive sync intermittently locks files in `app/build/`; redirecting buildDir is in [build.gradle.kts](build.gradle.kts).

### AF_UNIX tmpdir workaround

The wrapper scripts (`gradlew` / `gradlew.bat`) redirect `TEMP` / `TMP` / `java.io.tmpdir` to `.gradle/tmp` inside the project. On the build machine, the user-profile `AppData\Local` tree has AppContainer/sandbox SIDs that let processes *bind* AF_UNIX sockets there but block *connecting* to them — Gradle dies at launch with `java.net.SocketException: Invalid argument: connect` from `sun.nio.ch.UnixDomainSockets.connect0`. The redirect moves those sockets to a project-local path the sandbox doesn't restrict. Harmless on other machines.

## Install on the watch

The development watch lives at `192.168.50.143:5555` with ADB pinned to that port.

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
& $adb connect 192.168.50.143:5555
& $adb -s 192.168.50.143:5555 install -r "C:\GradleTmp\wbooks-build\app\build\outputs\apk\debug\app-debug.apk"
```
