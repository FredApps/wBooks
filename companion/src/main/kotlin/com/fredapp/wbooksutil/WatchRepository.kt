package com.fredapp.wbooksutil

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
            val bytes = messageClient.sendRequest(node.id, WearProtocol.PATH_LIST, ByteArray(0)).await()
            Result.Ok(LibraryListJson.decode(bytes))
        }.getOrElse { Result.Error(it.message ?: "Failed to fetch library") }
    }

    suspend fun fetchStats(): Result<StatsSummary> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            val bytes = messageClient.sendRequest(node.id, WearProtocol.PATH_STATS, ByteArray(0)).await()
            Result.Ok(StatsJson.decode(bytes))
        }.getOrElse { Result.Error(it.message ?: "Failed to fetch stats") }
    }

    suspend fun fetchSettings(): Result<SettingsSnapshot> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            val bytes = messageClient.sendRequest(node.id, WearProtocol.PATH_SETTINGS_GET, ByteArray(0)).await()
            val snap = SettingsJson.decode(bytes) ?: return@runCatching Result.Error("Empty settings response")
            Result.Ok(snap)
        }.getOrElse { Result.Error(it.message ?: "Failed to fetch settings") }
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
            val bytes = messageClient.sendRequest(node.id, WearProtocol.PATH_SETTINGS_SET, payload).await()
            val snap = SettingsJson.decode(bytes) ?: return@runCatching Result.Error("Empty settings response")
            Result.Ok(snap)
        }.getOrElse { Result.Error(it.message ?: "Failed to update setting") }
    }

    suspend fun deleteBook(id: String): Result<LibrarySnapshot> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            val payload = org.json.JSONObject().put("id", id).toString().toByteArray(Charsets.UTF_8)
            val bytes = messageClient.sendRequest(node.id, WearProtocol.PATH_DELETE, payload).await()
            Result.Ok(LibraryListJson.decode(bytes))
        }.getOrElse { Result.Error(it.message ?: "Delete failed") }
    }

    suspend fun uploadBook(uri: Uri, filename: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isSupportedBookFilename(filename)) {
            return@withContext Result.Error("Unsupported file type")
        }
        val input = appContext.contentResolver.openInputStream(uri)
            ?: return@withContext Result.Error("Cannot open file")
        uploadStream(input, filename)
    }

    /** Push [input] to the watch under [filename]; closes [input] when done. */
    suspend fun uploadStream(input: InputStream, filename: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!isSupportedBookFilename(filename)) {
                input.close()
                return@withContext Result.Error("Unsupported file type")
            }
            val node = bestNode() ?: run { input.close(); return@withContext Result.NoWatch }
            runCatching {
                input.use { stream ->
                    val path = WearProtocol.PATH_UPLOAD_PREFIX + URLEncoder.encode(filename, StandardCharsets.UTF_8)
                    val channel = channelClient.openChannel(node.id, path).await()
                    try {
                        val out = channelClient.getOutputStream(channel).await()
                        out.use { stream.copyTo(it) }
                    } finally {
                        channelClient.close(channel).await()
                    }
                }
                Result.Ok(Unit)
            }.getOrElse { Result.Error(it.message ?: "Upload failed") }
        }

    suspend fun mkdirBook(name: String): Result<LibrarySnapshot> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            val payload = """{"name":${jsonString(name)}}""".toByteArray(Charsets.UTF_8)
            val bytes = messageClient.sendRequest(node.id, WearProtocol.PATH_MKDIR, payload).await()
            Result.Ok(LibraryListJson.decode(bytes))
        }.getOrElse { Result.Error(it.message ?: "mkdir failed") }
    }

    suspend fun moveBook(bookId: String, targetFolder: String): Result<LibrarySnapshot> =
        withContext(Dispatchers.IO) {
            val node = bestNode() ?: return@withContext Result.NoWatch
            runCatching {
                val payload = """{"id":${jsonString(bookId)},"folder":${jsonString(targetFolder)}}"""
                    .toByteArray(Charsets.UTF_8)
                val bytes = messageClient.sendRequest(node.id, WearProtocol.PATH_MOVE, payload).await()
                Result.Ok(LibraryListJson.decode(bytes))
            }.getOrElse { Result.Error(it.message ?: "move failed") }
        }

    suspend fun hasReachableWatch(): Boolean = withContext(Dispatchers.IO) {
        bestNode() != null
    }

    /** Find a connected node that has the wBooks watch app installed. */
    private suspend fun bestNode(): Node? {
        val info = runCatching {
            capabilityClient
                .getCapability(WBOOKS_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
        }.getOrNull()
        val capabilityNode = info
            ?.nodes
            ?.let { nodes -> nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() }
        if (capabilityNode != null) return capabilityNode

        val connectedNodes = runCatching { nodeClient.connectedNodes.await() }.getOrNull().orEmpty()
        return connectedNodes.firstOrNull { it.isNearby } ?: connectedNodes.firstOrNull()
    }

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
    }
}
