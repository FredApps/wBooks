package com.fredapp.wbooksutil

import android.app.Application
import com.fredapp.wbooks.data.bookmarks.BookmarksRepository
import com.fredapp.wbooks.data.gutenberg.GutenbergDownloadsRepository
import com.fredapp.wbooks.data.library.LibraryRepository
import com.fredapp.wbooks.data.pace.ReadingPaceRepository
import com.fredapp.wbooks.data.position.PositionsRepository
import com.fredapp.wbooks.data.settings.SettingsRepository
import com.fredapp.wbooks.data.stats.ReadingStatsRepository
import com.fredapp.wbooks.parser.cache.DocumentCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Application subclass so the phone module can do manual Sentry init gated on
 * the user's crash-reporting opt-out â€” same pattern as `:app`'s `WBooksApp`.
 * Manifest auto-init is disabled; without this class running first, Sentry
 * would never start.
 *
 * Cold start also fires a background settings fetch so the local crash-reporting
 * cache catches up with the watch (the authoritative source) before the user
 * has to open the settings screen. If the watch is unreachable, we fall back
 * to whatever the cache held; the user's next visit to settings will reconcile.
 */
class CompanionApp : Application() {

    val crashReportingPref: CrashReportingPref by lazy { CrashReportingPref(this) }
    val booksDir: File by lazy { File(filesDir, "books").apply { mkdirs() } }
    val libraryRepository: LibraryRepository by lazy { LibraryRepository(booksDir) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val positionsRepository: PositionsRepository by lazy { PositionsRepository(this) }
    val bookmarksRepository: BookmarksRepository by lazy { BookmarksRepository(this) }
    val readingPaceRepository: ReadingPaceRepository by lazy { ReadingPaceRepository(this) }
    val readingStatsRepository: ReadingStatsRepository by lazy { ReadingStatsRepository(this) }
    val gutenbergDownloadsRepository: GutenbergDownloadsRepository by lazy { GutenbergDownloadsRepository(this) }
    val documentCache: DocumentCache by lazy { DocumentCache(File(cacheDir, "parsed")) }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Required before the first PDDocument.load() — without it pdfbox-android
        // can't resolve its bundled font/cmap resources and PDF conversion crashes.
        PDFBoxResourceLoader.init(this)
        crashReportingPref.initIfEnabled()
        appScope.launch {
            val repo = WatchRepository(this@CompanionApp)
            (repo.fetchSettings() as? WatchRepository.Result.Ok)?.let {
                crashReportingPref.applyFromWatch(it.value.crashReportingEnabled)
            }
        }
    }
}
