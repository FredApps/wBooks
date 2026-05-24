package com.fredapp.wbooks.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fredapp.wbooks.data.book.Book
import com.fredapp.wbooks.data.book.BookFormat
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
import com.fredapp.wbooks.data.settings.next
import com.fredapp.wbooks.data.settings.nextTextColor
import com.fredapp.wbooks.parser.cache.DocumentCache
import com.fredapp.wbooks.parser.EpubParser
import com.fredapp.wbooks.parser.model.Block
import com.fredapp.wbooks.parser.model.Document
import com.fredapp.wbooks.parser.model.DocumentMetrics
import com.fredapp.wbooks.parser.model.computeDocumentMetrics
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

sealed interface DocumentState {
    data object Idle : DocumentState
    data class Loading(
        val book: Book,
        val isFirstOpen: Boolean,
        val progressPercent: Int? = null,
        val status: String? = null,
    ) : DocumentState
    data class Loaded(
        val book: Book,
        val doc: Document,
        val initialPosition: BookPosition,
        /** Pre-computed lookup tables for word/sentence counts and the chapter TOC.
         *  Built once on Dispatchers.Default when the book opens; nullable only
         *  during the brief window between the doc loading and the metrics
         *  finishing (callers should treat null as "loading"). */
        val metrics: DocumentMetrics? = null,
    ) : DocumentState
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
    private val _settings = MutableStateFlow(ReaderSettings())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.flow.collect { persisted ->
                _settings.value = persisted
            }
        }
    }

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

    /**
     * Identity of the currently-open book, or null when nothing is open.
     * Derived flows that only care about *which* book is open (not whether
     * metrics have finished computing, etc.) subscribe to this instead of
     * [_document] so they don't tear down and rebuild their DataStore
     * subscriptions when the VM swaps in metrics after the doc parses.
     */
    private val openBookIdFlow: Flow<String?> = _document
        .map { (it as? DocumentState.Loaded)?.book?.id }
        .distinctUntilChanged()

    /** Last position recorded by the renderer for the open book. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentPosition: StateFlow<BookPosition> = openBookIdFlow
        .flatMapLatest { id ->
            if (id == null) flow { emit(BookPosition.START) }
            else positionsRepo.positionFlow(id)
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

    fun jumpTo(position: BookPosition) {
        // Invalidate the pace baseline — the next reportPosition is the jump
        // target arriving, not a natural advance, and its near-instant delta
        // would otherwise pull the EMA artificially low.
        lastAdvancePosition = null
        viewModelScope.launch { _jumps.send(position) }
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
     * 3. **Non-natural advances** (chapter change, backwards, large leap)
     *    are treated like jumps even if no explicit jumpTo ran â€" defends
     *    against renderer quirks. See [naturalAdvanceBlocks].
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
        if (prior != null && prior != position) {
            val advancedBlocks = naturalAdvanceBlocks(prior, position)
            if (advancedBlocks > 0) {
                val deltaPerBlock = (now - lastAdvanceMs) / advancedBlocks
                viewModelScope.launch { paceRepo.recordAdvances(bookId, deltaPerBlock, advancedBlocks) }
            }
        }
        lastAdvancePosition = position
        lastAdvanceMs = now
    }

    /**
     * Stays in the same chapter and moves forward by a plausible scroll/page
     * amount. Normal-mode swipes can advance dozens of short paragraphs between
     * sampled position reports, especially in books like The Time Machine.
     */
    private fun naturalAdvanceBlocks(prev: BookPosition, next: BookPosition): Int {
        if (prev.chapterIndex != next.chapterIndex) return 0
        val blocks = next.blockIndex - prev.blockIndex
        return if (blocks in 1..MAX_NATURAL_ADVANCE_BLOCKS) blocks else 0
    }

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
                val metrics = state.metrics
                if (pace == null || !pace.isReady || metrics == null) null
                else computeEta(state.doc, metrics, pos, pace.msPerBlock)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /**
     * Word-based ETA built on the precomputed [DocumentMetrics]. Was previously
     * walking every block on every position change — for a 50k-block book that's
     * ~30ms of main-thread work on the watch's tiny CPU each time the user
     * swipes a page. Now it's three array reads and a couple of subtractions.
     */
    private fun computeEta(
        doc: Document,
        metrics: DocumentMetrics,
        position: BookPosition,
        msPerBlock: Double,
    ): ReadingEta? {
        if (doc.chapters.isEmpty() || msPerBlock <= 0.0) return null
        val totalBlocks = metrics.totalBlocks
        val totalWords = metrics.totalWords
        if (totalBlocks == 0 || totalWords == 0) return null

        val avgWordsPerBlock = totalWords.toDouble() / totalBlocks
        if (avgWordsPerBlock <= 0.0) return null
        val msPerWord = msPerBlock / avgWordsPerBlock
        val derivedWpm = 60_000.0 / msPerWord
        // Refuse to publish an ETA when the implied reading speed is
        // physically implausible. Fast initial paging or huge pauses produce
        // values far outside any human range — better to show "Calculating…"
        // than mislead the user with a one-minute estimate for Moby Dick.
        if (derivedWpm !in MIN_TRUSTED_WPM..MAX_TRUSTED_WPM) return null

        val ci = position.chapterIndex.coerceIn(0, doc.chapters.lastIndex)
        val bi = position.blockIndex.coerceAtLeast(0)
        val chapter = doc.chapters.getOrNull(ci) ?: return null
        val chapterRow = metrics.wordsBeforeBlock[ci]
        val safeBi = bi.coerceIn(0, chapter.blocks.size - 1)
        val wordsConsumedInChapter = chapterRow[safeBi]
        val wordsAtChapterEnd = chapterRow[chapter.blocks.size]
        val wordsRemainingInChapter = (wordsAtChapterEnd - wordsConsumedInChapter).coerceAtLeast(0)

        val wordsConsumedInBook = chapterRow[safeBi]
        val wordsRemainingInBook = (totalWords - wordsConsumedInBook).coerceAtLeast(0)

        return ReadingEta(
            chapterMs = (wordsRemainingInChapter * msPerWord).toLong(),
            bookMs = (wordsRemainingInBook * msPerWord).toLong(),
        )
    }

    // ---- Bookmarks ----
    // Bookmarks are stored in three separate buckets — one per reading mode —
    // so switching modes shows a structurally different list, not a filter of
    // a shared list. The flow re-subscribes when either the open book or the
    // current mode changes.
    /**
     * Pre-sorted newest-first so the Tools screen can render directly without
     * re-sorting in composition. distinctUntilChanged on the mode prevents
     * unrelated settings edits (font, color, brightness) from tearing down and
     * rebuilding the DataStore subscription.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val bookmarks: StateFlow<List<Bookmark>> = openBookIdFlow
        .flatMapLatest { id ->
            if (id == null) emptyFlow()
            else settings
                .map { it.mode }
                .distinctUntilChanged()
                .flatMapLatest { mode -> bookmarksRepo.bookmarksFlow(id, mode) }
        }
        .map { list -> list.sortedByDescending { it.savedAtMs } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun bookmarkHere() {
        val state = _document.value
        if (state !is DocumentState.Loaded) return
        val pos = currentPosition.value
        val mode = settings.value.mode
        viewModelScope.launch {
            bookmarksRepo.add(state.book.id, Bookmark(pos, System.currentTimeMillis(), mode = mode))
        }
    }

    fun deleteBookmark(position: BookPosition, mode: ReadingMode) {
        val state = _document.value
        if (state !is DocumentState.Loaded) return
        viewModelScope.launch { bookmarksRepo.remove(state.book.id, position, mode) }
    }

    // ---- Transfer ----
    val transferState: StateFlow<TransferState> = transferController.state
    fun startTransfer() = transferController.start()
    fun canStartTransferOnWifi() = transferController.canStartOnWifi()
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
        // Search-result open is a jump too — see jumpTo for why we invalidate.
        // Stay in the user's current reading mode (normal / sentence / speedread)
        // and let that mode's vm.jumps collector handle the scroll/scan. Every
        // reader mode already listens on the jumps channel, so we don't need to
        // force ReadingMode.NORMAL the way the previous implementation did.
        lastAdvancePosition = null
        viewModelScope.launch {
            _jumps.send(result.position)
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
                    is Block.Image -> block.alt
                    Block.Divider -> ""
                }
                if (text.isEmpty()) continue
                val idx = text.lowercase().indexOf(q)
                if (idx < 0) continue
                val start = (idx - 20).coerceAtLeast(0)
                val end = (idx + q.length + 20).coerceAtMost(text.length)
                // Use plain "..." rather than the U+2026 ellipsis: an earlier
                // copy of this file picked up a UTF-8 → cp1252 transcoding bug
                // ("â€¦") that rendered as mojibake on the watch.
                val pre = if (start > 0) "... " else ""
                val post = if (end < text.length) " ..." else ""
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

    fun renameBook(bookId: String, newTitle: String) {
        viewModelScope.launch {
            val newId = libraryRepo.renameBook(bookId, newTitle) ?: return@launch
            migrateBookState(bookId, newId)
            val state = _document.value
            if (state is DocumentState.Loaded && state.book.id == bookId) {
                val renamedBook = libraryRepo.books.value.firstOrNull { it.id == newId } ?: return@launch
                _document.value = state.copy(book = renamedBook, initialPosition = currentPosition.value)
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
            _document.value = DocumentState.Loading(
                book = book,
                isFirstOpen = isFirstOpen,
                progressPercent = 0,
                status = "Preparing",
            )
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
                onSuccess = { doc ->
                    // Show the reader immediately on the parsed doc, then back-fill
                    // metrics from Dispatchers.Default. Walking 50k blocks for word /
                    // sentence prefix-sums on the main thread would block the swap-in
                    // animation by 100-300ms on a watch CPU.
                    val loaded = DocumentState.Loaded(book, doc, initialPosition)
                    viewModelScope.launch {
                        val metrics = withContext(Dispatchers.Default) {
                            runCatching { computeDocumentMetrics(doc) }.getOrNull()
                        }
                        val current = _document.value
                        if (current is DocumentState.Loaded && current.book.id == book.id && current.doc === doc) {
                            _document.value = current.copy(metrics = metrics)
                        }
                    }
                    loaded
                },
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
            withTimeout(CACHE_LOAD_TIMEOUT_MS) {
                updateLoadingProgress(book, 5, "Checking cache")
                documentCache.load(key)
            } ?: withTimeout(COLD_PARSE_TIMEOUT_MS) {
                updateLoadingProgress(book, 10, "Parsing")
                val startedAt = System.currentTimeMillis()
                parseBook(book).also { parsed ->
                    updateLoadingProgress(book, 95, "Preparing reader")
                    Log.i(TAG, "Parsed ${book.id} (${book.format}) in ${System.currentTimeMillis() - startedAt}ms")
                    appScope.launch(Dispatchers.IO) {
                        val storeStartedAt = System.currentTimeMillis()
                        runCatching { documentCache.store(key, parsed) }
                            .onSuccess {
                                Log.i(TAG, "Cached ${book.id} in ${System.currentTimeMillis() - storeStartedAt}ms")
                            }
                            .onFailure {
                                Log.w(TAG, "Failed to cache ${book.id}", it)
                            }
                    }
                }
            }
        )
    } catch (t: Throwable) {
        Result.failure(t)
    }

    private suspend fun parseBook(book: Book): Document = withContext(Dispatchers.IO) {
        val progress: (Int) -> Unit = { percent ->
            updateLoadingProgress(book, percent, "Parsing")
        }
        if (book.format == BookFormat.EPUB) {
            EpubParser(onProgress = progress).parse(book.file)
        } else {
            book.file.inputStream().use { parserFor(book.format, onProgress = progress).parse(it) }
        }
    }

    private fun updateLoadingProgress(book: Book, percent: Int, status: String) {
        val current = _document.value as? DocumentState.Loading ?: return
        if (current.book.id != book.id) return
        _document.value = current.copy(
            progressPercent = percent.coerceIn(0, 100),
            status = status,
        )
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
    fun setKeepAwakeMinutes(value: Int) = editSettings {
        it.copy(keepAwakeMinutes = value.coerceIn(ReaderSettings.KEEP_AWAKE_MINUTES_RANGE))
    }
    fun setFont(font: FontChoice) = editSettings { it.copy(font = font) }

    // ---- Keep-awake (only meaningful in NORMAL / SENTENCE) ----
    // SPEEDREAD always wins — those modes advance the screen for the user so
    // there is no "idle" to detect.
    private val lastInteractionAt = MutableStateFlow(System.currentTimeMillis())

    /** Reset the idle timer. Wired to Activity.onUserInteraction and onResume. */
    fun noteInteraction() {
        lastInteractionAt.value = System.currentTimeMillis()
    }

    /**
     * True while the screen should be held awake: always in SPEEDREAD, and in
     * the other two modes while less than `keepAwakeMinutes` have passed since
     * the last interaction. Activity collects this to manage the window flag
     * and to call moveTaskToBack when it flips to false.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val keepAwakeActive: StateFlow<Boolean> = settings
        .flatMapLatest { s ->
            if (s.mode == ReadingMode.SPEEDREAD) flow { emit(true) }
            else lastInteractionAt.flatMapLatest { last ->
                flow {
                    val timeoutMs = s.keepAwakeMinutes.toLong() * 60_000L
                    val remaining = timeoutMs - (System.currentTimeMillis() - last)
                    if (remaining <= 0L) {
                        emit(false)
                    } else {
                        emit(true)
                        delay(remaining)
                        emit(false)
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true,
        )

    private fun editSettings(transform: (ReaderSettings) -> ReaderSettings) {
        // Any settings edit comes from the watch UI (the companion writes
        // through a different path), so it implies the user is right here and
        // active. Bumping the interaction timestamp BEFORE the settings update
        // closes a race: otherwise the new settings value can flow through
        // keepAwakeActive's flatMapLatest with a stale lastInteractionAt and
        // emit(false) → moveTaskToBack — booting the user out the moment they
        // touch a control.
        noteInteraction()
        val next = transform(_settings.value)
        _settings.value = next
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    private companion object {
        const val TAG = "ReaderViewModel"
        const val CACHE_LOAD_TIMEOUT_MS = 5_000L
        const val COLD_PARSE_TIMEOUT_MS = 120_000L
        const val WPM_SAMPLE_DEBOUNCE_MS = 5_000L
        /** Cadence for splitting a session into commit-able chunks. */
        const val SESSION_FLUSH_INTERVAL_MS = 60_000L
        const val MIN_TRUSTED_WPM = 50.0
        const val MAX_TRUSTED_WPM = 800.0
        const val MAX_NATURAL_ADVANCE_BLOCKS = 60
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
