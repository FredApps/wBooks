package com.wbooks

import android.app.Application
import com.wbooks.data.bookmarks.BookmarksRepository
import com.wbooks.data.library.LibraryRepository
import com.wbooks.data.position.PositionsRepository
import com.wbooks.data.settings.SettingsRepository
import com.wbooks.transfer.TransferController
import java.io.File

class WBooksApp : Application() {

    val booksDir: File by lazy {
        File(filesDir, "books").apply { mkdirs() }
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val libraryRepository: LibraryRepository by lazy { LibraryRepository(booksDir) }

    val positionsRepository: PositionsRepository by lazy { PositionsRepository(this) }

    val bookmarksRepository: BookmarksRepository by lazy { BookmarksRepository(this) }

    val transferController: TransferController by lazy { TransferController(this) }
}
