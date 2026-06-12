package com.fredapp.wbooksutil

import android.app.Application
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

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val watch = WatchRepository(application)
    private val statsRepo = (application as CompanionApp).readingStatsRepository

    data class UiState(
        val stats: StatsSummary? = null,
        val loading: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var pollingJob: Job? = null

    init { refresh() }

    fun refresh() = viewModelScope.launch { refreshNow(showLoading = true) }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                refreshNow(showLoading = _state.value.stats == null)
                delay(5_000L)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun refreshNow(showLoading: Boolean) {
        if (showLoading) _state.value = _state.value.copy(loading = true)
        val phone = statsRepo.snapshot()
        // The watch's stats fold in when it's reachable; otherwise we just show
        // the phone's own. A missing or erroring watch is invisible here — the
        // Stats screen always has something to show because the phone reads too.
        val watchStats = (watch.fetchStats() as? WatchRepository.Result.Ok)?.value
        _state.value = _state.value.copy(stats = StatsMerge.merge(watchStats, phone), loading = false)
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = StatsViewModel(app) as T
    }
}
