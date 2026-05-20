package com.fredapps.watchdevtools

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * No-UI launcher. Fires the Developer options intent and immediately finishes
 * so the user lands directly in Settings → Developer options, where Wireless
 * debugging is one tap away. Falls back to the top-level Settings activity if
 * Developer options haven't been unlocked yet (build-number tap × 7).
 */
class LaunchDevOptionsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }
}
