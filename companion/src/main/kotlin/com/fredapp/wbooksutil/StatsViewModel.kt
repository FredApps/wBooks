package com.fredapp.wbooksutil

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val watch = WatchRepository(application)

    data class UiState(
        val stats: StatsSummary? = null,
        val loading: Boolean = false,
        val noWatch: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, errorMessage = null)
        when (val result = watch.fetchStats()) {
            is WatchRepository.Result.Ok ->
                _state.value = _state.value.copy(stats = result.value, noWatch = false, loading = false)
            is WatchRepository.Result.NoWatch ->
                _state.value = _state.value.copy(stats = null, noWatch = true, loading = false)
            is WatchRepository.Result.Error ->
                _state.value = _state.value.copy(errorMessage = result.message, loading = false)
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = StatsViewModel(app) as T
    }
}
