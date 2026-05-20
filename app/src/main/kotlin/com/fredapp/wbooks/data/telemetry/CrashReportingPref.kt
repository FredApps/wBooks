package com.fredapp.wbooks.data.telemetry

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * User-facing opt-out for Sentry crash reporting. Stored in SharedPreferences
 * (not DataStore) so it can be read synchronously from Application.onCreate
 * before the Sentry SDK initializes â€” DataStore is async-only and would force
 * us to either runBlocking on the main thread or leak crashes from the first
 * cold-start before the coroutine reads the value.
 *
 * Sentry SDK calls (init + close) are dispatched to a process-scoped IO
 * coroutine because [Sentry.close] flushes pending events with a multi-second
 * timeout â€” running it on the toggle handler's main thread would jank the UI.
 * The brief window between Application.onCreate firing [initIfEnabled] and the
 * background init completing is acceptable: it matches the timing of Sentry's
 * default ContentProvider auto-init, which also runs off-main implicitly.
 *
 * Default: enabled. To opt out, the user toggles the "Crash reports" chip in
 * Settings; the change schedules a [Sentry.close] so no further events leave
 * the device until they opt back in.
 */
class CrashReportingPref(private val app: Application) {

    private val prefs = app.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Lives as long as the process; never cancelled. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Call once from Application.onCreate. No-op when the user has opted out. */
    fun initIfEnabled() {
        if (_enabled.value) ioScope.launch { SentryAndroid.init(app) }
    }

    fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        prefs.edit { putBoolean(KEY, value) }
        _enabled.value = value
        ioScope.launch {
            if (value) SentryAndroid.init(app) else Sentry.close()
        }
    }

    private companion object {
        const val FILE = "telemetry_prefs"
        const val KEY = "crash_reporting_enabled"
    }
}
