# wBooks

An Android Wear OS ebook reader. Targets standalone watches (Wear OS 3+, min SDK 30) and is built primarily for the Galaxy Watch 6 Classic with its rotating bezel.

## Status

Pre-alpha scaffold. The package layout, manifest, Gradle setup and stub screens are in place; none of the reading modes, parsers or transfer mechanisms are implemented yet — they have docs and TODOs marking where the work lands.

## Architecture

```
app/
└── src/main/kotlin/com/wbooks/
    ├── MainActivity.kt               Compose host
    ├── WBooksApp.kt                  Application class
    ├── ui/
    │   ├── WBooksRoot.kt             Library  ←→  ReaderPager navigation
    │   ├── theme/                    Wear MaterialTheme colours
    │   ├── library/                  Book list (landing screen)
    │   ├── reader/
    │   │   ├── ReaderPager.kt        HorizontalPager: Tools | Reader | Settings
    │   │   ├── ReaderScreen.kt       Dispatch on ReadingMode
    │   │   ├── NormalMode.kt
    │   │   ├── SpeedReadMode.kt
    │   │   └── SentenceMode.kt
    │   ├── secondary/                Page 0: search, bookmarks, chapters
    │   └── settings/                 Page 2: font, colors, autoscroll, transfer, …
    ├── data/
    │   ├── book/                     Book + BookFormat
    │   ├── library/                  LibraryRepository (in-memory stub)
    │   └── settings/                 ReaderSettings data class
    ├── parser/
    │   ├── BookParser.kt             Interface + factory
    │   ├── model/Document.kt         Parser-neutral block/run model
    │   ├── TxtParser.kt              ✅ basic implementation
    │   ├── HtmlParser.kt             ✅ basic implementation (Jsoup)
    │   ├── EpubParser.kt             TODO
    │   ├── Fb2Parser.kt              TODO
    │   └── highlight/                Code syntax colouring for <pre><code>
    └── transfer/
        ├── UploadServer.kt           NanoHTTPD-based upload endpoint
        └── UploadServerService.kt    Foreground service wrapper
```

## Design decisions

### Navigation: HorizontalPager, not custom gestures

Wear OS reserves swipe-from-left-edge for the system dismiss gesture. The app uses a three-page `HorizontalPager` (Tools | Reader | Settings, opening on the middle page) so the menus the spec calls out live on adjacent pages — and the OS back-swipe at the left edge exits the reader back to the library, which is the platform-correct behaviour.

### Bezel-first input

Reader controls are designed around the watch's rotating bezel: page scroll in normal mode, WPM in speed reading, sentence advance in sentence mode. Touch is supported as a fallback.

### Format support

- **txt** — basic implementation. Blank lines split paragraphs; UPPERCASE short lines are treated as headings.
- **html / xhtml** — basic implementation via Jsoup. Headings, paragraphs, dividers, `<pre><code>` blocks (with language hint from `class="language-…"`). Inline `<b>/<strong>`, `<i>/<em>`, `<u>` mapped to run styles.
- **epub** — stubbed. Implementation plan: read the ZIP, parse `container.xml` → OPF, then run each spine entry through the HTML parser.
- **fb2** — stubbed. KXML2 pull parser over the FictionBook XML; inline `<emphasis>`, `<strong>`, `<code>`.

### Syntax colouring

Code blocks inside HTML/xhtml books go through `parser/highlight/SyntaxHighlighter` — a small generic regex tokenizer (keywords + strings + numbers + comments). Language-specific keyword sets can be added later without touching callers.

### Storage

- Books live on disk under `filesDir/books/`.
- Reading position, bookmarks, and reader settings will be in DataStore Preferences.
- We deliberately avoid Room for now — metadata is small enough that key/value is fine.

### File transfer

Built-in HTTP upload server (NanoHTTPD) toggleable from Settings. The server runs as a foreground service so it survives screen-off. Unauthenticated on LAN, gated by a generated PIN shown next to the URL. A phone companion app using the Wear Data Layer API is planned for v2.

## Build

```powershell
$env:JAVA_HOME = "<path-to-jdk>"
$env:ANDROID_HOME = "<path-to-android-sdk>"
.\gradlew.bat assembleDebug
```

## Install on the watch

The watch lives at `<watch-ip>:5555` with ADB pinned to that port.

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
& $adb connect <watch-ip>:5555
& $adb -s <watch-ip>:5555 install -r app\build\outputs\apk\debug\app-debug.apk
```
