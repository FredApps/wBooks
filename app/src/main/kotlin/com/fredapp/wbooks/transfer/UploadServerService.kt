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
            else -> {
                val started = startServer(intent?.getStringExtra(EXTRA_HOST_ADDRESS))
                if (!started) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        // START_NOT_STICKY (not START_STICKY): the upload server is a
        // user-controlled toggle. If the system kills the service under memory
        // pressure, it must stay dead until the user explicitly turns it back
        // on. With START_STICKY Android would restart the service later with a
        // null intent — onStartCommand would fall into the else branch and
        // silently bring the server back up with a fresh PIN, which is exactly
        // the "re-enables itself" complaint the user reported.
        return START_NOT_STICKY
    }

    /** Returns true on success; false if the server failed to bind (e.g. port busy). */
    private fun startServer(hostAddress: String?): Boolean {
        // Order matters: ContextCompat.startForegroundService(...) starts a
        // ~5-second timer the system uses to enforce "this service must promote
        // itself to foreground". We have to call startForeground for EVERY
        // start intent before that timer expires, including the re-entrant
        // "already running" case (otherwise a duplicate intent crashes us with
        // ForegroundServiceDidNotStartInTimeException). The current PIN-bearing
        // notification gets re-published below on the success path so this
        // placeholder is only visible for a frame in the cold-start case.
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification(pin = server?.let { (application as WBooksApp).transferController.state.value.pin }))

        if (server != null) return true
        if (hostAddress.isNullOrBlank()) {
            (application as WBooksApp).transferController.publishStopped()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return false
        }
        val app = application as WBooksApp
        val pin = generatePin()

        val candidate = UploadServer(
            hostAddress = hostAddress,
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
        )
        try {
            candidate.start(NANOHTTPD_TIMEOUT, /* daemon = */ true)
        } catch (t: Throwable) {
            // Most likely: port already bound by a previous instance that
            // hasn't released its socket yet. Surface as "not running" rather
            // than leaving a half-started service hanging on the system.
            io.sentry.Sentry.captureException(t)
            app.transferController.publishStopped()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return false
        }
        server = candidate
        app.transferController.publishRunning(PORT, pin, hostAddress)
        // Refresh the notification now that we know the PIN.
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(pin = pin))
        return true
    }

    private fun buildNotification(pin: String?): Notification {
        val subtitle = if (pin == null) getString(R.string.settings_transfer_subtitle)
        else getString(R.string.server_url, "PIN $pin")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.server_running))
            .setContentText(subtitle)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun shutdown() {
        server?.stop()
        server = null
        (application as WBooksApp).transferController.publishStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * Called when the user swipes the app away from the recents list. The user
     * intent is "I'm done with this app", and a server-running notification
     * that outlives the app's task is confusing and wastes battery. Tear down
     * the server here so the toggle starts fresh on the next launch.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdown()
        stopSelf()
        super.onTaskRemoved(rootIntent)
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
        const val EXTRA_HOST_ADDRESS = "com.fredapp.wbooks.transfer.HOST_ADDRESS"
        private const val PORT = 8080
        private const val NANOHTTPD_TIMEOUT = 30_000
        private const val NOTIF_ID = 17
        private const val CHANNEL_ID = "wbooks_upload"
    }
}
