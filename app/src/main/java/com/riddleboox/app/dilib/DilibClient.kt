package com.riddleboox.app.dilib

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.riddleboox.app.library.getOrCreateDirectoryAt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Reader for dilib.vn, which needs no account at all.
 *
 * That absence is the whole shape of this class. There is no login, no cookie
 * jar and no stored credential, so it takes no account and can be built
 * wherever it is needed.
 *
 * Two details are not obvious from the site. Search results are never in the
 * search page — jQuery fetches them from `/search/ajax-search.php`, so that is
 * what is asked for here. And a download is a redirect out to Google Drive,
 * which answers a large file with an HTML interstitial rather than the file, so
 * redirects are followed by hand and that page is retried with `confirm=t`.
 */
class DilibClient(domain: String = DEFAULT_DOMAIN) {

    private val domain = domain.trim().trimEnd('/').ifBlank { DEFAULT_DOMAIN }

    suspend fun search(query: String, page: Int = 1, count: Int = DEFAULT_COUNT): DilibSearchResult {
        require(query.isNotBlank()) { "A search needs a keyword." }
        val safePage = page.coerceAtLeast(1)
        val html = getHtml(searchUrl(domain, query, safePage))
        val result = DilibParser.parseSearchResults(html, domain, safePage)
        return result.copy(books = result.books.take(count.coerceIn(1, MAX_COUNT)))
    }

    /**
     * The numeric id is a whole address: dilib redirects `/<id>.html` to the
     * slug it prefers, so nothing has to remember the slug.
     */
    suspend fun fetchBook(id: String): DilibBook {
        val number = id.trim().let { DilibParser.bookId(it) ?: it }
        require(number.isNotBlank() && number.all(Char::isDigit)) {
            "A dilib book id is a string of digits, e.g. 403."
        }
        val html = getHtml("$domain/$number.html")
        return DilibParser.parseBookDetail(html, domain, number)
    }

    /** Downloads one file into the BOOX library folder, through the storage folder grant. */
    suspend fun download(book: DilibBook, file: DilibFile, context: Context): Uri {
        if (file.url.isBlank()) throw DilibException("\"${book.title}\" has no download link.")
        val destination = getOrCreateDirectoryAt(context, "Books")
            ?: throw DilibException("Could not reach the library folder — grant folder access in Settings first.")

        var lastError: Throwable? = null
        for (attempt in 0..DOWNLOAD_RETRIES) {
            try {
                currentCoroutineContext().ensureActive()
                return fetchInto(destination, context, book, file)
            } catch (error: IOException) {
                lastError = error
                if (attempt == DOWNLOAD_RETRIES) break
                delay(RETRY_DELAY_MS shl attempt)
            }
        }
        throw DilibException("The book would not download after ${DOWNLOAD_RETRIES + 1} attempts.", lastError)
    }

