package com.fredapp.wbooksutil

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
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/**
 * Minimal Project Gutenberg client. Talks to PG's OPDS Atom feeds â€” the standard
 * way to programmatically browse and download. Endpoints used:
 *
 *   - Search:           https://www.gutenberg.org/ebooks/search.opds/?query=...
 *   - Popular:          https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads
 *   - Recent releases:  https://www.gutenberg.org/ebooks/search.opds/?sort_order=release_date
 *
 * For each result we pick the best download link the reader can open natively
 * (EPUB > TXT) and surface a [GutenbergBook] with the direct URL. [withDownload]
 * streams the raw HTTP body so the caller can pipe it straight to the watch
 * via [WatchRepository.uploadStream] without buffering the whole book in RAM.
 *
 * All blocking HTTP runs on [Dispatchers.IO]. When the calling coroutine is
 * cancelled, the in-flight [HttpURLConnection] is disconnect()ed so the read
 * unwinds with an [java.io.IOException] instead of hanging the IO thread.
 */
class GutenbergRepository {

    /** OPDS feed: text search. */
    suspend fun search(query: String, startIndex: Int = 1): GutenbergPage =
        if (query.isBlank()) GutenbergPage.EMPTY
        else fetchPage(endpoint("ebooks/search.opds/", "query" to query, "start_index" to startIndex.toString()))

    /** OPDS feed: top books sorted by download count. */
    suspend fun popular(startIndex: Int = 1): GutenbergPage =
        fetchPage(endpoint("ebooks/search.opds/", "sort_order" to "downloads", "start_index" to startIndex.toString()))

    /** OPDS feed: newest Project Gutenberg releases. */
    suspend fun recentReleases(startIndex: Int = 1): GutenbergPage =
        fetchPage(endpoint("ebooks/search.opds/", "sort_order" to "release_date", "start_index" to startIndex.toString()))

    /**
     * Open the book's download stream and hand it to [block]. Connection is
     * closed when the block returns or when the surrounding coroutine cancels.
     */
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
        val books = parseFeed(xml).withResolvedSizes()
        return GutenbergPage(
            books = books,
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

    /**
     * Open an HTTP connection with a protocol whitelist (defends against the
     * remote returning `file:` / `ftp:` / `jar:` URLs) and a cancellation hook
     * that disconnects the in-flight request when [job] completes.
     */
    private fun openConnection(url: String, job: Job?): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol in ALLOWED_PROTOCOLS) {
            "Unsupported protocol: ${parsed.protocol}"
        }
        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/atom+xml, */*")
            instanceFollowRedirects = true
        }
        // Check response status before registering the cancellation hook so that
        // the hook isn't left pointing at a connection that was already disconnected
        // by the error path.
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            error("Project Gutenberg returned HTTP $code")
        }
        // disconnect() from another thread causes the blocking read on this
        // connection to throw IOException, which propagates back as the
        // CancellationException the coroutine expected.
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
            // The /ebooks.opds/ "popular" feed embeds direct download links per
            // entry; the /search.opds/ feed only has a subsection link to a
            // per-book OPDS doc. For search results we synthesize the download
            // URL from the numeric book id encoded in <id> ("…/ebooks/2701.opds").
            val acq = entry.select("link[rel=http://opds-spec.org/acquisition]")
            val preferred = if (acq.isNotEmpty()) {
                pickPreferred(acq)
            } else {
                bookIdFromOpdsUrl(id)?.let { numeric ->
                    Acquisition(
                        url = "$BASE_NO_SLASH/ebooks/$numeric.epub3.images",
                        extension = "epub",
                        sizeBytes = null,
                    )
                }
            } ?: continue
            val pageUrl = bookPageUrl(id)
            val contentText = entry.selectFirst("content")?.text()
                ?.trim()?.takeIf { it.isNotEmpty() }
            val author = authorName(entry) ?: contentText
            out += GutenbergBook(
                id = id,
                title = title,
                author = author,
                summary = entry.selectFirst("summary")?.text()
                    ?.trim()?.takeIf { it.isNotEmpty() },
                downloadUrl = preferred.url,
                extension = preferred.extension,
                sizeBytes = preferred.sizeBytes,
                infoUrl = pageUrl,
                releaseDate = releaseDate(entry),
            )
        }
        return out
    }

    internal fun nextPageUrl(xml: String): String? {
        val doc = Jsoup.parse(xml, BASE, Parser.xmlParser())
        val href = doc.selectFirst("link[rel=next]")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: return null
        return resolveUrl(href)
    }

    /** Extract the numeric book id from an `<id>https://www.gutenberg.org/ebooks/2701.opds</id>` URL. */
    private fun bookIdFromOpdsUrl(idUrl: String): String? {
        val m = Regex("/ebooks/(\\d+)(?:\\.opds)?\\b").find(idUrl) ?: return null
        return m.groupValues[1]
    }

