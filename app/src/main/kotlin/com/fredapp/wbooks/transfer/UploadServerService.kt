package com.fredapp.wbooks.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fredapp.wbooks.WBooksApp
import com.fredapp.wbooks.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the live [UploadServer]. Started/stopped via
 * [TransferController] using the [ACTION_START] / [ACTION_STOP] intent actions.
 *
 * Foreground type "dataSync" matches the manifest declaration and is the right
 * category for "transferring user files over a network".
 */
class UploadServerService : Service() {

    private var server: UploadServer? = null
    /** Scope used to dispatch post-delete DataStore cleanup from NanoHTTPD's thread pool. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        val s = UploadServer(
            port = PORT,
            booksDir = app.booksDir,
            pin = pin,
            settingsRepository = app.settingsRepository,
            crashReportingPref = app.crashReportingPref,
            assets = app.assets,
            onBookDeleted = { bookId ->
                // Clean up reading data for books deleted via the web UI.
                serviceScope.launch {
                    app.readingPaceRepository.clear(bookId)
                    app.positionsRepository.clear(bookId)
                    app.bookmarksRepository.clear(bookId)
                    app.documentCache.invalidate(bookId)
                }
            },
            onBookMoved = { fromBookId, toBookId ->
                serviceScope.launch {
                    app.readingPaceRepository.moveBookId(fromBookId, toBookId)
                    app.positionsRepository.moveBookId(fromBookId, toBookId)
                    app.bookmarksRepository.moveBookId(fromBookId, toBookId)
                    app.readingStatsRepository.moveBookId(fromBookId, toBookId)
                    app.documentCache.moveBookId(fromBookId, toBookId)
                }
            },
            onFolderRenamed = { oldFolder, newFolder ->
                // The rename is already done on disk by handleRename; re-id all books
                // that lived under the old folder so per-book DataStore state follows.
                serviceScope.launch {
                    val books = app.libraryRepository.books.value
                    app.libraryRepository.refresh()
                    for (b in books) {
                        if (b.id.substringBeforeLast('/', "") != oldFolder) continue
                        val tail = b.id.removePrefix("$oldFolder/")
                        val newId = "$newFolder/$tail"
                        app.readingPaceRepository.moveBookId(b.id, newId)
                        app.positionsRepository.moveBookId(b.id, newId)
                        app.bookmarksRepository.moveBookId(b.id, newId)
                        app.readingStatsRepository.moveBookId(b.id, newId)
                        app.documentCache.moveBookId(b.id, newId)
                    }
                }
            },
        ).also {
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
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Upload server", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the web interface is running" }
        )
    }

    companion object {
        const val ACTION_START = "com.fredapp.wbooks.transfer.START"
        const val ACTION_STOP = "com.fredapp.wbooks.transfer.STOP"
        private const val PORT = 8080
        private const val NANOHTTPD_TIMEOUT = 30_000
        private const val NOTIF_ID = 17
        private const val CHANNEL_ID = "wbooks_upload"
    }
}
