package com.wbooks

import android.app.Application
import com.wbooks.data.settings.SettingsRepository

class WBooksApp : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
}
