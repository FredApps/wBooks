package com.wbooks.companion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class GutenbergViewModel(application: Application) : AndroidViewModel(application) {

    private val gutenberg = GutenbergRepository()
    private val watch = WatchRepository(application)

    data class UiState(
        val query: String = "",
        val results: List<GutenbergBook> = emptyList(),
        val loading: Boolean = false,
        val downloadingId: String? = null,
        val errorMessage: String? = null,
        val lastSentTitle: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Pre-populate with the popular feed so the user has something to look at.
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            runCatching { gutenberg.popular() }
                .onSuccess { _state.value = _state.value.copy(results = it, loading = false) }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false,
                        errorMessage = it.message ?: "Could not reach Project Gutenberg",
                    )
                }
        }
    }

    fun onQueryChange(value: String) {
        _state.value = _state.value.copy(query = value)
    }

    fun submitSearch() {
        val q = _state.value.query.trim()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
            runCatching {
                if (q.isEmpty()) gutenberg.popular() else gutenberg.search(q)
            }
                .onSuccess { _state.value = _state.value.copy(results = it, loading = false) }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false,
                        errorMessage = it.message ?: "Search failed",
                    )
                }
        }
    }

    /**
     * Download [book] to memory and push to the watch. Books are typically a few
     * hundred KB so an in-memory buffer is fine and avoids a temp-file dance.
     */
    fun sendToWatch(book: GutenbergBook) = viewModelScope.launch {
        _state.value = _state.value.copy(downloadingId = book.id, errorMessage = null)
        val buf = ByteArrayOutputStream()
        val downloadResult = runCatching { gutenberg.download(book, buf) }
        if (downloadResult.isFailure) {
            _state.value = _state.value.copy(
                downloadingId = null,
                errorMessage = downloadResult.exceptionOrNull()?.message ?: "Download failed",
            )
            return@launch
        }
        val filename = filenameFor(book)
        val uploadResult = watch.uploadStream(buf.toByteArray().inputStream(), filename)
        _state.value = when (uploadResult) {
            is WatchRepository.Result.Ok ->
                _state.value.copy(downloadingId = null, lastSentTitle = book.title)
            is WatchRepository.Result.NoWatch ->
                _state.value.copy(downloadingId = null, errorMessage = "No watch connected")
            is WatchRepository.Result.Error ->
                _state.value.copy(downloadingId = null, errorMessage = uploadResult.message)
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    fun dismissSentToast() {
        _state.value = _state.value.copy(lastSentTitle = null)
    }

    private fun filenameFor(book: GutenbergBook): String {
        val safeTitle = book.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        return "$safeTitle.${book.extension}"
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GutenbergViewModel(app) as T
    }
}
