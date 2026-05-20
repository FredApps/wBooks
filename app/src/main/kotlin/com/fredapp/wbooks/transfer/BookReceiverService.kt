package com.fredapp.wbooks.transfer

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
import com.fredapp.wbooks.R
import com.fredapp.wbooks.WBooksApp
import com.fredapp.wbooks.data.book.BookFormat
import com.fredapp.wbooks.util.uniqueFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/**
 * Watch-side counterpart to the phone companion. Lives alongside the
 * [UploadServer] (which serves the LAN web UI) â€" the two transports are
 * additive, not alternatives.
 *
 * Wire protocol: see [WearProtocol]. List/delete arrive as MessageClient
 * messages on known paths; uploads arrive as ChannelClient channels whose
 * path encodes the filename. Replies for list/delete go back on the same
 * path to the sender's node.
 *
 * Service binding is automatic â€" declaring a subclass of WearableListenerService
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
                        val id = parseString(String(data, Charsets.UTF_8), "id")
                        if (id != null) deleteBook(id)
                        currentLibraryJson()
                    }
                    WearProtocol.PATH_MKDIR -> {
                        val name = parseString(String(data, Charsets.UTF_8), "name")
                        if (name != null) mkdirBook(name)
                        currentLibraryJson()
                    }
                    WearProtocol.PATH_MOVE -> {
                        val json = String(data, Charsets.UTF_8)
                        val id = parseString(json, "id")
                        val folder = parseString(json, "folder") ?: ""
                        if (id != null) moveBook(id, folder)
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
        val folders = app.booksDir.walkTopDown()
            .filter { it.isDirectory && it != app.booksDir }
            .map { it.relativeTo(app.booksDir).invariantSeparatorsPath }
            .sorted()
            .toList()
        return LibraryListJson.encode(books, folders).toByteArray(Charsets.UTF_8)
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
     * opt-out is held in [com.fredapp.wbooks.data.telemetry.CrashReportingPref] (separate
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
            val ok = runCatching {
                client.getInputStream(channel).await().use { input ->
                    dest.outputStream().buffered().use { out ->
                        input.copyToLimited(out, MAX_BOOK_BYTES)
                    }
                }
            }.onFailure { dest.delete() }.isSuccess
            if (ok) {
                notifyReceived(dest.name)
                app.libraryRepository.refresh()
            }
        }
    }

    private suspend fun deleteBook(id: String) {
        val app = application as WBooksApp
        val removed = app.libraryRepository.delete(id)
        if (removed) {
            app.readingPaceRepository.clear(id)
            app.positionsRepository.clear(id)
            app.bookmarksRepository.clear(id)
            app.documentCache.invalidate(id)
        } else {
            // id may be a folder name — delete it and all books inside recursively
            val dir = java.io.File(app.booksDir, id)
            if (dir.isInside(app.booksDir) && dir.canonicalFile != app.booksDir.canonicalFile && dir.isDirectory) {
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val bookId = file.relativeTo(app.booksDir).invariantSeparatorsPath
                    app.readingPaceRepository.clear(bookId)
                    app.positionsRepository.clear(bookId)
                    app.bookmarksRepository.clear(bookId)
                    app.documentCache.invalidate(bookId)
                }
                dir.deleteRecursively()
                app.libraryRepository.refresh()
            }
        }
    }

    private suspend fun mkdirBook(name: String) {
        if (name.isBlank() || name.contains('/') || name.contains('\\')) return
        val app = application as WBooksApp
        val dir = java.io.File(app.booksDir, name)
        if (dir.isInside(app.booksDir) && dir.canonicalFile != app.booksDir.canonicalFile) dir.mkdirs()
    }

    private suspend fun moveBook(id: String, targetFolder: String) {
        val app = application as WBooksApp
        val newId = app.libraryRepository.move(id, targetFolder) ?: return
        if (newId == id) return
        app.readingPaceRepository.moveBookId(id, newId)
        app.positionsRepository.moveBookId(id, newId)
        app.bookmarksRepository.moveBookId(id, newId)
        app.documentCache.moveBookId(id, newId)
    }

    private fun parseString(json: String, key: String): String? {
        val needle = "\"" + key + "\""
        val ki = json.indexOf(needle); if (ki < 0) return null
        val colon = json.indexOf(':', ki + needle.length); if (colon < 0) return null
        val q1 = json.indexOf('"', colon + 1); if (q1 < 0) return null
        val sb = StringBuilder(); var i = q1 + 1
        while (i < json.length) {
            val c = json[i]
            if (c == '"') return sb.toString()
            if (c == '\\' && i + 1 < json.length) {
                when (val esc = json[i + 1]) {
                    '"', '\\', '/' -> sb.append(esc)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(esc)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return null
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

        /** Cap incoming Wear channel uploads to prevent a paired device from filling storage. */
        const val MAX_BOOK_BYTES = 50L * 1024 * 1024
    }
}

private fun File.isInside(root: File): Boolean =
    canonicalFile.toPath().startsWith(root.canonicalFile.toPath())

/**
 * Copy [this] stream to [out], throwing [IOException] if more than [limit] bytes are read.
 * Cleans up gracefully: the caller is responsible for deleting any partially-written destination.
 */
private fun InputStream.copyToLimited(out: OutputStream, limit: Long) {
    var total = 0L
    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
    var n: Int
    while (read(buf).also { n = it } != -1) {
        total += n
        if (total > limit) throw IOException("Book upload exceeds ${limit / 1_048_576} MB limit")
        out.write(buf, 0, n)
    }
}
