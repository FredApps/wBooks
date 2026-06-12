package com.fredapp.wbooksutil

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fredapp.wbooks.data.book.Book
import com.fredapp.wbooks.data.book.BookFormat
import com.fredapp.wbooks.data.bookmarks.Bookmark
import com.fredapp.wbooks.data.position.BookPosition
import com.fredapp.wbooks.data.settings.FontChoice
import com.fredapp.wbooks.data.settings.ReaderSettings
import com.fredapp.wbooks.data.settings.ReadingMode
import com.fredapp.wbooks.parser.EpubParser
import com.fredapp.wbooks.parser.cache.DocumentCache
import com.fredapp.wbooks.parser.model.Block
import com.fredapp.wbooks.parser.model.Document
import com.fredapp.wbooks.parser.model.DocumentMetrics
import com.fredapp.wbooks.parser.model.computeDocumentMetrics
import com.fredapp.wbooks.parser.parserFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

sealed interface PhoneDocumentState {
    data object Idle : PhoneDocumentState
    data class Loading(val title: String, val progressPercent: Int = 0, val status: String = "Preparing") : PhoneDocumentState
    data class Loaded(
        val book: Book,
        val document: Document,
        val initialPosition: BookPosition,
        val metrics: DocumentMetrics? = null,
    ) : PhoneDocumentState
    data class Failed(val title: String, val message: String) : PhoneDocumentState
}

data class PhoneSearchResult(val position: BookPosition, val snippet: String)

class PhoneReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CompanionApp
    private val settingsRepo = app.settingsRepository
    private val libraryRepo = app.libraryRepository
    private val positionsRepo = app.positionsRepository
    private val bookmarksRepo = app.bookmarksRepository
    private val statsRepo = app.readingStatsRepository
    private val documentCache = app.documentCache
    private val watchRepo = WatchRepository(application)

    private val _settings = MutableStateFlow(ReaderSettings())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    private val _document = MutableStateFlow<PhoneDocumentState>(PhoneDocumentState.Idle)
    val document: StateFlow<PhoneDocumentState> = _document.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<PhoneSearchResult>>(emptyList())
    val searchResults: StateFlow<List<PhoneSearchResult>> = _searchResults.asStateFlow()

    private val _jumps = Channel<BookPosition>(capacity = Channel.CONFLATED)
    val jumps: Flow<BookPosition> = _jumps.receiveAsFlow()

    private var loadJob: Job? = null
    private var cacheStoreJob: Job? = null
    private var sessionStartMs: Long = 0L
    private val settingSyncJobs = mutableMapOf<String, Job>()
    private var wpmRecordJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepo.flow.collect { _settings.value = it }
        }
    }

    private val openBookIdFlow: Flow<String?> = _document
        .map { (it as? PhoneDocumentState.Loaded)?.book?.id }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentPosition: StateFlow<BookPosition> = openBookIdFlow
        .flatMapLatest { id ->
            if (id == null) emptyFlow() else positionsRepo.positionFlow(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookPosition.START)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val bookmarks: StateFlow<List<Bookmark>> = openBookIdFlow
        .flatMapLatest { id ->
            if (id == null) emptyFlow()
            else settings.flatMapLatest { s -> bookmarksRepo.bookmarksFlow(id, s.mode) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openBook(bookId: String) {
        loadJob?.cancel()
        cacheStoreJob?.cancel()
        loadJob = viewModelScope.launch {
            libraryRepo.refresh()
            val book = libraryRepo.books.value.firstOrNull { it.id == bookId }
                ?: run {
                    _document.value = PhoneDocumentState.Failed(bookId, "Book is not cached on this phone yet.")
                    return@launch
                }
            _document.value = PhoneDocumentState.Loading(book.title, 0, "Preparing")
            positionsRepo.markOpened(book.id)
            positionsRepo.setLastOpenedBookId(book.id)
            val key = DocumentCache.Key(book.id, book.file.length(), book.file.lastModified())
            val result = loadDocumentResult(key, book)
            val initialPosition = withTimeoutOrNull(1_000) {
                positionsRepo.readPosition(book.id)
            } ?: BookPosition.START
            _document.value = result.fold(
                onSuccess = { doc ->
                    val loaded = PhoneDocumentState.Loaded(book, doc, initialPosition)
                    viewModelScope.launch {
                        val metrics = withContext(Dispatchers.Default) {
                            runCatching { computeDocumentMetrics(doc) }.getOrNull()
                        }
                        val current = _document.value
                        if (current is PhoneDocumentState.Loaded && current.book.id == book.id && current.document === doc) {
                            _document.value = current.copy(metrics = metrics)
                        }
                    }
                    loaded
                },
                onFailure = { PhoneDocumentState.Failed(book.title, it.message ?: "Could not open book") },
            )
        }
    }

    fun closeBook() {
        loadJob?.cancel()
        cacheStoreJob?.cancel()
        _document.value = PhoneDocumentState.Idle
        clearSearch()
    }

    private suspend fun loadDocumentResult(key: DocumentCache.Key, book: Book): Result<Document> = try {
        updateLoading(book.title, 5, "Checking cache")
        val cached = withTimeoutOrNull(CACHE_LOAD_TIMEOUT_MS) {
            documentCache.load(key)
        }
        Result.success(
            cached ?: withTimeout(COLD_PARSE_TIMEOUT_MS) {
                updateLoading(book.title, 10, "Parsing")
                parseBook(book).also { parsed ->
                    updateLoading(book.title, 95, "Preparing reader")
                    cacheStoreJob = app.appScope.launch(Dispatchers.IO) {
                        runCatching { documentCache.store(key, parsed) }
                    }
                }
            }
        )
    } catch (t: Throwable) {
        Result.failure(t)
    }

    private suspend fun parseBook(book: Book): Document = withContext(Dispatchers.IO) {
        val progress: (Int) -> Unit = { percent -> updateLoading(book.title, percent, "Parsing") }
        if (book.format == BookFormat.EPUB) {
            EpubParser(onProgress = progress).parse(book.file)
        } else {
            book.file.inputStream().use { parserFor(book.format, onProgress = progress).parse(it) }
        }
    }

    private fun updateLoading(title: String, percent: Int, status: String) {
        val current = _document.value as? PhoneDocumentState.Loading ?: return
        if (current.title == title) {
            _document.value = current.copy(progressPercent = percent.coerceIn(0, 100), status = status)
        }
    }

    fun reportPosition(position: BookPosition) {
        val state = _document.value as? PhoneDocumentState.Loaded ?: return
        maybeMarkFinished(state, position)
        viewModelScope.launch { positionsRepo.setPosition(state.book.id, position) }
    }

    private fun maybeMarkFinished(state: PhoneDocumentState.Loaded, position: BookPosition) {
        val lastChapter = state.document.chapters.lastIndex
        if (position.chapterIndex < lastChapter) return
        val chapter = state.document.chapters.getOrNull(position.chapterIndex) ?: return
        if (chapter.blocks.isNotEmpty() && position.blockIndex >= chapter.blocks.lastIndex) {
            viewModelScope.launch { statsRepo.markFinished(state.book.id) }
        }
    }

    fun startReadingSession() {
        if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()
    }

    fun endReadingSession() {
        val start = sessionStartMs
        if (start == 0L) return
        sessionStartMs = 0L
        val elapsed = (System.currentTimeMillis() - start).coerceAtMost(10 * 60_000L)
        if (elapsed > 0) viewModelScope.launch { statsRepo.recordSession(elapsed) }
    }

    fun jumpTo(position: BookPosition) {
        viewModelScope.launch { _jumps.send(position) }
    }

    fun bookmarkHere(label: String? = null) {
        val state = _document.value as? PhoneDocumentState.Loaded ?: return
        val pos = currentPosition.value
        val mode = settings.value.mode
        viewModelScope.launch {
            bookmarksRepo.add(state.book.id, Bookmark(pos, System.currentTimeMillis(), label, mode))
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        val state = _document.value as? PhoneDocumentState.Loaded ?: return
        viewModelScope.launch { bookmarksRepo.remove(state.book.id, bookmark.position, bookmark.mode) }
    }

    fun runSearch(query: String) {
        val state = _document.value as? PhoneDocumentState.Loaded ?: return
        val q = query.trim()
        if (q.isBlank()) return
        _searchQuery.value = q
        viewModelScope.launch {
            _searchResults.value = withContext(Dispatchers.Default) { searchDocument(state.document, q) }
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    fun setMode(mode: ReadingMode) = editSetting("mode", mode.name) { it.copy(mode = mode) }
    fun setFont(font: FontChoice) = editSetting("font", font.name) { it.copy(font = font) }
    fun setTextSize(value: Int) = editSetting("textSizeSp", value.coerceIn(ReaderSettings.TEXT_SIZE_RANGE)) {
        it.copy(textSizeSp = value.coerceIn(ReaderSettings.TEXT_SIZE_RANGE))
    }
    fun setSentenceTextSize(value: Int) = editSetting("sentenceTextSizeSp", value.coerceIn(ReaderSettings.SENTENCE_TEXT_SIZE_RANGE)) {
        it.copy(sentenceTextSizeSp = value.coerceIn(ReaderSettings.SENTENCE_TEXT_SIZE_RANGE))
    }
    fun setAutoscrollEnabled(value: Boolean) = editSetting("autoscrollEnabled", value) { it.copy(autoscrollEnabled = value) }
    fun setAutoscrollSpeed(value: Int) = editSetting("autoscrollSpeed", value.coerceIn(ReaderSettings.AUTOSCROLL_SPEED_RANGE)) {
        it.copy(autoscrollSpeed = value.coerceIn(ReaderSettings.AUTOSCROLL_SPEED_RANGE))
    }
    fun setSpeedreadWpm(value: Int) {
        val wpm = value.coerceIn(ReaderSettings.WPM_RANGE)
        editSetting("speedreadWpm", wpm) { it.copy(speedreadWpm = wpm) }
        // Debounced separately: recordWpm appends a sample and trims history to
        // the last 50, so an undebounced slider drag would flush the real
        // reading-speed history with dozens of junk samples.
        wpmRecordJob?.cancel()
        wpmRecordJob = viewModelScope.launch {
            delay(SETTING_SYNC_DEBOUNCE_MS)
            statsRepo.recordWpm(wpm)
        }
    }

    /**
     * Apply a reader-setting change. The in-memory [_settings] update is immediate
     * so the UI stays responsive, but the DataStore write and the cross-device
     * watch RPC are debounced per key. Sliders emit onValueChange on every drag
     * tick; without this, a single drag would spawn dozens of Wear Data Layer
     * round-trips and DataStore transactions.
     */
    private fun editSetting(key: String, value: Any, transform: (ReaderSettings) -> ReaderSettings) {
        _settings.value = transform(_settings.value)
        settingSyncJobs[key]?.cancel()
        settingSyncJobs[key] = viewModelScope.launch {
            delay(SETTING_SYNC_DEBOUNCE_MS)
            settingsRepo.update(transform)
            watchRepo.setSetting(key, value)
        }
    }

    private fun searchDocument(doc: Document, query: String): List<PhoneSearchResult> {
        val q = query.lowercase()
        val out = mutableListOf<PhoneSearchResult>()
        for ((ci, chapter) in doc.chapters.withIndex()) {
            for ((bi, block) in chapter.blocks.withIndex()) {
                val text = blockText(block)
                val idx = text.lowercase().indexOf(q)
                if (idx < 0) continue
                val start = (idx - 40).coerceAtLeast(0)
                val end = (idx + q.length + 40).coerceAtMost(text.length)
                out += PhoneSearchResult(
                    position = BookPosition(ci, bi),
                    snippet = text.substring(start, end).replace(Regex("\\s+"), " ").trim(),
                )
                if (out.size >= 100) return out
            }
        }
        return out
    }

    private fun blockText(block: Block): String = when (block) {
        is Block.Heading -> block.text
        is Block.Paragraph -> block.runs.joinToString("") { it.text }
        is Block.Code -> block.text
        Block.Divider -> ""
    }

    override fun onCleared() {
        endReadingSession()
        loadJob?.cancel()
        cacheStoreJob?.cancel()
        super.onCleared()
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PhoneReaderViewModel(app) as T
    }

    private companion object {
        const val CACHE_LOAD_TIMEOUT_MS = 5_000L
        const val COLD_PARSE_TIMEOUT_MS = 120_000L
        const val SETTING_SYNC_DEBOUNCE_MS = 400L
    }
}
