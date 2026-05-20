package com.wbooks.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.wbooks.R
import com.wbooks.WBooksApp
import com.wbooks.data.book.BookFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.net.URLDecoder

/**
 * Watch-side counterpart to the phone companion. Lives alongside the
 * [UploadServer] (which serves the LAN web UI) — the two transports are
 * additive, not alternatives.
 *
 * Wire protocol: see [WearProtocol]. List/delete arrive as MessageClient
 * messages on known paths; uploads arrive as ChannelClient channels whose
 * path encodes the filename. Replies for list/delete go back on the same
 * path to the sender's node.
 *
 * Service binding is automatic — declaring a subclass of WearableListenerService
 * in the manifest registers it with Google Play Services; we don't need
 * a separate IntentService or explicit startService call.
 */
class BookReceiverService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        // The service is destroyed after an idle window; any in-flight onRequest /
        // onChannelOpened work must be cancelled to avoid orphan coroutines holding
        // references to a destroyed Service or writing half-files.
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Request/response handler used by [com.google.android.gms.wearable.MessageClient.sendRequest].
     * `onRequest` is invoked on the binder thread; we must return a Task immediately and
     * let the actual work complete asynchronously. The work is launched on [scope] (which
     * runs on Dispatchers.IO and gets cancelled in [onDestroy]) and resolves the
     * [TaskCompletionSource] when done.
     */
    override fun onRequest(nodeId: String, path: String, data: ByteArray): Task<ByteArray> {
        val tcs = TaskCompletionSource<ByteArray>()
        scope.launch {
            try {
                val result = when (path) {
                    WearProtocol.PATH_LIST -> currentLibraryJson()
                    WearProtocol.PATH_DELETE -> {
                        val id = parseId(String(data, Charsets.UTF_8))
                        if (id != null) deleteBook(id)
                        currentLibraryJson()
                    }
                    WearProtocol.PATH_STATS -> currentStatsJson()
                    WearProtocol.PATH_SETTINGS_GET -> currentSettingsJson()
                    WearProtocol.PATH_SETTINGS_SET -> {
                        applySettingsUpdate(data)
                        currentSettingsJson()
                    }
                    else -> ByteArray(0)
                }
                tcs.setResult(result)
            } catch (t: Throwable) {
                tcs.setException(t as? Exception ?: RuntimeException(t))
            }
        }
        return tcs.task
    }

    private suspend fun currentStatsJson(): ByteArray {
        val app = application as WBooksApp
        val snapshot = app.readingStatsRepository.snapshot()
        return StatsJson.encode(snapshot).toByteArray(Charsets.UTF_8)
    }

    private suspend fun currentLibraryJson(): ByteArray {
        val app = application as WBooksApp
        app.libraryRepository.refresh()
        val books = app.libraryRepository.books.value.map { b ->
            BookSummary(id = b.id, title = b.title, format = b.format.name)
        }
        return LibraryListJson.encode(books).toByteArray(Charsets.UTF_8)
    }

    private suspend fun currentSettingsJson(): ByteArray {
        val app = application as WBooksApp
        val s = app.settingsRepository.snapshot()
        val snapshot = SettingsSnapshot(
            mode = s.mode.name,
            font = s.font.name,
            textSizeSp = s.textSizeSp,
            sentenceTextSizeSp = s.sentenceTextSizeSp,
            textColorArgb = s.textColorArgb,
            autoscrollEnabled = s.autoscrollEnabled,
            autoscrollSpeed = s.autoscrollSpeed,
            screenBrightness = s.screenBrightness,
            speedreadWpm = s.speedreadWpm,
            theme = s.theme.name,
            crashReportingEnabled = app.crashReportingPref.enabled.value,
        )
        return SettingsJson.encode(snapshot).toByteArray(Charsets.UTF_8)
    }

    /**
     * Parse the `{"key","value"}` payload and route it: the crash-reporting
     * opt-out is held in [com.wbooks.data.telemetry.CrashReportingPref] (separate
     * from DataStore so it can be read synchronously at app start), everything
     * else lives in `SettingsRepository`.
     */
    private suspend fun applySettingsUpdate(data: ByteArray) {
        val (key, value) = SettingsJson.decodeSetRequest(data) ?: return
        val app = application as WBooksApp
        if (key == "crashReportingEnabled") {
            val b = value.equals("true", ignoreCase = true)
            app.crashReportingPref.setEnabled(b)
        } else {
            app.settingsRepository.applyWireKey(key, value)
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(WearProtocol.PATH_UPLOAD_PREFIX)) return
        val filename = URLDecoder.decode(
            channel.path.removePrefix(WearProtocol.PATH_UPLOAD_PREFIX),
            "UTF-8",
        )
        if (filename.isBlank()) return
        val ext = filename.substringAfterLast('.', "")
        if (BookFormat.fromExtension(ext) == null) return
        val safe = filename.replace(Regex("[\\\\/:*?\"<>|]"), "_")

        val app = application as WBooksApp
        scope.launch {
            val client = Wearable.getChannelClient(this@BookReceiverService)
            val dest = uniqueFile(app.booksDir, safe)
            client.getInputStream(channel).await().use { input ->
                dest.outputStream().buffered().use { out -> input.copyTo(out) }
            }
            notifyReceived(dest.name)
            app.libraryRepository.refresh()
        }
    }

    private fun deleteBook(id: String) {
        val app = application as WBooksApp
        // LibraryRepository.delete is synchronous and idempotent.
        app.libraryRepository.delete(id)
    }

    /**
     * Extract the `"id":"..."` value from a small JSON payload without pulling in a JSON lib.
     *
     * Safe by virtue of [com.wbooks.data.library.LibraryRepository.delete]: an id that doesn't
     * exactly match an existing `Book.id` is a no-op, and book ids are derived from the file's
     * relative path under `booksDir` — there's no path construction here that could escape.
     * If a future caller starts treating ids as paths, tighten this with a stricter check.
     */
    private fun parseId(json: String): String? {
        val key = "\"id\""
        val ki = json.indexOf(key)
        if (ki < 0) return null
        val colon = json.indexOf(':', ki + key.length)
        if (colon < 0) return null
        val q1 = json.indexOf('"', colon + 1)
        if (q1 < 0) return null
        val q2 = json.indexOf('"', q1 + 1)
        if (q2 < 0) return null
        return json.substring(q1 + 1, q2)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 2
        while (true) {
            candidate = File(dir, "$base ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun notifyReceived(filename: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Book received", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown when the phone sends a book" }
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(filename)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(filename.hashCode(), notif)
    }

    private companion object {
        const val CHANNEL_ID = "wbooks_received"
    }
}
