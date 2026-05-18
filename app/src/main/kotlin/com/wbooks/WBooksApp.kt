package com.wbooks

import android.app.Application
import com.wbooks.data.bookmarks.BookmarksRepository
import com.wbooks.data.library.LibraryRepository
import com.wbooks.data.position.PositionsRepository
import com.wbooks.data.settings.SettingsRepository
import com.wbooks.parser.cache.DocumentCache
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
    val documentCache: DocumentCache by lazy { DocumentCache(File(cacheDir, "parsed")) }

    override fun onCreate() {
        super.onCreate()
        seedLibraryIfFirstRun()
    }

    /**
     * On first install, populate the library from `assets/seed-books/` so the user
     * has something to read out of the box. Bumping [SEED_VERSION] re-seeds — useful
     * if we add or update bundled books in a later release.
     *
     * We never overwrite existing files: a user who deleted the seeded Moby Dick
     * shouldn't have it reappear on every launch. The version marker only triggers
     * the copy-once-per-bump behaviour.
     */
    private fun seedLibraryIfFirstRun() {
        val marker = File(filesDir, ".seed-version")
        val current = marker.takeIf { it.exists() }?.readText()?.trim()
        if (current == SEED_VERSION) return

        val names = runCatching { assets.list("seed-books") }.getOrNull() ?: return
        if (names.isEmpty()) {
            marker.writeText(SEED_VERSION)
            return
        }
        booksDir.mkdirs()
        for (name in names) {
            val dest = File(booksDir, name)
            if (dest.exists()) continue
            runCatching {
                assets.open("seed-books/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        marker.writeText(SEED_VERSION)
    }

    private companion object {
        /** Bump to re-seed any books not already present in booksDir. */
        const val SEED_VERSION = "1"
    }
}
