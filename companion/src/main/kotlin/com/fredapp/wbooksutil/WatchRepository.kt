package com.fredapp.wbooksutil

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Thin wrapper over the Wear Data Layer. The watch app advertises a
 * [WBOOKS_CAPABILITY] in res/values/wear.xml â€" picking the first node that
 * declares it is enough for the typical 1 watch / 1 phone pairing.
 *
 * - [fetchLibrary] / [deleteBook] use [com.google.android.gms.wearable.MessageClient.sendRequest]
 *   for round-trip request/response.
 * - [uploadBook] opens a [com.google.android.gms.wearable.ChannelClient] channel
 *   whose path encodes the filename, then streams the bytes.
 */
class WatchRepository(context: Context) {

    private val appContext = context.applicationContext
    private val capabilityClient = Wearable.getCapabilityClient(appContext)
    private val messageClient = Wearable.getMessageClient(appContext)
    private val channelClient = Wearable.getChannelClient(appContext)
    private val nodeClient = Wearable.getNodeClient(appContext)

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data object NoWatch : Result<Nothing>()
        data class Error(val message: String) : Result<Nothing>()
    }

    suspend fun fetchLibrary(): Result<LibrarySnapshot> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            val bytes = sendRequest(node, WearProtocol.PATH_LIST, ByteArray(0))
            Result.Ok(LibraryListJson.decode(bytes))
        }.getOrElse { it.toFetchResult("Failed to fetch library") }
    }

    suspend fun syncPull(): Result<ByteArray> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            Result.Ok(sendRequest(node, WearProtocol.PATH_SYNC_PULL, ByteArray(0)))
        }.getOrElse { it.toFetchResult("Failed to sync") }
    }

    suspend fun syncPush(payload: ByteArray): Result<ByteArray> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            Result.Ok(sendRequest(node, WearProtocol.PATH_SYNC_PUSH, payload))
        }.getOrElse { it.toConnectionOrActionError("Failed to push sync queue") }
    }

    suspend fun fetchStats(): Result<StatsSummary> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            val bytes = sendRequest(node, WearProtocol.PATH_STATS, ByteArray(0))
            Result.Ok(StatsJson.decode(bytes))
        }.getOrElse { it.toFetchResult("Failed to fetch stats") }
    }

    suspend fun fetchSettings(): Result<SettingsSnapshot> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            val bytes = sendRequest(node, WearProtocol.PATH_SETTINGS_GET, ByteArray(0))
            val snap = SettingsJson.decode(bytes) ?: return@runCatching Result.Error("Empty settings response")
            Result.Ok(snap)
        }.getOrElse { it.toFetchResult("Failed to fetch settings") }
    }

    /**
     * Send a single keyed update to the watch and return the resulting snapshot.
     * The watch echoes the full state back on the same path so callers don't
     * have to re-fetch separately.
     */
    suspend fun setSetting(key: String, value: Any): Result<SettingsSnapshot> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            val payload = SettingsJson.encodeSetRequest(key, value)
            val bytes = sendRequest(node, WearProtocol.PATH_SETTINGS_SET, payload)
            val snap = SettingsJson.decode(bytes) ?: return@runCatching Result.Error("Empty settings response")
            Result.Ok(snap)
        }.getOrElse { it.toConnectionOrActionError("Failed to update setting") }
    }

    suspend fun deleteBook(id: String): Result<LibrarySnapshot> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            val payload = org.json.JSONObject().put("id", id).toString().toByteArray(Charsets.UTF_8)
            val bytes = sendRequest(node, WearProtocol.PATH_DELETE, payload)
            Result.Ok(LibraryListJson.decode(bytes))
        }.getOrElse { it.toConnectionOrActionError("Delete failed") }
    }

    suspend fun uploadBook(
        uri: Uri,
        filename: String,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isSupportedBookFilename(filename)) {
            return@withContext Result.Error("Unsupported file type")
        }
        val total = querySize(uri) ?: -1L
        val input = appContext.contentResolver.openInputStream(uri)
            ?: return@withContext Result.Error("Cannot open file")
        uploadStream(input, filename, total, onProgress = onProgress)
    }

    /**
     * Push [input] to the watch under [filename]; closes [input] when done.
     * If [totalBytes] >= 0, [onProgress] is called periodically with the cumulative
     * bytes sent and the total — used by the UI to drive a determinate progress bar.
     * If totalBytes is negative, progress is reported with -1 total (indeterminate).
     */
    suspend fun uploadStream(
        input: InputStream,
        filename: String,
        totalBytes: Long = -1L,
        overwrite: Boolean = false,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!isSupportedBookFilename(filename)) {
                input.close()
                return@withContext Result.Error("Unsupported file type")
            }
            val node = bestNode() ?: run { input.close(); return@withContext Result.NoWatch }
            runCatching {
                input.use { stream ->
                    val path = WearUploadPath.encode(filename, totalBytes, overwrite)
                    val channel = channelClient.openChannel(node.id, path).await()
                    try {
                        val out = channelClient.getOutputStream(channel).await()
                        out.use { sink ->
                            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                            var sent = 0L
                            onProgress(0L, totalBytes)
                            while (true) {
                                val n = stream.read(buf)
                                if (n < 0) break
                                sink.write(buf, 0, n)
                                sent += n
                                onProgress(sent, totalBytes)
                            }
                        }
                    } finally {
                        channelClient.close(channel).await()
                    }
                }
                Result.Ok(Unit)
            }.getOrElse { it.toConnectionOrActionError("Upload failed") }
        }

    suspend fun downloadBook(
        bookId: String,
        destination: File,
        expectedBytes: Long = -1L,
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        destination.parentFile?.mkdirs()
        val temp = File.createTempFile("wbooks-download-", ".tmp", destination.parentFile)
        runCatching {
            val encoded = URLEncoder.encode(bookId, "UTF-8")
            val channel = channelClient.openChannel(node.id, WearProtocol.PATH_BOOK_DOWNLOAD_PREFIX + encoded).await()
            try {
                channelClient.getInputStream(channel).await().use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var received = 0L
                        onProgress(0L, expectedBytes)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            received += n
                            onProgress(received, expectedBytes)
                        }
                        if (expectedBytes >= 0L && received != expectedBytes) {
                            throw IOException("Download ended after $received of $expectedBytes bytes")
                        }
                    }
                }
            } finally {
                channelClient.close(channel).await()
            }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Result.Ok(Unit)
        }.onFailure { temp.delete() }
            .getOrElse { it.toConnectionOrActionError("Download failed") }
    }

    internal object WearUploadPath {
        fun encode(filename: String, totalBytes: Long, overwrite: Boolean): String {
            val encoded = URLEncoder.encode(filename, "UTF-8")
            val params = buildList {
                if (totalBytes >= 0L) add("bytes=$totalBytes")
                if (overwrite) add("overwrite=1")
            }
            return WearProtocol.PATH_UPLOAD_PREFIX + encoded + params.joinToString("&", prefix = "?")
                .takeIf { params.isNotEmpty() }.orEmpty()
        }
    }

    private fun querySize(uri: Uri): Long? {
        val resolver = appContext.contentResolver
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
            }
        }
        return null
    }

    suspend fun mkdirBook(name: String): Result<LibrarySnapshot> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            val payload = """{"name":${jsonString(name)}}""".toByteArray(Charsets.UTF_8)
            val bytes = sendRequest(node, WearProtocol.PATH_MKDIR, payload)
            Result.Ok(LibraryListJson.decode(bytes))
            }.getOrElse { it.toConnectionOrActionError("mkdir failed") }
    }

    suspend fun renameFolder(oldName: String, newName: String): Result<LibrarySnapshot> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            val payload = """{"from":${jsonString(oldName)},"to":${jsonString(newName)}}"""
                .toByteArray(Charsets.UTF_8)
            val bytes = sendRequest(node, WearProtocol.PATH_RENAME, payload)
            Result.Ok(LibraryListJson.decode(bytes))
            }.getOrElse { it.toConnectionOrActionError("rename failed") }
    }

    suspend fun moveBook(bookId: String, targetFolder: String): Result<LibrarySnapshot> =
        withContext(Dispatchers.IO) {
            val node = bestNode() ?: return@withContext Result.NoWatch
            runCatching {
                val payload = """{"id":${jsonString(bookId)},"folder":${jsonString(targetFolder)}}"""
                    .toByteArray(Charsets.UTF_8)
                val bytes = sendRequest(node, WearProtocol.PATH_MOVE, payload)
                Result.Ok(LibraryListJson.decode(bytes))
            }.getOrElse { it.toConnectionOrActionError("move failed") }
        }

    suspend fun reorderBooks(folder: String, orderedIds: List<String>): Result<LibrarySnapshot> =
        withContext(Dispatchers.IO) {
            val node = bestNode() ?: return@withContext Result.NoWatch
            runCatching {
                val order = orderedIds.joinToString(",", prefix = "[", postfix = "]") { jsonString(it) }
                val payload = """{"folder":${jsonString(folder)},"order":$order}"""
                    .toByteArray(Charsets.UTF_8)
                val bytes = sendRequest(node, WearProtocol.PATH_REORDER, payload)
                Result.Ok(LibraryListJson.decode(bytes))
            }.getOrElse { it.toConnectionOrActionError("reorder failed") }
        }

    suspend fun hasReachableWatch(): Boolean = withContext(Dispatchers.IO) {
        bestNode() != null
    }

    /** Find a connected node that has the wBooks watch app installed. */
    private suspend fun bestNode(): Node? {
        val info = withTimeoutOrNull(NODE_LOOKUP_TIMEOUT_MS) {
            runCatching {
                capabilityClient
                    .getCapability(WBOOKS_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                    .await()
            }.getOrNull()
        }
        val capabilityNode = info
            ?.nodes
            ?.let { nodes -> nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() }
        if (capabilityNode != null) return capabilityNode

        val connectedNodes = withTimeoutOrNull(NODE_LOOKUP_TIMEOUT_MS) {
            runCatching { nodeClient.connectedNodes.await() }.getOrNull().orEmpty()
        }.orEmpty()
        return connectedNodes.firstOrNull { it.isNearby } ?: connectedNodes.firstOrNull()
    }

    private suspend fun sendRequest(node: Node, path: String, payload: ByteArray): ByteArray =
        withTimeout(REQUEST_TIMEOUT_MS) {
            messageClient.sendRequest(node.id, path, payload).await()
        }

    private fun <T> Throwable.toFetchResult(fallback: String): Result<T> =
        if (this is TimeoutCancellationException) Result.NoWatch
        else Result.Error(message ?: fallback)

    private fun <T> Throwable.toActionError(fallback: String): Result<T> =
        Result.Error(if (this is TimeoutCancellationException) "Watch did not respond." else message ?: fallback)

    private fun <T> Throwable.toConnectionOrActionError(fallback: String): Result<T> =
        if (isWatchConnectionFailure()) Result.NoWatch else toActionError(fallback)

    private fun Throwable.isWatchConnectionFailure(): Boolean =
        this is TimeoutCancellationException ||
            this is IOException ||
            this is com.google.android.gms.common.api.ApiException ||
            message?.contains("disconnected", ignoreCase = true) == true ||
            message?.contains("timeout", ignoreCase = true) == true ||
            message?.contains("timed out", ignoreCase = true) == true

    companion object {
        /** Must match the capability the watch app advertises in res/values/wear.xml. */
        const val WBOOKS_CAPABILITY = "wbooks_receiver"

        private fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
            return sb.append('"').toString()
        }

        private fun isSupportedBookFilename(filename: String): Boolean {
            val ext = filename.substringAfterLast('.', "").lowercase()
            return ext in SUPPORTED_BOOK_EXTENSIONS
        }

        private val SUPPORTED_BOOK_EXTENSIONS = setOf(
            "epub",
            "txt",
            "fb2",
            "html",
            "htm",
            "xhtml",
            "docx",
            "odt",
        )
        private const val NODE_LOOKUP_TIMEOUT_MS = 5_000L
        private const val REQUEST_TIMEOUT_MS = 12_000L
    }
}