    private suspend fun fetchInto(destination: DocumentFile, context: Context, book: DilibBook, file: DilibFile): Uri {
        var connection = openFollowing(file.url)
        // Drive answers a large file with a scan-warning page instead of bytes.
        // The page is not an error, so only the content type gives it away.
        if (isHtml(connection)) {
            val url = connection.url.toString()
            connection.disconnect()
            connection = openFollowing(confirmed(url))
            if (isHtml(connection)) {
                connection.disconnect()
                throw DilibException(
                    "Google Drive has not handed over the file for \"${book.title}\"; try again later or open ${book.url} in a browser.",
                )
            }
        }

        val name = uniqueName(destination, filename(connection, book, file))
        val target = destination.createFile(mimeType(name.substringAfterLast('.', "")), name)
            ?: throw DilibException("Could not create \"$name\" in the library folder.")
        try {
            connection.inputStream.use { input ->
                context.contentResolver.openOutputStream(target.uri)!!.use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
        return target.uri
    }

    private fun getHtml(url: String): String {
        val connection = openFollowing(url)
        val code = connection.responseCode
        val body = responseBody(connection, code)
        connection.disconnect()
        if (code !in 200..299) throw DilibException("dilib answered with HTTP $code.")
        return body
    }

    /**
     * Redirects are followed here rather than by [HttpURLConnection] because a
     * download leaves dilib for Google Drive, and the built-in follower drops
     * the hop when the host changes.
     */
    private fun openFollowing(url: String): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = open(current)
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw DilibException("dilib redirected with no destination (HTTP $code).")
                }
                current = URI(current).resolve(location).toString()
                return@repeat
            }
            if (code == HttpURLConnection.HTTP_BAD_GATEWAY ||
                code == HttpURLConnection.HTTP_UNAVAILABLE ||
                code == HttpURLConnection.HTTP_GATEWAY_TIMEOUT
            ) {
                connection.disconnect()
                throw RetryableDownload("the server answered with HTTP $code")
            }
            return connection
        }
        throw DilibException("dilib redirected too many times.")
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", ACCEPT)
        connection.setRequestProperty("Accept-Encoding", "gzip")
        connection.setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
        connection.setRequestProperty("Referer", "$domain/")
        return connection
    }

    private fun responseBody(connection: HttpURLConnection, code: Int): String {
        val stream = (if (code in 200..399) connection.inputStream else connection.errorStream)
            ?: return ""
        return decoded(stream, connection.getHeaderField("Content-Encoding"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun decoded(stream: InputStream, encoding: String?): InputStream =
        if (encoding?.contains("gzip", ignoreCase = true) == true) GZIPInputStream(stream) else stream

    private fun isHtml(connection: HttpURLConnection): Boolean =
        connection.getHeaderField("Content-Type").orEmpty().contains("text/html", ignoreCase = true)

    private fun confirmed(url: String): String =
        if (url.contains("confirm=")) url else url + (if (url.contains('?')) "&" else "?") + "confirm=t"

    private fun filename(connection: HttpURLConnection, book: DilibBook, file: DilibFile): String {
        val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
        val fromHeader = Regex("filename\\*?=(?:UTF-8'')?[\"']?([^;\"']+)", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        var result = sanitize(fromHeader.ifBlank { book.title.ifBlank { "dilib-${book.id}" } })
        val extension = file.format.trim().lowercase(Locale.ROOT).removePrefix(".")
        if (extension.isNotBlank() && !result.substringAfterLast('.', "").equals(extension, true)) {
            result += ".$extension"
        }
        return result
    }

    private fun sanitize(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(120)
        .ifBlank { "dilib" }

    private fun uniqueName(directory: DocumentFile, requested: String): String =
        uniqueName({ directory.findFile(it) != null }, requested)

    /** [requested], or the same name with a counter appended until [exists] says no one has it. */
    internal fun uniqueName(exists: (String) -> Boolean, requested: String): String {
        if (!exists(requested)) return requested
        val base = requested.substringBeforeLast('.', requested)
        val ext = requested.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        for (i in 2..999) {
            val candidate = "$base ($i)$ext"
            if (!exists(candidate)) return candidate
        }
        throw DilibException("Too many files in the library share this name.")
    }

    private fun mimeType(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "pdf" -> "application/pdf"
        "epub" -> "application/epub+zip"
        "mobi" -> "application/x-mobipocket-ebook"
        "azw3" -> "application/vnd.amazon.ebook"
        else -> "application/octet-stream"
    }

    private class RetryableDownload(message: String) : IOException(message)

    companion object {
        const val DEFAULT_DOMAIN = "https://dilib.vn"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 180_000
        private const val DOWNLOAD_RETRIES = 3
        private const val RETRY_DELAY_MS = 1_000L
        private const val MAX_REDIRECTS = 6
        private const val DEFAULT_COUNT = 10
        private const val MAX_COUNT = 20
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

        internal fun searchUrl(domain: String, query: String, page: Int): String {
            val base = domain.trim().trimEnd('/').ifBlank { DEFAULT_DOMAIN }
            val keyword = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
            return "$base/search/ajax-search.php?keyword=$keyword&page=${page.coerceAtLeast(1)}"
        }
    }
}
