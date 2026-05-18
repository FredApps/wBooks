package com.wbooks.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wbooks.data.settings.FontChoice
import com.wbooks.data.settings.ReaderSettings
import com.wbooks.data.settings.ReadingMode
import com.wbooks.data.settings.SettingsRepository
import com.wbooks.data.settings.next
import com.wbooks.data.settings.nextTextColor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Single VM shared by Reader, Settings, and the Tools page. Holds the persisted
 * [ReaderSettings] and offers narrow edit methods so the UI can stay declarative.
 *
 * Why one VM instead of per-screen: settings are read by the Reader and edited by
 * the Settings page; sharing keeps both bound to the same flow without plumbing.
 */
class ReaderViewModel(
    private val repo: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<ReaderSettings> = repo.flow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ReaderSettings(),
    )

    fun cycleMode() = edit { it.copy(mode = it.mode.next()) }
    fun cycleFont() = edit { it.copy(font = it.font.next()) }
    fun cycleTextColor() = edit { it.copy(textColorArgb = nextTextColor(it.textColorArgb)) }
    fun toggleAutoscroll() = edit { it.copy(autoscrollEnabled = !it.autoscrollEnabled) }

    fun setMode(mode: ReadingMode) = edit { it.copy(mode = mode) }
    fun setTextSize(value: Int) = edit { it.copy(textSizeSp = value.coerceIn(ReaderSettings.TEXT_SIZE_RANGE)) }
    fun setSentenceTextSize(value: Int) = edit { it.copy(sentenceTextSizeSp = value.coerceIn(ReaderSettings.SENTENCE_TEXT_SIZE_RANGE)) }
    fun setAutoscrollSpeed(value: Int) = edit { it.copy(autoscrollSpeed = value.coerceIn(ReaderSettings.AUTOSCROLL_SPEED_RANGE)) }
    fun setSpeedreadWpm(value: Int) = edit { it.copy(speedreadWpm = value.coerceIn(ReaderSettings.WPM_RANGE)) }
    fun setFont(font: FontChoice) = edit { it.copy(font = font) }

    private fun edit(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    class Factory(private val repo: SettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == ReaderViewModel::class.java)
            return ReaderViewModel(repo) as T
        }
    }
}
