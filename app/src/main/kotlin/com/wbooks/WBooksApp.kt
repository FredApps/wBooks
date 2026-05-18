package com.wbooks

import android.app.Application
import com.wbooks.data.bookmarks.BookmarksRepository
import com.wbooks.data.library.LibraryRepository
import com.wbooks.data.position.PositionsRepository
import com.wbooks.data.settings.SettingsRepository
import com.wbooks.parser.cache.DocumentCache
import com.wbooks.transfer.TransferController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /** Application-scope coroutine scope for one-shot background work that needs to outlive any single screen. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Run the seed copy off the main thread so first-launch startup latency
        // isn't blocked by ~2 MB of asset I/O. The library refresh at the end
        // pushes the new books into the StateFlow the UI is already collecting.
        appScope.launch { seedLibraryIfFirstRun() }
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
    private suspend fun seedLibraryIfFirstRun() {
        val marker = File(filesDir, ".seed-version")
        val current = marker.takeIf { it.exists() }?.readText()?.trim()
        if (current == SEED_VERSION) return

        val names = runCatching { assets.list("seed-books") }.getOrNull().orEmpty()
        booksDir.mkdirs()
        var copied = 0
        for (name in names) {
            val dest = File(booksDir, name)
            if (dest.exists()) continue
            runCatching {
                assets.open("seed-books/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                copied++
            }
        }
        marker.writeText(SEED_VERSION)

        if (copied > 0) libraryRepository.refresh()
    }

    private companion object {
        /** Bump to re-seed any books not already present in booksDir. */
        const val SEED_VERSION = "2"
    }
}
