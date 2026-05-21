package com.fredapp.wbooks.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fredapp.wbooks.data.book.Book
import com.fredapp.wbooks.data.bookmarks.Bookmark
import com.fredapp.wbooks.data.bookmarks.BookmarksRepository
import com.fredapp.wbooks.data.library.LibraryRepository
import com.fredapp.wbooks.data.pace.ReadingPaceRepository
import com.fredapp.wbooks.data.position.BookPosition
import com.fredapp.wbooks.data.position.PositionsRepository
import com.fredapp.wbooks.data.stats.ReadingStatsRepository
import com.fredapp.wbooks.data.settings.FontChoice
import com.fredapp.wbooks.data.settings.ReaderSettings
import com.fredapp.wbooks.data.settings.ReadingMode
import com.fredapp.wbooks.data.settings.SettingsRepository
import com.fredapp.wbooks.data.settings.ThemeChoice
import com.fredapp.wbooks.data.settings.next
import com.fredapp.wbooks.data.settings.nextTextColor
import com.fredapp.wbooks.parser.cache.DocumentCache
import com.fredapp.wbooks.parser.EpubParser
import com.fredapp.wbooks.parser.model.Block
import com.fredapp.wbooks.parser.model.Document
import com.fredapp.wbooks.parser.parserFor
import com.fredapp.wbooks.transfer.TransferController
import com.fredapp.wbooks.transfer.TransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

sealed interface DocumentState {
    data object Idle : DocumentState
    data class Loading(val book: Book, val isFirstOpen: Boolean) : DocumentState
    data class Loaded(val book: Book, val doc: Document, val initialPosition: BookPosition) : DocumentState
    data class Failed(val book: Book, val message: String) : DocumentState
}

