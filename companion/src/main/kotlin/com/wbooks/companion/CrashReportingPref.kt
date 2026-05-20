package com.wbooks.companion

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
 * Companion-side mirror of `:app`'s `CrashReportingPref`. The authoritative
 * value lives on the watch; this class holds a local cache so the phone can
 * decide whether to init Sentry at cold start, before any watch round-trip.
 *
 * Cache is updated whenever the user opens the settings screen and the watch
 * returns a fresh snapshot, OR when the user toggles the opt-out from this
 * device (which writes through to the watch and then writes the new value
 * back here). Default at first launch is enabled, matching the watch's default.
 *
 * Sentry SDK calls run on an internal IO scope so the multi-second [Sentry.close]
 * flush doesn't block the main thread — see the same comment in `:app`'s
 * `CrashReportingPref`.
 */
class CrashReportingPref(private val app: Application) {

    private val prefs = app.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initIfEnabled() {
        if (_enabled.value) ioScope.launch { SentryAndroid.init(app) }
    }

    /**
     * Apply a value that came back from the watch (or from a local toggle).
     * Persists the new value and brings the local Sentry SDK into the matching
     * state. No-op when the value hasn't changed.
     */
    fun applyFromWatch(value: Boolean) {
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
