package com.wbooks.companion

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

/**
 * Thin wrapper over the Wear Data Layer. The watch app advertises a
 * [WBOOKS_CAPABILITY] in res/values/wear.xml — picking the first node that
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

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data object NoWatch : Result<Nothing>()
        data class Error(val message: String) : Result<Nothing>()
    }

    suspend fun fetchLibrary(): Result<List<BookSummary>> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            val bytes = messageClient.sendRequest(node.id, WearProtocol.PATH_LIST, ByteArray(0)).await()
            Result.Ok(LibraryListJson.decode(bytes))
        }.getOrElse { Result.Error(it.message ?: "Failed to fetch library") }
    }

    suspend fun deleteBook(id: String): Result<List<BookSummary>> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        runCatching {
            val payload = """{"id":"${escape(id)}"}""".toByteArray(Charsets.UTF_8)
            val bytes = messageClient.sendRequest(node.id, WearProtocol.PATH_DELETE, payload).await()
            Result.Ok(LibraryListJson.decode(bytes))
        }.getOrElse { Result.Error(it.message ?: "Delete failed") }
    }

    suspend fun uploadBook(uri: Uri, filename: String): Result<Unit> = withContext(Dispatchers.IO) {
        val node = bestNode() ?: return@withContext Result.NoWatch
        val resolver = appContext.contentResolver
        val input: InputStream = resolver.openInputStream(uri)
            ?: return@withContext Result.Error("Cannot open file")
        runCatching {
            input.use { stream ->
                val path = WearProtocol.PATH_UPLOAD_PREFIX + URLEncoder.encode(filename, "UTF-8")
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

    /** Find a connected node that has the wBooks watch app installed. */
    private suspend fun bestNode(): Node? {
        val info = runCatching {
            capabilityClient
                .getCapability(WBOOKS_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
        }.getOrNull() ?: return null
        return info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        /** Must match the capability the watch app advertises in res/values/wear.xml. */
        const val WBOOKS_CAPABILITY = "wbooks_receiver"
    }
}
