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
import com.wbooks.data.settings.next
import com.wbooks.data.settings.nextTextColor
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
import kotlinx.coroutines.withContext

sealed interface DocumentState {
    data object Idle : DocumentState
    data object Loading : DocumentState
    data class Loaded(val book: Book, val doc: Document) : DocumentState
    data class Failed(val book: Book, val message: String) : DocumentState
}

class ReaderViewModel(
    private val settingsRepo: SettingsRepository,
    private val libraryRepo: LibraryRepository,
    private val positionsRepo: PositionsRepository,
    private val bookmarksRepo: BookmarksRepository,
    private val transferController: TransferController,
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

    // ---- Library + document lifecycle ----
    init {
        viewModelScope.launch { libraryRepo.refresh() }
    }

    fun refreshLibrary() {
        viewModelScope.launch { libraryRepo.refresh() }
    }

    fun openBook(book: Book) {
        loadJob?.cancel()
        _document.value = DocumentState.Loading
        loadJob = viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    book.file.inputStream().use { parserFor(book.format).parse(it) }
                }
            }
            _document.value = result.fold(
                onSuccess = { DocumentState.Loaded(book, it) },
                onFailure = { DocumentState.Failed(book, it.message ?: it::class.simpleName ?: "unknown error") },
            )
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
    fun setTextSize(value: Int) = editSettings { it.copy(textSizeSp = value.coerceIn(ReaderSettings.TEXT_SIZE_RANGE)) }
    fun setSentenceTextSize(value: Int) = editSettings { it.copy(sentenceTextSizeSp = value.coerceIn(ReaderSettings.SENTENCE_TEXT_SIZE_RANGE)) }
    fun setAutoscrollSpeed(value: Int) = editSettings { it.copy(autoscrollSpeed = value.coerceIn(ReaderSettings.AUTOSCROLL_SPEED_RANGE)) }
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == ReaderViewModel::class.java)
            return ReaderViewModel(settingsRepo, libraryRepo, positionsRepo, bookmarksRepo, transferController) as T
        }
    }
}
