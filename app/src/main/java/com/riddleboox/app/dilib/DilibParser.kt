package com.riddleboox.app.dilib

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/**
 * HTML parsing kept apart from networking, so a selector that dilib changes can
 * be fixed against a saved page instead of against the live site.
 *
 * dilib serves two shapes. Search results arrive as a bare fragment from
 * `/search/ajax-search.php` — the page the browser shows is empty until jQuery
 * fills it, which is why the fragment, not the page, is what this reads. A book
 * page is ordinary server-rendered HTML whose facts sit in `<p><b>Label :</b>
 * value</p>` rows.
 */
object DilibParser {

    private val idInHref = Regex("-(\\d+)\\.html")
    private val pageInHref = Regex("/page/(\\d+)")
    private val lineBreak = Regex("(?i)<br\\s*/?>")
    private val pageCount = Regex("(\\d+)\\s*trang")
    private val knownFormats = setOf("pdf", "epub", "mobi", "azw3", "prc", "doc", "docx", "djvu")

    fun parseSearchResults(html: String, domain: String, page: Int): DilibSearchResult {
        val document = Jsoup.parse(html)
        val books = document.select(".searchsugestion a[href], #searchajax a[href]")
            .distinctBy { it.attr("href") }
            .mapNotNull { card(it, domain) }
        // The fragment carries its own pager; the "last page" link is the only
        // place the total appears, so an absent pager means a single page.
        val totalPages = document.select("a[href*=/page/]")
            .mapNotNull { pageInHref.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: page
        return DilibSearchResult(books, page, totalPages.coerceAtLeast(page))
    }

    fun parseBookDetail(html: String, domain: String, id: String): DilibBook {
        val document = Jsoup.parse(html)
        val info = infoRows(document)
        val formats = document.select("a[href]")
            .filter { it.attr("href").contains("/download/") }
            .mapNotNull { anchor ->
                val href = anchor.attr("href")
                val format = anchor.text().trim().lowercase().takeIf { it in knownFormats }
                    ?: formatFromHref(href)
                DilibFile(format = format, url = absoluteUrl(domain, href))
            }
            .distinctBy { it.format }

        return DilibBook(
            id = id,
            title = detailTitle(document),
            author = info["Tác giả"].orEmpty(),
            publisher = info["NXB"].orEmpty(),
            year = info["Năm xuất bản"].orEmpty(),
            pages = info["Số trang"].orEmpty(),
            size = info["Kích thước"].orEmpty(),
            downloads = info["Lượt tải"].orEmpty(),
            categories = document.select("fieldset a.button2[href*=/thu-vien/]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() },
            formats = formats,
            coverUrl = absoluteUrl(domain, document.selectFirst("img.border[src]")?.attr("src").orEmpty()),
            url = absoluteUrl(domain, "/$id.html"),
        )
    }

    /** dilib canonicalises `/<anything>-<id>.html`, so the number is the whole address. */
    fun bookId(href: String): String? =
        idInHref.find(href)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun card(anchor: Element, domain: String): DilibBook? {
        val href = anchor.attr("href")
        val id = bookId(href) ?: return null
        val rows = labelledLines(anchor.selectFirst(".searchdiv2 span")?.html().orEmpty())
        val format = rows["Định dạng"].orEmpty()
        return DilibBook(
            id = id,
            // The `title` attribute keeps the book's own capitalisation; the
            // <b> beside it is shouted uppercase for the card layout.
            title = anchor.attr("title").trim()
                .ifBlank { anchor.selectFirst(".searchdiv2 b")?.text()?.trim().orEmpty() },
            author = rows["Tác giả"].orEmpty(),
            pages = pageCount.find(format)?.groupValues?.getOrNull(1).orEmpty(),
            downloads = rows["Lượt tải"].orEmpty(),
            formats = formatsFrom(format).map { DilibFile(it, "") },
            coverUrl = absoluteUrl(domain, anchor.selectFirst(".searchdiv1 img")?.attr("src").orEmpty()),
            url = absoluteUrl(domain, href),
        )
    }

    private fun detailTitle(document: org.jsoup.nodes.Document): String {
        val fromCover = document.selectFirst("img.border[alt]")?.attr("alt")?.trim().orEmpty()
            .removePrefix("Ảnh bìa sách").trim()
        return fromCover.ifBlank { document.selectFirst("h1")?.text()?.trim().orEmpty() }
    }

    /** `<p><b>Label :</b> value</p>` rows, first occurrence winning. */
    private fun infoRows(document: org.jsoup.nodes.Document): Map<String, String> = buildMap {
        for (paragraph in document.select("p")) {
            val bold = paragraph.selectFirst("b") ?: continue
            val label = bold.text().trim().removeSuffix(":").trim()
            val value = paragraph.text().removePrefix(bold.text()).trim().removePrefix(":").trim()
            if (label.isNotBlank() && value.isNotBlank() && !containsKey(label)) put(label, value)
        }
    }

    /** The search card packs its facts into one `<span>` separated by `<br>`. */
    private fun labelledLines(html: String): Map<String, String> = buildMap {
        for (piece in lineBreak.split(html)) {
            val text = Jsoup.parse(piece).text().trim()
            val label = text.substringBefore(':', "").trim()
            val value = text.substringAfter(':', "").trim()
            if (label.isNotBlank() && value.isNotBlank() && !containsKey(label)) put(label, value)
        }
    }

    /** "PDF - 167 trang" and "Sách nói / PDF, EPUB" both reduce to the real files. */
    private fun formatsFrom(value: String): List<String> = value
        .substringBefore('-')
        .split(',', '/')
        .map { it.trim().lowercase() }
        .filter { it in knownFormats }
        .distinct()

    private fun formatFromHref(href: String): String {
        val tail = href.substringAfter("/download/", "").substringBefore('/')
        return tail.lowercase().takeIf { it in knownFormats } ?: "pdf"
    }

    private fun absoluteUrl(domain: String, href: String): String {
        if (href.isBlank()) return ""
        return runCatching { URI(domain.trimEnd('/') + "/").resolve(href).toString() }
            .getOrDefault(href)
    }
}
