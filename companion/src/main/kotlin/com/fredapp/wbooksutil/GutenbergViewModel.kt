package com.fredapp.wbooksutil

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GutenbergViewModel(application: Application) : AndroidViewModel(application) {

    private val gutenberg = GutenbergRepository()
    private val watch = WatchRepository(application)

    data class UiState(
        val query: String = "",
        val popularBooks: List<GutenbergBook> = emptyList(),
        val recentReleases: List<GutenbergBook> = emptyList(),
        val searchResults: List<GutenbergBook> = emptyList(),
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val downloadingId: String? = null,
        val downloadingTitle: String? = null,
        val downloadProgressBytes: Long = 0L,
        val downloadProgressTotal: Long = -1L,
        val deviceBookFilenames: Set<String> = emptySet(),
        val deviceBookTitleKeys: Set<String> = emptySet(),
        val canceledBookIds: Set<String> = emptySet(),
        val popularHasMore: Boolean = false,
        val recentHasMore: Boolean = false,
        val searchHasMore: Boolean = false,
        val errorMessage: String? = null,
        val lastSentTitle: String? = null,
        val lastStatusMessage: String? = null,
        val noWatch: Boolean = false,
    ) {
        val showingSearch: Boolean
            get() = query.trim().isNotEmpty()

        val visibleBooks: List<GutenbergBook>
            get() = if (showingSearch) searchResults else popularBooks + recentReleases
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadCancelRequested = false

    init {
        loadHomeSections()
        refreshDeviceBooks()
    }

    private fun loadHomeSections() {
        searchJob?.cancel()
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
            runCatching {
                gutenberg.popular() to gutenberg.recentReleases()
            }
                .onSuccess { (popular, recent) ->
                    _state.value = _state.value.copy(
                        popularBooks = popular.books,
                        recentReleases = recent.books,
                        popularHasMore = popular.hasMore,
                        recentHasMore = recent.hasMore,
                        searchResults = emptyList(),
                        searchHasMore = false,
                        loading = false,
                    )
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    _state.value = _state.value.copy(
                        loading = false,
                        errorMessage = it.message ?: "Could not reach Project Gutenberg",
                    )
                }
        }
    }

    fun reconnect() {
        _state.value = _state.value.copy(noWatch = false, errorMessage = null)
        loadHomeSections()
        refreshDeviceBooks()
    }

    fun onQueryChange(value: String) {
        val previous = _state.value
        _state.value = previous.copy(
            query = value,
            searchResults = if (value.isBlank()) emptyList() else previous.searchResults,
            searchHasMore = if (value.isBlank()) false else previous.searchHasMore,
        )
        if (value.isBlank() && previous.popularBooks.isEmpty() && previous.recentReleases.isEmpty()) {
            loadHomeSections()
        }
    }

    fun submitSearch() {
        val q = _state.value.query.trim()
        searchJob?.cancel()
        if (q.isEmpty()) {
            if (_state.value.popularBooks.isEmpty() && _state.value.recentReleases.isEmpty()) {
                loadHomeSections()
            } else {
                _state.value = _state.value.copy(searchResults = emptyList(), errorMessage = null)
            }
            return
        }
        searchJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
            runCatching { gutenberg.search(q) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        searchResults = it.books,
                        searchHasMore = it.hasMore,
                        loading = false,
                    )
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    _state.value = _state.value.copy(
                        loading = false,
                        errorMessage = it.message ?: "Search failed",
                    )
                }
        }
    }

    fun loadMore(target: GutenbergListTarget) {
        if (loadMoreJob?.isActive == true) return
        val current = _state.value
        val count = when (target) {
            GutenbergListTarget.POPULAR -> current.popularBooks.size
            GutenbergListTarget.RECENT -> current.recentReleases.size
            GutenbergListTarget.SEARCH -> current.searchResults.size
        }
        if (count >= MAX_LIST_ITEMS) {
            _state.value = current.copy(errorMessage = "Showing the maximum $MAX_LIST_ITEMS Gutenberg results for this list.")
            return
        }
        loadMoreJob = viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true, errorMessage = null)
            val startIndex = count + 1
            val result = runCatching {
                when (target) {
                    GutenbergListTarget.POPULAR -> gutenberg.popular(startIndex)
                    GutenbergListTarget.RECENT -> gutenberg.recentReleases(startIndex)
                    GutenbergListTarget.SEARCH -> gutenberg.search(_state.value.query.trim(), startIndex)
                }
            }
            result
                .onSuccess { page ->
                    appendPage(target, page)
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    _state.value = _state.value.copy(
                        loadingMore = false,
                        errorMessage = it.message ?: "Could not load more books",
                    )
                }
        }
    }

    private fun appendPage(target: GutenbergListTarget, page: GutenbergPage) {
        val state = _state.value
        when (target) {
            GutenbergListTarget.POPULAR -> {
                val merged = mergeBooks(state.popularBooks, page.books)
                _state.value = state.copy(
                    popularBooks = merged,
                    popularHasMore = page.hasMore && merged.size < MAX_LIST_ITEMS,
                    loadingMore = false,
                )
            }
            GutenbergListTarget.RECENT -> {
                val merged = mergeBooks(state.recentReleases, page.books)
                _state.value = state.copy(
                    recentReleases = merged,
                    recentHasMore = page.hasMore && merged.size < MAX_LIST_ITEMS,
                    loadingMore = false,
                )
            }
            GutenbergListTarget.SEARCH -> {
                val merged = mergeBooks(state.searchResults, page.books)
                _state.value = state.copy(
                    searchResults = merged,
                    searchHasMore = page.hasMore && merged.size < MAX_LIST_ITEMS,
                    loadingMore = false,
                )
            }
        }
    }

    /**
     * Stream [book]'s download straight from HTTP into the watch's ChannelClient
     * â€” no intermediate buffer. The bytes flow network -> ChannelClient as
     * Gutenberg pushes them.
     */
    fun sendToWatch(book: GutenbergBook) {
        if (_state.value.downloadingId != null) return
        val filename = filenameFor(book)
        val overwrite = _state.value.canceledBookIds.contains(book.id)
        if (!overwrite && _state.value.deviceBookFilenames.contains(filename.normalizedFilename())) return
        downloadCancelRequested = false
        downloadJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                downloadingId = book.id,
                downloadingTitle = book.title,
                downloadProgressBytes = 0L,
                downloadProgressTotal = book.sizeBytes ?: -1L,
                errorMessage = null,
                lastStatusMessage = null,
            )
            val result = try {
                gutenberg.withDownload(book) { input, totalBytes ->
                    watch.uploadStream(
                        input = input,
                        filename = filename,
                        totalBytes = totalBytes,
                        onProgress = { sent, total ->
                            _state.value = _state.value.copy(
                                downloadProgressBytes = sent,
                                downloadProgressTotal = total,
                            )
                        },
                        overwrite = overwrite,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                WatchRepository.Result.Error(t.message ?: "Download failed")
            }
            _state.value = when {
                downloadCancelRequested ->
                    _state.value.copy(
                        downloadingId = null,
                        downloadingTitle = null,
                        downloadProgressBytes = 0L,
                        downloadProgressTotal = -1L,
                        lastStatusMessage = "Add canceled",
                    )
                result is WatchRepository.Result.Ok ->
                    _state.value.copy(
                        downloadingId = null,
                        downloadingTitle = null,
                        downloadProgressBytes = 0L,
                        downloadProgressTotal = -1L,
                        deviceBookFilenames = _state.value.deviceBookFilenames + filename.normalizedFilename(),
                        deviceBookTitleKeys = _state.value.deviceBookTitleKeys + book.title.normalizedTitleKey(),
                        canceledBookIds = _state.value.canceledBookIds - book.id,
                        lastSentTitle = book.title,
                    )
                result is WatchRepository.Result.NoWatch ->
                    _state.value.disconnectedCopy()
                result is WatchRepository.Result.Error ->
                    _state.value.copy(
                        downloadingId = null,
                        downloadingTitle = null,
                        downloadProgressBytes = 0L,
                        downloadProgressTotal = -1L,
                        errorMessage = result.message,
                    )
                else -> _state.value
            }
            downloadJob = null
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    _state.value = _state.value.copy(
                        downloadingId = null,
                        downloadingTitle = null,
                        downloadProgressBytes = 0L,
                        downloadProgressTotal = -1L,
                        canceledBookIds = _state.value.canceledBookIds + (_state.value.downloadingId ?: ""),
                        lastStatusMessage = "Add canceled",
                    )
                    downloadJob = null
                }
            }
        }
    }

    fun cancelDownload() {
        downloadCancelRequested = true
        downloadJob?.cancel()
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    fun dismissSentToast() {
        _state.value = _state.value.copy(lastSentTitle = null)
    }

    fun dismissStatusMessage() {
        _state.value = _state.value.copy(lastStatusMessage = null)
    }

    private fun filenameFor(book: GutenbergBook): String {
        val safeTitle = book.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        return "$safeTitle.${book.extension}"
    }

    fun isPresentOnDevice(book: GutenbergBook): Boolean {
        val state = _state.value
        return (filenameFor(book).normalizedFilename() in state.deviceBookFilenames ||
            book.gutenbergId()?.let { id ->
                SEED_GUTENBERG_FILES[id]?.normalizedFilename() in state.deviceBookFilenames
            } == true ||
            book.title.normalizedTitleKey() in state.deviceBookTitleKeys) &&
            book.id !in state.canceledBookIds
    }

    private fun refreshDeviceBooks() = viewModelScope.launch {
        when (val result = watch.fetchLibrary()) {
            is WatchRepository.Result.Ok -> {
                _state.value = _state.value.copy(
                    deviceBookFilenames = result.value.books.mapTo(mutableSetOf()) {
                        it.id.substringAfterLast('/').normalizedFilename()
                    },
                    deviceBookTitleKeys = result.value.books.mapTo(mutableSetOf()) {
                        it.title.normalizedTitleKey()
                    },
                    noWatch = false,
                )
            }
            is WatchRepository.Result.NoWatch -> _state.value = _state.value.disconnectedCopy()
            is WatchRepository.Result.Error -> Unit
        }
    }

    private fun UiState.disconnectedCopy(): UiState = copy(
        downloadingId = null,
        downloadingTitle = null,
        downloadProgressBytes = 0L,
        downloadProgressTotal = -1L,
        noWatch = true,
        loading = false,
        loadingMore = false,
        errorMessage = null,
        lastStatusMessage = null,
    )

    private fun mergeBooks(current: List<GutenbergBook>, incoming: List<GutenbergBook>): List<GutenbergBook> {
        val seen = current.mapTo(mutableSetOf()) { it.id.ifEmpty { it.downloadUrl } }
        val merged = current + incoming.filter { seen.add(it.id.ifEmpty { it.downloadUrl }) }
        return merged.take(MAX_LIST_ITEMS)
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GutenbergViewModel(app) as T
    }

    companion object {
        const val MAX_LIST_ITEMS = 200
    }
}

enum class GutenbergListTarget {
    POPULAR,
    RECENT,
    SEARCH,
}

private fun String.normalizedFilename(): String = trim().lowercase()

private fun String.normalizedTitleKey(): String =
    lowercase().replace(Regex("[^a-z0-9]"), "")

private fun GutenbergBook.gutenbergId(): String? =
    Regex("/ebooks/(\\d+)").find(id)?.groupValues?.get(1)
        ?: Regex("/ebooks/(\\d+)").find(downloadUrl)?.groupValues?.get(1)

private val SEED_GUTENBERG_FILES = mapOf(
    "35" to "The Time Machine.odt",
    "43" to "The strange case of Dr. Jekyll and Mr. Hyde.docx",
    "1342" to "Pride and Prejudice.txt",
    "1661" to "The Adventures of Sherlock Holmes.html",
    "1952" to "The Yellow Wallpaper.fb2",
    "2701" to "Moby Dick; Or, The Whale.epub",
)
