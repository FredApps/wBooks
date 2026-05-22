package com.fredapp.wbooks.data.firstrun

import android.app.Application
import android.content.Context
import androidx.core.content.edit

/**
 * Tracks whether the current process is the very first launch after install.
 *
 * Read once at construction (synchronous SharedPreferences — same rationale as
 * [com.fredapp.wbooks.data.telemetry.CrashReportingPref]) and immediately
 * marked as consumed so subsequent launches see [isFirstRun] = false.
 *
 * The in-memory value remains stable for the lifetime of the process so any UI
 * reading it (e.g. the one-shot "How to use" hint in the library) doesn't flip
 * mid-session.
 */
class FirstRunPref(app: Application) {

    private val prefs = app.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True only during the first launch after install. */
    val isFirstRun: Boolean = !prefs.getBoolean(KEY_LAUNCHED, false)

    init {
        if (isFirstRun) prefs.edit { putBoolean(KEY_LAUNCHED, true) }
    }

    private companion object {
        const val FILE = "first_run_prefs"
        const val KEY_LAUNCHED = "has_launched"
    }
}
