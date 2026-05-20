package com.wbooks.companion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the phone-side settings screen. The watch is the source of truth:
 * we fetch on screen-open, refuse to display editable controls until that
 * fetch succeeds, and round-trip every user edit back to the watch (whose
 * reply contains the new authoritative snapshot we then redisplay).
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = WatchRepository(application)
    private val crashPref: CrashReportingPref = (application as CompanionApp).crashReportingPref

    sealed interface SyncState {
        data object Idle : SyncState
        data object Loading : SyncState
        /** Re-fetching while a prior snapshot is still on screen. */
        data class Refreshing(val stale: SettingsSnapshot) : SyncState
        data class Synced(val snapshot: SettingsSnapshot) : SyncState
        data object NoWatch : SyncState
        data class Error(val message: String) : SyncState
    }

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** True while a SET is in flight; the UI disables interactions to avoid races. */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /**
     * Fetch fresh settings from the watch. If we already have a snapshot, keep
     * showing it (as [SyncState.Refreshing]) instead of dropping back to a
     * full-screen spinner — the user shouldn't see the form vanish every time
     * they re-enter the screen.
     */
    fun refresh() = viewModelScope.launch {
        val stale = (_state.value as? SyncState.Synced)?.snapshot
            ?: (_state.value as? SyncState.Refreshing)?.stale
        _state.value = if (stale != null) SyncState.Refreshing(stale) else SyncState.Loading
        applyResult(repo.fetchSettings())
    }

    fun setMode(mode: ReadingMode) = sendUpdate("mode", mode.name)
    fun setFont(font: FontChoice) = sendUpdate("font", font.name)
    fun setTheme(theme: ThemeChoice) = sendUpdate("theme", theme.name)
    fun setTextSize(value: Int) = sendUpdate("textSizeSp", value)
    fun setSentenceTextSize(value: Int) = sendUpdate("sentenceTextSizeSp", value)
    fun setTextColor(argb: Int) = sendUpdate("textColorArgb", argb)
    fun setAutoscrollEnabled(value: Boolean) = sendUpdate("autoscrollEnabled", value)
    fun setAutoscrollSpeed(value: Int) = sendUpdate("autoscrollSpeed", value)
    fun setScreenBrightness(value: Int) = sendUpdate("screenBrightness", value)
    fun setSpeedreadWpm(value: Int) = sendUpdate("speedreadWpm", value)
    fun setCrashReportingEnabled(value: Boolean) = sendUpdate("crashReportingEnabled", value)

    private fun sendUpdate(key: String, value: Any) = viewModelScope.launch {
        _saving.value = true
        val result = repo.setSetting(key, value)
        _saving.value = false
        applyResult(result)
    }

    /**
     * Common path for fetch + set results. On success, push the new snapshot
     * into UI state AND mirror the crash-reporting bit into the local
     * SharedPreferences (so the next cold launch starts Sentry in the right
     * mode even before the user opens settings again).
     */
    private fun applyResult(result: WatchRepository.Result<SettingsSnapshot>) {
        when (result) {
            is WatchRepository.Result.Ok -> {
                _state.value = SyncState.Synced(result.value)
                crashPref.applyFromWatch(result.value.crashReportingEnabled)
            }
            is WatchRepository.Result.NoWatch -> _state.value = SyncState.NoWatch
            is WatchRepository.Result.Error -> _state.value = SyncState.Error(result.message)
        }
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(app) as T
    }
}