class ReaderViewModel(
    private val settingsRepo: SettingsRepository,
    private val libraryRepo: LibraryRepository,
    private val positionsRepo: PositionsRepository,
    private val bookmarksRepo: BookmarksRepository,
    private val transferController: TransferController,
    private val documentCache: DocumentCache,
    private val paceRepo: ReadingPaceRepository,
    private val statsRepo: ReadingStatsRepository,
    /** Application-level scope used for cache writes that must outlive this ViewModel. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    private var lastAdvanceMs: Long = 0L
    private var lastAdvancePosition: BookPosition? = null
    private var sessionStartMs: Long = 0L
    private var sessionFlushJob: Job? = null
    private var lastWpmSampleMs: Long = 0L

    // ---- Settings ----
    val settings: StateFlow<ReaderSettings> = settingsRepo.flow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ReaderSettings(),
    )

    // ---- Library ----
    val books: StateFlow<List<Book>> = libraryRepo.books
    val folders: StateFlow<List<String>> = libraryRepo.folders

    fun createFolder(name: String) {
        viewModelScope.launch { libraryRepo.createFolder(name) }
    }

    fun renameFolder(oldName: String, newName: String) {
        viewModelScope.launch {
            val newId = libraryRepo.renameFolder(oldName, newName) ?: return@launch
            // Books under the renamed folder change ID — migrate per-book state.
            val movedBooks = libraryRepo.books.value.filter { it.id.startsWith("$newId/") }
            for (book in movedBooks) {
                val tail = book.id.removePrefix("$newId/")
                val oldId = "$oldName/$tail"
                migrateBookState(oldId, book.id)
                val openState = _document.value
                if (openState is DocumentState.Loaded && openState.book.id == oldId) {
                    _document.value = openState.copy(book = book, initialPosition = currentPosition.value)
                }
            }
        }
    }

    fun deleteFolder(name: String) {
        viewModelScope.launch {
            val removedIds = libraryRepo.deleteFolder(name)
            for (id in removedIds) {
                if ((_document.value as? DocumentState.Loaded)?.book?.id == id) {
                    closeBook()
                    positionsRepo.setLastOpenedBookId(null)
                } else if (positionsRepo.readLastOpenedBookId() == id) {
                    positionsRepo.setLastOpenedBookId(null)
                }
                paceRepo.clear(id)
                positionsRepo.clear(id)
                bookmarksRepo.clear(id)
                documentCache.invalidate(id)
            }
        }
    }

    // ---- Currently-loaded document ----
    private val _document = MutableStateFlow<DocumentState>(DocumentState.Idle)
    val document: StateFlow<DocumentState> = _document.asStateFlow()
    private var loadJob: Job? = null

    /** Last position recorded by the renderer for the open book. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentPosition: StateFlow<BookPosition> = _document
        .flatMapLatest { state ->
            when (state) {
                is DocumentState.Loaded -> positionsRepo.positionFlow(state.book.id)
                else -> flow { emit(BookPosition.START) }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BookPosition.START,
        )

    /**
     * One-shot jumps requested by the UI (chapter list, bookmark tap, etc.).
     * The reader collects from this Channel and scrolls; emissions don't
     * replay if no collector is attached.
     */
    private val _jumps = Channel<BookPosition>(capacity = Channel.CONFLATED)
    val jumps: Flow<BookPosition> = _jumps.receiveAsFlow()
    private val _pendingNormalJump = MutableStateFlow<BookPosition?>(null)
    val pendingNormalJump: StateFlow<BookPosition?> = _pendingNormalJump.asStateFlow()

    fun jumpTo(position: BookPosition) {
        // Invalidate the pace baseline â€" the next reportPosition is the jump
        // target arriving, not a natural advance, and its near-instant delta
        // would otherwise pull the EMA artificially low.
        lastAdvancePosition = null
        viewModelScope.launch { _jumps.send(position) }
    }

    fun consumePendingNormalJump(position: BookPosition) {
        if (_pendingNormalJump.value == position) _pendingNormalJump.value = null
    }

    /** Called by the renderer when the visible block changes. Debounced upstream. */
    fun reportPosition(position: BookPosition) {
        val state = _document.value
        if (state !is DocumentState.Loaded) return
        recordAdvanceIfNeeded(state.book.id, position)
        maybeMarkFinished(state, position)
        viewModelScope.launch { positionsRepo.setPosition(state.book.id, position) }
    }

    /**
     * Count the book as "finished" the first time the user lands on the last
     * block of the last chapter. This means "reached the end at least once" â€"
     * not "actually read every word." Idempotent at the repository level so
     * scrolling back and re-reaching the end doesn't bump the counter twice.
     * RSVP zip-throughs and search-jump-to-end also trigger it, by design.
     */
    private fun maybeMarkFinished(state: DocumentState.Loaded, position: BookPosition) {
        val lastChapter = state.doc.chapters.lastIndex
        if (position.chapterIndex < lastChapter) return
        val chapter = state.doc.chapters.getOrNull(position.chapterIndex) ?: return
        if (chapter.blocks.isEmpty()) return
        if (position.blockIndex < chapter.blocks.lastIndex) return
        viewModelScope.launch { statsRepo.markFinished(state.book.id) }
    }

    /** Reader screen entry â€" start counting reading time. */
    fun startReadingSession() {
        if (sessionStartMs != 0L) return  // already running
        sessionStartMs = System.currentTimeMillis()
        // Periodic flush so an OOM kill or force-stop only loses one interval
        // worth of session time, not the whole session. Also splits sessions
        // that cross midnight onto the correct days at minute granularity.
        sessionFlushJob?.cancel()
        sessionFlushJob = viewModelScope.launch {
            while (isActive) {
                delay(SESSION_FLUSH_INTERVAL_MS)
                flushSessionPartial()
            }
        }
    }

    /** Reader screen exit / app background â€" flush accumulated time. */
    fun endReadingSession() {
        sessionFlushJob?.cancel()
        sessionFlushJob = null
        val start = sessionStartMs
        if (start == 0L) return
        sessionStartMs = 0L
        recordSessionCapped(System.currentTimeMillis() - start)
    }

    /** Mid-session flush â€" commits ms since [sessionStartMs] and rebases. */
    private fun flushSessionPartial() {
        val start = sessionStartMs
        if (start == 0L) return
        val now = System.currentTimeMillis()
        sessionStartMs = now
        recordSessionCapped(now - start)
    }

    /**
     * Cap each recorded chunk to [SESSION_FLUSH_INTERVAL_MS] Ã— 2. A larger gap
     * means the device was suspended (Doze, ambient, screen-off) â€" counting
     * suspended time as "reading" would be wrong. TODO: pause the session via
     * a real idle signal (screen-off receiver, no-reportPosition timeout).
     */
    private fun recordSessionCapped(deltaMs: Long) {
        val capped = deltaMs.coerceAtMost(SESSION_FLUSH_INTERVAL_MS * 2)
        if (capped <= 0) return
        viewModelScope.launch { statsRepo.recordSession(capped) }
    }

    /** Periodic WPM sample during RSVP, called from the speed-reading screen. */
    fun recordWpmSample(wpm: Int) {
        viewModelScope.launch { statsRepo.recordWpm(wpm) }
    }

    /**
     * Feed the inter-position interval into [paceRepo] so the Tools page can
     * compute time-to-finish. Three sources of garbage we explicitly skip:
     *
     * 1. **RSVP mode** advances position at WPM rate (â‰¤200ms per word), which
     *    would dominate the EMA used for Normal-mode ETA. Sentence mode is
     *    user-paced, so we treat it like Normal.
     * 2. **Jumps** (chapter list, bookmark, search result) set
     *    [lastAdvancePosition] to null in [jumpTo] / [openSearchResult] so the
     *    next reportPosition just re-baselines without computing a delta.
     * 3. **Non-natural advances** (chapter change, backwards, multi-block leap)
     *    are treated like jumps even if no explicit jumpTo ran â€" defends
     *    against renderer quirks. See [isNaturalAdvance].
     *
     * Remaining outliers (long pauses, double-fires) are clamped at the
     * repository level via [ReadingPaceRepository]'s MIN/MAX bounds.
     */
    private fun recordAdvanceIfNeeded(bookId: String, position: BookPosition) {
        val mode = settings.value.mode
        if (mode != ReadingMode.NORMAL && mode != ReadingMode.SENTENCE) {
            // RSVP: don't record, but update the baseline so resuming Normal
            // doesn't trigger a huge "jump" delta the moment the next page
            // advances.
            lastAdvancePosition = position
            lastAdvanceMs = System.currentTimeMillis()
            return
        }
        val now = System.currentTimeMillis()
        val prior = lastAdvancePosition
        if (prior != null && prior != position && isNaturalAdvance(prior, position)) {
            val delta = now - lastAdvanceMs
            viewModelScope.launch { paceRepo.recordAdvance(bookId, delta) }
        }
        lastAdvancePosition = position
        lastAdvanceMs = now
    }

    /** Stays in the same chapter and moves forward by exactly one block. */
    private fun isNaturalAdvance(prev: BookPosition, next: BookPosition): Boolean =
        prev.chapterIndex == next.chapterIndex && next.blockIndex - prev.blockIndex == 1

    // ---- Reading-pace ETA ----
    data class ReadingEta(val chapterMs: Long, val bookMs: Long)

    /**
     * Time-to-finish estimate based on the per-book EMA of ms-per-block-advance
     * (see [ReadingPaceRepository]) and the remaining-block counts derived from
     * the parsed Document. Null until the user has read enough blocks to trust
     * the pace estimate.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val readingEta: StateFlow<ReadingEta?> = _document
        .flatMapLatest { state ->
            if (state !is DocumentState.Loaded) flow { emit(null) }
            else combine(paceRepo.paceFlow(state.book.id), currentPosition) { pace, pos ->
                if (pace == null || !pace.isReady) null
                else computeEta(state.doc, pos, pace.msPerBlock)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    private fun computeEta(
        doc: Document,
        position: BookPosition,
        msPerBlock: Double,
    ): ReadingEta? {
        if (doc.chapters.isEmpty()) return null
        val ci = position.chapterIndex.coerceIn(0, doc.chapters.lastIndex)
        val bi = position.blockIndex.coerceAtLeast(0)
        val chapter = doc.chapters.getOrNull(ci) ?: return null

        // Remaining-in-chapter is the distance to the next chapter boundary. A
        // boundary is either the next doc.chapter or â€" for single-chapter TXT/ODF
        // parses â€" the next in-block Heading. This mirrors what chapterJumps()
        // shows in the Tools page, so the ETA's "chapter" matches the user's
        // mental model.
        var blocksRemainingInChapter = 0
        var foundHeading = false
        for (i in (bi + 1)..chapter.blocks.lastIndex) {
            blocksRemainingInChapter++
            if (chapter.blocks[i] is Block.Heading) {
                foundHeading = true
                break
            }
        }
        if (!foundHeading) {
            // No more headings in this doc.chapter; the next chapter boundary is
            // the next doc.chapter (or end of book). Count the rest of this
            // doc.chapter â€" the recipient adds whatever else it needs.
            blocksRemainingInChapter = (chapter.blocks.size - bi).coerceAtLeast(0)
        }

        var blocksRemainingInBook = (chapter.blocks.size - bi).coerceAtLeast(0)
        for (i in (ci + 1)..doc.chapters.lastIndex) {
            blocksRemainingInBook += doc.chapters[i].blocks.size
        }
        return ReadingEta(
            chapterMs = (blocksRemainingInChapter * msPerBlock).toLong(),
            bookMs = (blocksRemainingInBook * msPerBlock).toLong(),
        )
    }

    // ---- Bookmarks ----
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val bookmarks: StateFlow<List<Bookmark>> = _document
        .flatMapLatest { state ->
            when (state) {
                is DocumentState.Loaded -> bookmarksRepo.bookmarksFlow(state.book.id)
                else -> emptyFlow()
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun bookmarkHere() {
        val state = _document.value
        if (state !is DocumentState.Loaded) return
        val pos = currentPosition.value
        viewModelScope.launch {
            bookmarksRepo.add(state.book.id, Bookmark(pos, System.currentTimeMillis()))
        }
    }

    fun deleteBookmark(position: BookPosition) {
        val state = _document.value
        if (state !is DocumentState.Loaded) return
        viewModelScope.launch { bookmarksRepo.remove(state.book.id, position) }
    }

    // ---- Transfer ----
    val transferState: StateFlow<TransferState> = transferController.state
    fun startTransfer() = transferController.start()
    fun stopTransfer() = transferController.stop()

    // ---- Search ----
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    fun runSearch(query: String) {
        val state = _document.value
        if (state !is DocumentState.Loaded || query.isBlank()) return
        _searchQuery.value = query
        viewModelScope.launch {
            _searchResults.value = withContext(Dispatchers.Default) {
                searchDocument(state.doc, query)
            }
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    fun openSearchResult(result: SearchResult) {
        // Search-result open is a jump too â€" see jumpTo for why we invalidate.
        lastAdvancePosition = null
        viewModelScope.launch {
            // Search results open in Normal mode; keep the jump pending until that
            // mode is composed so the previous reader mode cannot consume it.
            settingsRepo.update { it.copy(mode = ReadingMode.NORMAL) }
            _pendingNormalJump.value = result.position
            clearSearch()
        }
    }

    private fun searchDocument(doc: Document, query: String): List<SearchResult> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<SearchResult>()
        for ((ci, chapter) in doc.chapters.withIndex()) {
            for ((bi, block) in chapter.blocks.withIndex()) {
                val text = when (block) {
                    is Block.Heading -> block.text
                    is Block.Paragraph -> block.runs.joinToString("") { it.text }
                    is Block.Code -> block.text
                    Block.Divider -> ""
                }
                if (text.isEmpty()) continue
                val idx = text.lowercase().indexOf(q)
                if (idx < 0) continue
                val start = (idx - 20).coerceAtLeast(0)
                val end = (idx + q.length + 20).coerceAtMost(text.length)
                val pre = if (start > 0) "â€¦" else ""
                val post = if (end < text.length) "â€¦" else ""
                out += SearchResult(
                    position = com.fredapp.wbooks.data.position.BookPosition(ci, bi),
                    snippet = pre + text.substring(start, end).replace(Regex("\\s+"), " ") + post,
                )
                if (out.size >= 100) return out
            }
        }
        return out
    }

    // ---- Library + document lifecycle ----
    init {
        viewModelScope.launch { libraryRepo.refresh() }
    }

    fun refreshLibrary() {
        viewModelScope.launch { libraryRepo.refresh() }
    }

    fun moveBook(bookId: String, targetFolder: String) {
        viewModelScope.launch {
            val newId = libraryRepo.move(bookId, targetFolder) ?: return@launch
            migrateBookState(bookId, newId)
            val state = _document.value
            if (state is DocumentState.Loaded && state.book.id == bookId) {
                val movedBook = libraryRepo.books.value.firstOrNull { it.id == newId } ?: return@launch
                _document.value = state.copy(book = movedBook, initialPosition = currentPosition.value)
            }
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            if (libraryRepo.delete(bookId)) {
                if ((_document.value as? DocumentState.Loaded)?.book?.id == bookId) {
                    closeBook()
                    positionsRepo.setLastOpenedBookId(null)
                } else if (positionsRepo.readLastOpenedBookId() == bookId) {
                    positionsRepo.setLastOpenedBookId(null)
                }
                paceRepo.clear(bookId)
                positionsRepo.clear(bookId)
                bookmarksRepo.clear(bookId)
                documentCache.invalidate(bookId)
            }
        }
    }

    private suspend fun migrateBookState(oldId: String, newId: String) {
        if (oldId == newId) return
        paceRepo.moveBookId(oldId, newId)
        positionsRepo.moveBookId(oldId, newId)
        bookmarksRepo.moveBookId(oldId, newId)
        statsRepo.moveBookId(oldId, newId)
        documentCache.moveBookId(oldId, newId)
    }

    fun openBook(book: Book) {
        loadJob?.cancel()
        // Reset pace baseline so the first reportPosition after the new book
        // loads doesn't compute a cross-book delta.
        lastAdvancePosition = null
        loadJob = viewModelScope.launch {
            val isFirstOpen = !positionsRepo.hasOpened(book.id)
            _document.value = DocumentState.Loading(book, isFirstOpen)
            positionsRepo.markOpened(book.id)
            positionsRepo.setLastOpenedBookId(book.id)
            val key = DocumentCache.Key(
                bookId = book.id,
                sizeBytes = book.file.length(),
                mtimeMs = book.file.lastModified(),
            )
            val result = loadDocumentResult(key, book)
            val initialPosition = withTimeoutOrNull(1_000) {
                positionsRepo.readPosition(book.id)
            } ?: BookPosition.START
            _document.value = result.fold(
                onSuccess = { DocumentState.Loaded(book, it, initialPosition) },
                onFailure = {
                    // runCatching also catches CancellationException â€" re-throw so
                    // coroutine cancellation (e.g. user navigates away mid-parse)
                    // doesn't get reported to Sentry as a parser bug.
                    if (it is kotlinx.coroutines.CancellationException &&
                        it !is kotlinx.coroutines.TimeoutCancellationException
                    ) {
                        throw it
                    }
                    val reported = if (it is kotlinx.coroutines.TimeoutCancellationException) {
                        IllegalStateException("Timed out opening ${book.id} (${book.format})", it)
                    } else {
                        it
                    }
                    io.sentry.Sentry.captureException(reported)
                    DocumentState.Failed(
                        book,
                        if (it is kotlinx.coroutines.TimeoutCancellationException) {
                            "Opening timed out"
                        } else {
                            it.message ?: it::class.simpleName ?: "unknown error"
                        },
                    )
                },
            )
        }
    }

    private suspend fun loadDocumentResult(
        key: DocumentCache.Key,
        book: Book,
    ): Result<Document> = try {
        Result.success(
            withTimeout(20_000) {
                documentCache.load(key) ?: parseBook(book).also { parsed ->
                    appScope.launch(Dispatchers.IO) {
                        runCatching { documentCache.store(key, parsed) }
                    }
                }
            }
        )
    } catch (t: Throwable) {
        Result.failure(t)
    }

    private suspend fun parseBook(book: Book): Document = withContext(Dispatchers.IO) {
        if (book.format == com.fredapp.wbooks.data.book.BookFormat.EPUB) {
            EpubParser().parse(book.file)
        } else {
            book.file.inputStream().use { parserFor(book.format).parse(it) }
        }
    }

    /**
     * Open the last book the user was reading, if any. Called from MainActivity on
     * first creation and from the Resume tile entry point. No-op if either there's
     * no last-opened id or it doesn't match anything currently in the library.
     */
    fun resumeLastBook() {
        viewModelScope.launch {
            val id = positionsRepo.readLastOpenedBookId() ?: return@launch
            // Make sure the library scan has run so books.value is populated.
            libraryRepo.refresh()
            val book = libraryRepo.books.value.firstOrNull { it.id == id } ?: return@launch
            openBook(book)
        }
    }

    fun closeBook() {
        loadJob?.cancel()
        _document.value = DocumentState.Idle
    }

    // ---- Settings edits ----
    fun cycleMode() = editSettings { it.copy(mode = it.mode.next()) }
    fun cycleFont() = editSettings { it.copy(font = it.font.next()) }
    fun cycleTextColor() = editSettings { it.copy(textColorArgb = nextTextColor(it.textColorArgb)) }
    fun cycleTheme() = editSettings { it.copy(theme = it.theme.next()) }
    fun setTheme(theme: ThemeChoice) = editSettings { it.copy(theme = theme) }
    fun toggleAutoscroll() = editSettings { it.copy(autoscrollEnabled = !it.autoscrollEnabled) }

    fun setMode(mode: ReadingMode) = editSettings { it.copy(mode = mode) }
    fun setTextColor(argb: Int) = editSettings { it.copy(textColorArgb = argb) }
    fun setTextSize(value: Int) = editSettings { it.copy(textSizeSp = value.coerceIn(ReaderSettings.TEXT_SIZE_RANGE)) }
    fun setSentenceTextSize(value: Int) = editSettings { it.copy(sentenceTextSizeSp = value.coerceIn(ReaderSettings.SENTENCE_TEXT_SIZE_RANGE)) }
    fun setAutoscrollSpeed(value: Int) = editSettings { it.copy(autoscrollSpeed = value.coerceIn(ReaderSettings.AUTOSCROLL_SPEED_RANGE)) }
    fun setScreenBrightness(value: Int) = editSettings { it.copy(screenBrightness = value.coerceIn(ReaderSettings.SCREEN_BRIGHTNESS_RANGE)) }
    fun setSpeedreadWpm(value: Int) {
        editSettings { it.copy(speedreadWpm = value.coerceIn(ReaderSettings.WPM_RANGE)) }
        // Debounced sample of the user's preferred WPM. Avoids spamming the
        // log while they fine-tune with the +/-25 chips or twist the bezel.
        val now = System.currentTimeMillis()
        if (now - lastWpmSampleMs > WPM_SAMPLE_DEBOUNCE_MS) {
            lastWpmSampleMs = now
            recordWpmSample(value.coerceIn(ReaderSettings.WPM_RANGE))
        }
    }
    fun setFont(font: FontChoice) = editSettings { it.copy(font = font) }

    private fun editSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    private companion object {
        const val WPM_SAMPLE_DEBOUNCE_MS = 5_000L
        /** Cadence for splitting a session into commit-able chunks. */
        const val SESSION_FLUSH_INTERVAL_MS = 60_000L
    }

    class Factory(
        private val settingsRepo: SettingsRepository,
        private val libraryRepo: LibraryRepository,
        private val positionsRepo: PositionsRepository,
        private val bookmarksRepo: BookmarksRepository,
        private val transferController: TransferController,
        private val documentCache: DocumentCache,
        private val paceRepo: ReadingPaceRepository,
        private val statsRepo: ReadingStatsRepository,
        private val appScope: CoroutineScope,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == ReaderViewModel::class.java)
            return ReaderViewModel(
                settingsRepo, libraryRepo, positionsRepo, bookmarksRepo, transferController,
                documentCache, paceRepo, statsRepo, appScope,
            ) as T
        }
    }
}