    private fun bookPageUrl(id: String): String? {
        val numeric = bookIdFromOpdsUrl(id)
            ?: Regex("""\burn:gutenberg:(\d+)\b""").find(id)?.groupValues?.get(1)
            ?: return null
        return "$BASE_NO_SLASH/ebooks/$numeric"
    }

    private fun authorName(entry: org.jsoup.nodes.Element): String? {
        val atomAuthor = listOf(
            "author > name",
            "creator",
            "author",
        ).firstNotNullOfOrNull { selector ->
            entry.selectFirst(selector)?.text()?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (atomAuthor != null) return atomAuthor
        return entry.children().firstOrNull { child ->
            child.tagName().substringAfter(':') == "creator"
        }?.text()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun releaseDate(entry: org.jsoup.nodes.Element): String? {
        val raw = entry.selectFirst("published")?.text()
            ?: entry.selectFirst("updated")?.text()
            ?: return null
        return raw.trim().take(10).takeIf { it.isNotEmpty() }
    }

    private data class Acquisition(val url: String, val extension: String, val sizeBytes: Long?)

    /** Pick the first acquisition link whose MIME type we know how to parse. */
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
                if (book.sizeBytes != null) {
                    book
                } else {
                    val size = throttle.withPermit { contentLength(book.downloadUrl) }
                    book.copy(sizeBytes = size)
                }
            }
        }.awaitAll()
    }

    private fun contentLength(url: String): Long? {
        val conn = runCatching {
            val parsed = URL(url)
            require(parsed.protocol in ALLOWED_PROTOCOLS) {
                "Unsupported protocol: ${parsed.protocol}"
            }
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

    /**
     * Resolve [href] against [BASE]. Handles absolute URLs (`https://...`),
     * protocol-relative URLs (`//host/path`), root-relative (`/path`), and
     * plain relative (`path`).
     */
    private fun resolveUrl(href: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> BASE_NO_SLASH + href
        else -> "$BASE_NO_SLASH/$href"
    }

    /** Build `<BASE>/path?k1=v1&k2=v2` with proper URL-encoding. */
    private fun endpoint(path: String, vararg params: Pair<String, String>): String {
        val query = if (params.isEmpty()) "" else params.joinToString("&", prefix = "?") { (k, v) ->
            "$k=" + URLEncoder.encode(v, StandardCharsets.UTF_8)
        }
        return "$BASE_NO_SLASH/$path$query"
    }

    companion object {
        private const val BASE = "https://www.gutenberg.org/"
        private const val BASE_NO_SLASH = "https://www.gutenberg.org"
        private const val USER_AGENT = "wBooks-companion/0.3 (https://github.com/FredApps/wBooks)"
        private const val MAX_SIZE_LOOKUPS = 6
        private val ALLOWED_PROTOCOLS = setOf("https", "http")
    }
}

data class GutenbergPage(
    val books: List<GutenbergBook>,
    val hasMore: Boolean,
) {
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
    val infoUrl: String?,
    val releaseDate: String?,
)
