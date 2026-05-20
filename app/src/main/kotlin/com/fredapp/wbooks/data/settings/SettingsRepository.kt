package com.fredapp.wbooks.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "reader_settings")

private object Keys {
    val MODE = stringPreferencesKey("mode")
    val FONT = stringPreferencesKey("font")
    val TEXT_SIZE = intPreferencesKey("text_size")
    val SENTENCE_TEXT_SIZE = intPreferencesKey("sentence_text_size")
    val TEXT_COLOR = intPreferencesKey("text_color")
    val AUTOSCROLL_ENABLED = booleanPreferencesKey("autoscroll_enabled")
    val AUTOSCROLL_SPEED = intPreferencesKey("autoscroll_speed")
    val SCREEN_BRIGHTNESS = intPreferencesKey("screen_brightness")
    val SPEEDREAD_WPM = intPreferencesKey("speedread_wpm")
    val THEME = stringPreferencesKey("theme")
}

/**
 * Persists [ReaderSettings] via Jetpack DataStore. Single instance per process
 * (the underlying [DataStore] is held by [Context.dataStore]); construct it from
 * the Application's context so the file lives in the app data dir.
 */
class SettingsRepository(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.dataStore

    val flow: Flow<ReaderSettings> = store.data.map { prefs -> prefs.toSettings() }

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        store.edit { prefs -> prefs.applySettings(transform(prefs.toSettings())) }
    }

    /** Synchronous-style point read of the latest persisted settings. */
    suspend fun snapshot(): ReaderSettings = flow.first()

    /**
     * Apply a single keyed update arriving from the phone companion (see
     * [com.fredapp.wbooks.transfer.SettingsJson]). Returns true if [key] was recognised
     * and a parseable value was written, false if either was rejected so the
     * caller can surface an error.
     *
     * Values arrive as strings on the wire; here we coerce them back into the
     * underlying type and run them through the same `coerceIn` clamps the
     * watch UI uses. Unknown enum names are ignored rather than thrown to keep
     * the protocol forward-compatible.
     */
    suspend fun applyWireKey(key: String, value: String): Boolean = when (key) {
        "mode" -> enumUpdate(value, ReadingMode::valueOf) { copy(mode = it) }
        "font" -> enumUpdate(value, FontChoice::valueOf) { copy(font = it) }
        "theme" -> enumUpdate(value, ThemeChoice::valueOf) { copy(theme = it) }
        "textSizeSp" -> intUpdate(value) { copy(textSizeSp = it.coerceIn(ReaderSettings.TEXT_SIZE_RANGE)) }
        "sentenceTextSizeSp" -> intUpdate(value) { copy(sentenceTextSizeSp = it.coerceIn(ReaderSettings.SENTENCE_TEXT_SIZE_RANGE)) }
        "textColorArgb" -> intUpdate(value) { copy(textColorArgb = it) }
        "autoscrollSpeed" -> intUpdate(value) { copy(autoscrollSpeed = it.coerceIn(ReaderSettings.AUTOSCROLL_SPEED_RANGE)) }
        "screenBrightness" -> intUpdate(value) { copy(screenBrightness = it.coerceIn(ReaderSettings.SCREEN_BRIGHTNESS_RANGE)) }
        "speedreadWpm" -> intUpdate(value) { copy(speedreadWpm = it.coerceIn(ReaderSettings.WPM_RANGE)) }
        "autoscrollEnabled" -> boolUpdate(value) { copy(autoscrollEnabled = it) }
        else -> false
    }

    private suspend fun intUpdate(value: String, edit: ReaderSettings.(Int) -> ReaderSettings): Boolean {
        val n = value.toIntOrNull() ?: return false
        update { it.edit(n) }
        return true
    }

    private suspend fun boolUpdate(value: String, edit: ReaderSettings.(Boolean) -> ReaderSettings): Boolean {
        val b = when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> return false
        }
        update { it.edit(b) }
        return true
    }

    private suspend fun <E : Enum<E>> enumUpdate(
        value: String,
        parser: (String) -> E,
        edit: ReaderSettings.(E) -> ReaderSettings,
    ): Boolean {
        val parsed = runCatching { parser(value) }.getOrNull() ?: return false
        update { it.edit(parsed) }
        return true
    }

    private fun Preferences.toSettings(): ReaderSettings {
        val defaults = ReaderSettings()
        return ReaderSettings(
            mode = this[Keys.MODE]?.let(::readMode) ?: defaults.mode,
            font = this[Keys.FONT]?.let(::readFont) ?: defaults.font,
            textSizeSp = this[Keys.TEXT_SIZE] ?: defaults.textSizeSp,
            sentenceTextSizeSp = this[Keys.SENTENCE_TEXT_SIZE] ?: defaults.sentenceTextSizeSp,
            textColorArgb = this[Keys.TEXT_COLOR] ?: defaults.textColorArgb,
            autoscrollEnabled = this[Keys.AUTOSCROLL_ENABLED] ?: defaults.autoscrollEnabled,
            autoscrollSpeed = this[Keys.AUTOSCROLL_SPEED] ?: defaults.autoscrollSpeed,
            screenBrightness = (this[Keys.SCREEN_BRIGHTNESS] ?: defaults.screenBrightness)
                .coerceIn(ReaderSettings.SCREEN_BRIGHTNESS_RANGE),
            speedreadWpm = this[Keys.SPEEDREAD_WPM] ?: defaults.speedreadWpm,
            theme = this[Keys.THEME]?.let(::readTheme) ?: defaults.theme,
        )
    }

    private fun MutablePreferences.applySettings(s: ReaderSettings) {
        this[Keys.MODE] = s.mode.name
        this[Keys.FONT] = s.font.name
        this[Keys.TEXT_SIZE] = s.textSizeSp
        this[Keys.SENTENCE_TEXT_SIZE] = s.sentenceTextSizeSp
        this[Keys.TEXT_COLOR] = s.textColorArgb
        this[Keys.AUTOSCROLL_ENABLED] = s.autoscrollEnabled
        this[Keys.AUTOSCROLL_SPEED] = s.autoscrollSpeed
        this[Keys.SCREEN_BRIGHTNESS] = s.screenBrightness
        this[Keys.SPEEDREAD_WPM] = s.speedreadWpm
        this[Keys.THEME] = s.theme.name
    }

    private fun readMode(raw: String): ReadingMode =
        runCatching { ReadingMode.valueOf(raw) }.getOrDefault(ReadingMode.NORMAL)

    private fun readFont(raw: String): FontChoice =
        runCatching { FontChoice.valueOf(raw) }.getOrDefault(FontChoice.SERIF)

    private fun readTheme(raw: String): ThemeChoice =
        runCatching { ThemeChoice.valueOf(raw) }.getOrDefault(ThemeChoice.DARK)
}
