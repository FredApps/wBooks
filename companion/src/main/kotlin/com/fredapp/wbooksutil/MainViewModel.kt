package com.fredapp.wbooksutil

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = WatchRepository(application)
    private var watchPollingJob: Job? = null

    data class UiState(
        val books: List<BookSummary> = emptyList(),
        val folders: List<Folder> = emptyList(),
        val bookFolders: Map<String, String> = emptyMap(),
        val loading: Boolean = false,
        val sending: Boolean = false,
        val noWatch: Boolean = false,
        val errorMessage: String? = null,
        // Folders created locally but not yet confirmed by a book assignment.
        // Kept so newly created folders remain visible until the user drags a book in.
        val pendingFolders: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        refreshLibrary(showLoading = true)
    }

    fun startForegroundWatchPolling() {
        if (watchPollingJob?.isActive == true) return
        watchPollingJob = viewModelScope.launch {
            while (isActive) {
                delay(WATCH_POLL_INTERVAL_MS)
                pollWatchConnection()
            }
        }
    }

    fun stopForegroundWatchPolling() {
        watchPollingJob?.cancel()
        watchPollingJob = null
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        val trimmed = name.trim()
        val newFolder = Folder(id = trimmed, name = trimmed)
        _state.value = _state.value.copy(
            folders = (_state.value.folders + newFolder).distinctBy { it.id },
            pendingFolders = _state.value.pendingFolders + trimmed,
        )
        viewModelScope.launch { repo.mkdirBook(trimmed) }
    }

    fun deleteFolder(folderId: String) {
        _state.value = _state.value.copy(
            folders = _state.value.folders.filter { it.id != folderId },
            pendingFolders = _state.value.pendingFolders - folderId,
        )
        viewModelScope.launch {
            applyResult(repo.deleteBook(folderId))
        }
    }

    fun assignBookToFolder(bookId: String, folderId: String?) {
        val targetFolder = folderId ?: ""
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true)
            val result = repo.moveBook(bookId, targetFolder)
            _state.value = _state.value.copy(sending = false)
            applyResult(result)
        }
    }

    private suspend fun pollWatchConnection() {
        if (_state.value.sending) return
        val reachable = repo.hasReachableWatch()
        val state = _state.value
        when {
            reachable && state.noWatch -> refreshLibrary(showLoading = false)
            !reachable && !state.noWatch -> {
                _state.value = state.copy(noWatch = true, books = emptyList(), loading = false)
            }
        }
    }

    private suspend fun refreshLibrary(showLoading: Boolean) {
        if (showLoading) {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
        }
        applyResult(repo.fetchLibrary())
        if (showLoading) {
            _state.value = _state.value.copy(loading = false)
        }
    }

    fun delete(id: String) = viewModelScope.launch {
        applyResult(repo.deleteBook(id))
    }

    fun upload(uri: Uri) = viewModelScope.launch {
        val filename = displayNameFor(uri) ?: "book"
        _state.value = _state.value.copy(sending = true, errorMessage = null)
        val result = repo.uploadBook(uri, filename)
        _state.value = _state.value.copy(sending = false)
        when (result) {
            is WatchRepository.Result.Ok -> refresh()
            is WatchRepository.Result.NoWatch ->
                _state.value = _state.value.copy(noWatch = true)
            is WatchRepository.Result.Error ->
                _state.value = _state.value.copy(errorMessage = result.message)
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    private fun applyResult(result: WatchRepository.Result<LibrarySnapshot>) {
        when (result) {
            is WatchRepository.Result.Ok -> {
                val snapshot = result.value
                val books = snapshot.books
                // Derive folder membership from book ID path prefixes.
                // "Fiction/moby-dick.epub" -> folder "Fiction"; "moby-dick.epub" -> uncategorized.
                val bookFolders = books
                    .mapNotNull { book ->
                        val folder = book.id.substringBeforeLast('/', "")
                        if (folder.isNotEmpty()) book.id to folder else null
                    }
                    .toMap()
                val derivedFolderNames = (bookFolders.values + snapshot.folders).distinct().sorted().toSet()
                val pending = _state.value.pendingFolders - derivedFolderNames
                val allFolders = (derivedFolderNames + pending).sorted()
                    .map { Folder(id = it, name = it) }
                _state.value = _state.value.copy(
                    books = books,
                    folders = allFolders,
                    bookFolders = bookFolders,
                    noWatch = false,
                    pendingFolders = pending,
                )
            }
            is WatchRepository.Result.NoWatch ->
                _state.value = _state.value.copy(noWatch = true, books = emptyList())
            is WatchRepository.Result.Error ->
                _state.value = _state.value.copy(errorMessage = result.message)
        }
    }

    private fun displayNameFor(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(app) as T
    }

    companion object {
        private const val WATCH_POLL_INTERVAL_MS = 5_000L
    }
}
