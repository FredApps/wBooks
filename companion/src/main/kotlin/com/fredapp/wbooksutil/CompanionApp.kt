package com.fredapp.wbooksutil

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
