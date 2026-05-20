package com.fredapp.wbooksutil

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = WatchRepository(application)

    data class UiState(
        val books: List<BookSummary> = emptyList(),
        val loading: Boolean = false,
        val sending: Boolean = false,
        val noWatch: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, errorMessage = null)
        applyResult(repo.fetchLibrary())
        _state.value = _state.value.copy(loading = false)
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

    private fun applyResult(result: WatchRepository.Result<List<BookSummary>>) {
        when (result) {
            is WatchRepository.Result.Ok ->
                _state.value = _state.value.copy(books = result.value, noWatch = false)
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
}
