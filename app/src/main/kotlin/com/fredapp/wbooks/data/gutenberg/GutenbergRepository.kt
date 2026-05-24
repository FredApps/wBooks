package com.fredapp.wbooks.data.gutenberg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

class GutenbergRepository {
    suspend fun search(query: String, startIndex: Int = 1): GutenbergPage =
        if (query.isBlank()) GutenbergPage.EMPTY
        else fetchPage(endpoint("ebooks/search.opds/", "query" to query, "start_index" to startIndex.toString()))

    suspend fun popular(startIndex: Int = 1): GutenbergPage =
        fetchPage(endpoint("ebooks/search.opds/", "sort_order" to "downloads", "start_index" to startIndex.toString()))

    suspend fun recentReleases(startIndex: Int = 1): GutenbergPage =
        fetchPage(endpoint("ebooks/search.opds/", "sort_order" to "release_date", "start_index" to startIndex.toString()))

    suspend fun <T> withDownload(book: GutenbergBook, block: suspend (InputStream, Long) -> T): T =
        withContext(Dispatchers.IO) {
            val conn = openConnection(book.downloadUrl, coroutineContext[Job])
            try {
                conn.inputStream.use { input -> block(input, conn.contentLengthLong) }
            } finally {
                conn.disconnect()
            }
        }

    private suspend fun fetchPage(url: String): GutenbergPage {
        val xml = fetch(url)
        return GutenbergPage(
            books = parseFeed(xml).withResolvedSizes(),
            hasMore = nextPageUrl(xml) != null,
        )
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val conn = openConnection(url, coroutineContext[Job])
        try {
            conn.inputStream.use { it.bufferedReader(Charsets.UTF_8).readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(url: String, job: Job?): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol in ALLOWED_PROTOCOLS) { "Unsupported protocol: ${parsed.protocol}" }
        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/atom+xml, */*")
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            error("Project Gutenberg returned HTTP $code")
        }
        job?.invokeOnCompletion { if (it != null) conn.disconnect() }
        return conn
    }

    internal fun parseFeed(xml: String): List<GutenbergBook> {
        val doc = Jsoup.parse(xml, BASE, Parser.xmlParser())
        val out = mutableListOf<GutenbergBook>()
        for (entry in doc.select("entry")) {
            val title = entry.selectFirst("title")?.text()?.trim().orEmpty()
            if (title.isEmpty()) continue
            val id = entry.selectFirst("id")?.text()?.trim().orEmpty()
            val acq = entry.select("link[rel=http://opds-spec.org/acquisition]")
            val preferred = if (acq.isNotEmpty()) {
                pickPreferred(acq)
            } else {
                bookIdFromOpdsUrl(id)?.let { numeric ->
                    Acquisition("$BASE_NO_SLASH/ebooks/$numeric.epub3.images", "epub", null)
                }
            } ?: continue
            val contentText = entry.selectFirst("content")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            out += GutenbergBook(
                id = id,
                title = title,
                author = authorName(entry) ?: contentText,
                summary = entry.selectFirst("summary")?.text()?.trim()?.takeIf { it.isNotEmpty() },
                downloadUrl = preferred.url,
                extension = preferred.extension,
                sizeBytes = preferred.sizeBytes,
                releaseDate = releaseDate(entry),
            )
        }
        return out
    }

    private fun nextPageUrl(xml: String): String? {
        val doc = Jsoup.parse(xml, BASE, Parser.xmlParser())
        return doc.selectFirst("link[rel=next]")?.attr("href")?.takeIf { it.isNotBlank() }
    }

    private fun bookIdFromOpdsUrl(idUrl: String): String? =
        Regex("/ebooks/(\\d+)(?:\\.opds)?\\b").find(idUrl)?.groupValues?.get(1)

    private fun authorName(entry: org.jsoup.nodes.Element): String? {
        val atomAuthor = listOf("author > name", "creator", "author").firstNotNullOfOrNull { selector ->
            entry.selectFirst(selector)?.text()?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (atomAuthor != null) return atomAuthor
        return entry.children().firstOrNull { child -> child.tagName().substringAfter(':') == "creator" }
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun releaseDate(entry: org.jsoup.nodes.Element): String? {
        val raw = entry.selectFirst("published")?.text() ?: return null
        return raw.trim().take(10).takeIf { it.isNotEmpty() }
    }

    private data class Acquisition(val url: String, val extension: String, val sizeBytes: Long?)

    private fun pickPreferred(links: org.jsoup.select.Elements): Acquisition? {
        var epub: Acquisition? = null
        var txt: Acquisition? = null
        for (link in links) {
            val href = link.attr("href")
            val type = link.attr("type").substringBefore(';').trim().lowercase()
            if (href.isEmpty()) continue
            val abs = resolveUrl(href)
            val sizeBytes = link.attr("length").toLongOrNull()?.takeIf { it > 0L }
            when (type) {
                "application/epub+zip" -> if (epub == null) epub = Acquisition(abs, "epub", sizeBytes)
                "text/plain" -> if (txt == null) txt = Acquisition(abs, "txt", sizeBytes)
            }
        }
        return epub ?: txt
    }

    private suspend fun List<GutenbergBook>.withResolvedSizes(): List<GutenbergBook> = coroutineScope {
        if (isEmpty()) return@coroutineScope this@withResolvedSizes
        val throttle = Semaphore(MAX_SIZE_LOOKUPS)
        map { book ->
            async(Dispatchers.IO) {
                if (book.sizeBytes != null) book
                else book.copy(sizeBytes = throttle.withPermit { contentLength(book.downloadUrl) })
            }
        }.awaitAll()
    }

    private fun contentLength(url: String): Long? {
        val conn = runCatching {
            val parsed = URL(url)
            require(parsed.protocol in ALLOWED_PROTOCOLS) { "Unsupported protocol: ${parsed.protocol}" }
            (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = true
            }
        }.getOrNull() ?: return null
        return try {
            val code = conn.responseCode
            if (code in 200..299) conn.contentLengthLong.takeIf { it > 0L } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun resolveUrl(href: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> BASE_NO_SLASH + href
        else -> "$BASE_NO_SLASH/$href"
    }

    private fun endpoint(path: String, vararg params: Pair<String, String>): String {
        val query = if (params.isEmpty()) "" else params.joinToString("&", prefix = "?") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
        return "$BASE_NO_SLASH/$path$query"
    }

    companion object {
        private const val BASE = "https://www.gutenberg.org/"
        private const val BASE_NO_SLASH = "https://www.gutenberg.org"
        private const val USER_AGENT = "wBooks-watch/0.8 (https://github.com/FredApps/wBooks)"
        private const val MAX_SIZE_LOOKUPS = 4
        private val ALLOWED_PROTOCOLS = setOf("https", "http")
    }
}

data class GutenbergPage(val books: List<GutenbergBook>, val hasMore: Boolean) {
    companion object {
        val EMPTY = GutenbergPage(emptyList(), hasMore = false)
    }
}

data class GutenbergBook(
    val id: String,
    val title: String,
    val author: String?,
    val summary: String?,
    val downloadUrl: String,
    val extension: String,
    val sizeBytes: Long?,
    val releaseDate: String?,
)
