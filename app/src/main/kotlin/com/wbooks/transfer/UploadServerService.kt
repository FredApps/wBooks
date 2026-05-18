package com.wbooks.transfer

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

/**
 * Foreground service that owns the [UploadServer] lifecycle so uploads can finish
 * even if the user lets the watch screen sleep. Notification copy will surface
 * the address + PIN so the user can read it without opening the app.
 *
 * Wire-up not implemented yet — the service is declared in the manifest so its
 * permission/foreground-type combo is locked in before code lands.
 */
class UploadServerService : Service() {

    private var server: UploadServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: build notification, startForeground, start UploadServer on chosen port.
        val booksDir = File(filesDir, "books").apply { mkdirs() }
        server = UploadServer(port = 8080, booksDir = booksDir).also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
