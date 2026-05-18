package com.wbooks

import android.app.Application
import com.wbooks.data.library.LibraryRepository
import com.wbooks.data.settings.SettingsRepository
import java.io.File

class WBooksApp : Application() {

    val booksDir: File by lazy {
        File(filesDir, "books").apply { mkdirs() }
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val libraryRepository: LibraryRepository by lazy { LibraryRepository(booksDir) }
}
