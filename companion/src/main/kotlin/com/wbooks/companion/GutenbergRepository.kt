package com.wbooks.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Project Gutenberg client. Talks to PG's OPDS Atom feeds — the standard
 * way to programmatically browse and download. Endpoints used:
 *
 *   - Search:  https://www.gutenberg.org/ebooks/search.opds/?query=...
 *   - Browse:  https://www.gutenberg.org/ebooks.opds/  (popular, opening page)
 *
 * For each result we pick the best download link the reader can open natively
 * (EPUB > TXT) and surface a [GutenbergBook] with the direct URL. Downloads
 * stream the bytes to a phone temp file which the caller then forwards to the
 * watch via [WatchRepository.uploadBook].
 */
class GutenbergRepository {

    suspend fun search(query: String): List<GutenbergBook> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "$BASE/ebooks/search.opds/?query=" + URLEncoder.encode(query, "UTF-8")
        parseFeed(fetch(url))
    }

    suspend fun popular(): List<GutenbergBook> = withContext(Dispatchers.IO) {
        parseFeed(fetch("$BASE/ebooks.opds/"))
    }

    /**
     * Stream [book]'s bytes into [out]. Caller is responsible for opening / closing
     * [out]; we don't close it because the caller often wraps a temp-file output
     * stream they want to handle themselves.
     */
    suspend fun download(book: GutenbergBook, out: java.io.OutputStream) =
        withContext(Dispatchers.IO) {
            openConnection(book.downloadUrl).inputStream.use { input ->
                input.copyTo(out)
            }
        }

    private fun fetch(url: String): String =
        openConnection(url).inputStream.use { it.bufferedReader(Charsets.UTF_8).readText() }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/atom+xml, */*")
            instanceFollowRedirects = true
        }
        // Manually surface non-2xx as exceptions so callers see a clean error.
        if (conn.responseCode !in 200..299) {
            val msg = "HTTP ${conn.responseCode} for $url"
            conn.disconnect()
            error(msg)
        }
        return conn
    }

    private fun parseFeed(xml: String): List<GutenbergBook> {
        val doc = Jsoup.parse(xml, BASE, Parser.xmlParser())
        val out = mutableListOf<GutenbergBook>()
        for (entry in doc.select("entry")) {
            // Skip navigation entries — only real books have an acquisition link.
            val acq = entry.select("link[rel=http://opds-spec.org/acquisition]")
            if (acq.isEmpty()) continue

            val preferred = pickPreferred(acq) ?: continue
            val id = entry.selectFirst("id")?.text()?.trim().orEmpty()
            val title = entry.selectFirst("title")?.text()?.trim().orEmpty()
            val author = entry.selectFirst("author > name")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
            val summary = (entry.selectFirst("summary")?.text()
                ?: entry.selectFirst("content")?.text())
                ?.trim()?.takeIf { it.isNotEmpty() }

            if (title.isEmpty()) continue
            out += GutenbergBook(
                id = id,
                title = title,
                author = author,
                summary = summary,
                downloadUrl = preferred.url,
                mimeType = preferred.mime,
                extension = preferred.extension,
            )
        }
        return out
    }

    private data class Acquisition(val url: String, val mime: String, val extension: String)

    /** Pick the first acquisition link whose MIME type we know how to parse. */
    private fun pickPreferred(links: org.jsoup.select.Elements): Acquisition? {
        var epub: Acquisition? = null
        var txt: Acquisition? = null
        for (link in links) {
            val href = link.attr("href")
            val type = link.attr("type").substringBefore(';').trim().lowercase()
            if (href.isEmpty()) continue
            // OPDS hrefs may be relative; resolve against the base URL.
            val abs = if (href.startsWith("http")) href else BASE + href.removePrefix("/")
            when (type) {
                "application/epub+zip" -> if (epub == null) epub = Acquisition(abs, type, "epub")
                "text/plain" -> if (txt == null) txt = Acquisition(abs, type, "txt")
            }
        }
        return epub ?: txt
    }

    companion object {
        private const val BASE = "https://www.gutenberg.org/"
        private const val USER_AGENT = "wBooks-companion/0.3 (https://github.com/FredApps/wBooks)"
    }
}

data class GutenbergBook(
    val id: String,
    val title: String,
    val author: String?,
    val summary: String?,
    val downloadUrl: String,
    val mimeType: String,
    val extension: String,
)
