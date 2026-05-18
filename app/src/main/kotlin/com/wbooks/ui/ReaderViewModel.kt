package com.wbooks.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wbooks.data.book.Book
import com.wbooks.data.bookmarks.Bookmark
import com.wbooks.data.bookmarks.BookmarksRepository
import com.wbooks.data.library.LibraryRepository
import com.wbooks.data.position.BookPosition
import com.wbooks.data.position.PositionsRepository
import com.wbooks.data.settings.FontChoice
import com.wbooks.data.settings.ReaderSettings
import com.wbooks.data.settings.ReadingMode
import com.wbooks.data.settings.SettingsRepository
import com.wbooks.data.settings.ThemeChoice
import com.wbooks.data.settings.next
import com.wbooks.data.settings.nextTextColor
import com.wbooks.parser.cache.DocumentCache
import com.wbooks.parser.EpubParser
import com.wbooks.parser.model.Block
import com.wbooks.parser.model.Document
import com.wbooks.parser.parserFor
import com.wbooks.transfer.TransferController
import com.wbooks.transfer.TransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
) : ViewModel() {

    // ---- Settings ----
    val settings: StateFlow<ReaderSettings> = settingsRepo.flow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ReaderSettings(),
    )

    // ---- Library ----
    val books: StateFlow<List<Book>> = libraryRepo.books

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

    fun jumpTo(position: BookPosition) {
        viewModelScope.launch { _jumps.send(position) }
    }

    /** Called by the renderer when the visible block changes. Debounced upstream. */
    fun reportPosition(position: BookPosition) {
        val state = _document.value
        if (state !is DocumentState.Loaded) return
        viewModelScope.launch { positionsRepo.setPosition(state.book.id, position) }
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
        // Per spec: opening a search result resets the reader to Normal mode.
        setMode(ReadingMode.NORMAL)
        jumpTo(result.position)
        clearSearch()
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
                val pre = if (start > 0) "…" else ""
                val post = if (end < text.length) "…" else ""
                out += SearchResult(
                    position = com.wbooks.data.position.BookPosition(ci, bi),
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

    fun openBook(book: Book) {
        loadJob?.cancel()
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
            val result = runCatching {
                documentCache.load(key) ?: parseBook(book).also { parsed ->
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { documentCache.store(key, parsed) }
                    }
                }
            }
            val initialPosition = withTimeoutOrNull(1_000) {
                positionsRepo.readPosition(book.id)
            } ?: BookPosition.START
            _document.value = result.fold(
                onSuccess = { DocumentState.Loaded(book, it, initialPosition) },
                onFailure = { DocumentState.Failed(book, it.message ?: it::class.simpleName ?: "unknown error") },
            )
        }
    }

    private suspend fun parseBook(book: Book): Document = withContext(Dispatchers.IO) {
        if (book.format == com.wbooks.data.book.BookFormat.EPUB) {
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
    fun setSpeedreadWpm(value: Int) = editSettings { it.copy(speedreadWpm = value.coerceIn(ReaderSettings.WPM_RANGE)) }
    fun setFont(font: FontChoice) = editSettings { it.copy(font = font) }

    private fun editSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    class Factory(
        private val settingsRepo: SettingsRepository,
        private val libraryRepo: LibraryRepository,
        private val positionsRepo: PositionsRepository,
        private val bookmarksRepo: BookmarksRepository,
        private val transferController: TransferController,
        private val documentCache: DocumentCache,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == ReaderViewModel::class.java)
            return ReaderViewModel(
                settingsRepo, libraryRepo, positionsRepo, bookmarksRepo, transferController, documentCache,
            ) as T
        }
    }
}
