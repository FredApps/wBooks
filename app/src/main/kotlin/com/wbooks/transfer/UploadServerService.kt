package com.wbooks.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wbooks.WBooksApp
import com.wbooks.R

/**
 * Foreground service that owns the live [UploadServer]. Started/stopped via
 * [TransferController] using the [ACTION_START] / [ACTION_STOP] intent actions.
 *
 * Foreground type "dataSync" matches the manifest declaration and is the right
 * category for "transferring user files over a network".
 */
class UploadServerService : Service() {

    private var server: UploadServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (server != null) return
        val app = application as WBooksApp
        val pin = generatePin()
        val s = UploadServer(port = PORT, booksDir = app.booksDir, pin = pin).also {
            it.start(NANOHTTPD_TIMEOUT, /* daemon = */ true)
        }
        server = s
        app.transferController.publishRunning(PORT, pin)

        ensureChannel()
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.server_running))
            .setContentText(getString(R.string.server_url, "PIN $pin"))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun shutdown() {
        server?.stop()
        server = null
        (application as WBooksApp).transferController.publishStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Upload server", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the file-transfer server is running" }
        )
    }

    companion object {
        const val ACTION_START = "com.wbooks.transfer.START"
        const val ACTION_STOP = "com.wbooks.transfer.STOP"
        private const val PORT = 8080
        private const val NANOHTTPD_TIMEOUT = 30_000
        private const val NOTIF_ID = 17
        private const val CHANNEL_ID = "wbooks_upload"
    }
}
