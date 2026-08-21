package com.riddleboox.app.dilib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures below are trimmed copies of what dilib actually serves: the
 * `ajax-search.php` fragment and a book page. They are kept verbose on purpose
 * — the parser exists because of the specific shape of this markup, so a test
 * written against tidier HTML would stop testing the thing that breaks.
 */
class DilibParserTest {

    private val domain = "https://dilib.vn"

    private val searchFragment = """
        <body>
        <div id="searchajax" class="searchsugestion">
        <a target="_blank" href="/25-thuat-dac-nhan-tam-7950.html" title="25 Thuật Đắc Nhân Tâm">
        <div class="searchtotal">
          <div class="searchdiv1"><img class="vienden" alt="25 Thuật Đắc Nhân Tâm" src="/img/news/2022/11/thumb/7950-25-thuat-dac-nhan-tam-1.webp"></div>
          <div class="searchdiv2">
            <b>25 THUẬT ĐẮC NHÂN TÂM</b><br>
            <span style="color: #011e1b">
              Tác giả : John C. Maxwell<br>
              Định dạng : PDF - 167 trang<br>
              Lượt xem : 5554<br>
              Lượt đọc : 3142<br>
              Lượt tải : 2226      </span>
          </div>
        </div>
        </a>
        <a target="_blank" href="/dac-nhan-tam-403.html" title="Đắc Nhân Tâm">
        <div class="searchtotal">
          <div class="searchdiv1"><img class="vienden" alt="Đắc Nhân Tâm" src="/img/news/2022/09/thumb/403-dac-nhan-tam-1.webp?v=5198"></div>
          <div class="searchdiv2">
            <b>ĐẮC NHÂN TÂM</b><br>
            <span style="color: #011e1b">
              Tác giả : Nguyễn Hiến Lê<br>
              Định dạng : PDF - 378 trang<br>
              Lượt xem : 60240<br>
              Lượt tải : 9556      <br>Audio : Lời Nhà Xuất Bản, Phần Thứ Nhất...</span>
          </div>
        </div>
        </a>
        </div>
        <div align="center">
          <a href='/search/L%E1%BB%8Bch+S%E1%BB%AD/page/3' class='next_link_activ'>»</a>
          <a href='/search/L%E1%BB%8Bch+S%E1%BB%AD/page/14' class='end_link'>»»</a>
        </div>
        </body>
    """.trimIndent()

    private val bookPage = """
        <html><body>
        <div class="col-xs-12 pd0 size-shop_catalog">
          <a href="/img/news/2022/09/larger/403-dac-nhan-tam-1.webp?v=5198" title="Ảnh bìa sách Đắc Nhân Tâm">
            <img src="/img/news/2022/09/larger/403-dac-nhan-tam-1.webp?v=5198" class="border" alt="Ảnh bìa sách Đắc Nhân Tâm">
          </a>
        </div>
        <h1 class="fs20">ĐẮC NHÂN TÂM</h1>
        <p class="mt10 mb10"><b>Tác giả : </b><a href="/tac-gia/nguyen-hien-le-109/">Nguyễn Hiến Lê</a></p>
        <p class="mt10 mb10"><b>Giọng đọc :</b> <a href="/giong-doc/dong-linh-51/">Đồng Linh</a></p>
        <p class="mt10 mb10"><b>NXB :</b> <a href="/nxb/V%C4%83n+H%C3%B3a/">Văn Hóa</a></p>
        <p class="mt10 mb10"><b>Năm xuất bản :</b> 1999</p>
        <p class="mt10 mb10"><b>Định dạng :</b> Sách nói / <a href="#pdf">PDF, EPUB</a></p>
        <p class="mt10 mb10"><b>Số trang :</b> 378</p>
        <p class="mt10 mb10"><b>Lượt tải :</b> 9560</p>
        <p class="mt10 mb10"><b>Kích thước :</b> 1.5 MB</p>
        <fieldset id="pdf">
          <legend><b>THỂ LOẠI</b></legend>
          <a class="button2" href="/thu-vien/tam-ly-ky-nang/">Tâm Lý - Kỹ Năng</a>
          <a class="button2" href="/thu-vien/phat-trien-ban-than/">Phát Triển Bản Thân</a>
          <a class="button1" href="/readbook/dc12e1">Đọc Sách</a>
          <a class="button2" href="/download/dc12e1">PDF</a>
          <a class="button2" href="/download/epub/dc12e1">EPUB</a>
        </fieldset>
        </body></html>
    """.trimIndent()

    @Test
    fun `search cards carry the id, the cased title and the facts beside it`() {
        val result = DilibParser.parseSearchResults(searchFragment, domain, page = 1)

        assertEquals(2, result.books.size)
        val first = result.books[0]
        assertEquals("7950", first.id)
        // The card shouts the title in uppercase; the anchor keeps the real casing.
        assertEquals("25 Thuật Đắc Nhân Tâm", first.title)
        assertEquals("John C. Maxwell", first.author)
        assertEquals("167", first.pages)
        assertEquals("2226", first.downloads)
        assertEquals("PDF", first.formatLabel)
        assertEquals("https://dilib.vn/25-thuat-dac-nhan-tam-7950.html", first.url)
        assertTrue(first.coverUrl.startsWith("https://dilib.vn/img/news/"))
    }

    /**
     * A search card names one format even when the book page offers two: this
     * book is listed as "PDF - 378 trang" here and as PDF *and* EPUB on its own
     * page. So a card's format is a hint, never the catalogue — which is why
     * [DilibTools] confirms the real list on the book page before downloading.
     */
    @Test
    fun `a search card reports only the format dilib prints on it`() {
        val second = DilibParser.parseSearchResults(searchFragment, domain, page = 1).books[1]

        assertEquals("403", second.id)
        assertEquals("Đắc Nhân Tâm", second.title)
        assertEquals("Nguyễn Hiến Lê", second.author)
        assertEquals("378", second.pages)
        assertEquals("PDF", second.formatLabel)

        val fromPage = DilibParser.parseBookDetail(bookPage, domain, "403")
        assertEquals(listOf("pdf", "epub"), fromPage.formats.map { it.format })
    }

    /**
     * The same "Định dạng" label also appears as "Sách nói / PDF, EPUB". An
     * audiobook is not a file that belongs in /sdcard/Books, so it is dropped
     * while the two real formats beside it are kept.
     */
    @Test
    fun `an audiobook listed beside the files is not counted as one`() {
        val card = """
            <div class="searchsugestion">
            <a href="/x-9.html" title="X"><div class="searchdiv2"><span>
              Tác giả : Ai Đó<br>Định dạng : Sách nói / PDF, EPUB<br></span></div></a>
            </div>
        """.trimIndent()

        val book = DilibParser.parseSearchResults(card, domain, page = 1).books.single()

        assertEquals("PDF, EPUB", book.formatLabel)
    }

    @Test
    fun `the total page count comes from the last-page link, not the result count`() {
        val result = DilibParser.parseSearchResults(searchFragment, domain, page = 1)

        assertEquals(1, result.page)
        assertEquals(14, result.totalPages)
    }

    @Test
    fun `a fragment without a pager is a single page`() {
        val single = "<div id=\"searchajax\" class=\"searchsugestion\">" +
            "<a href=\"/mot-cuon-sach-12.html\" title=\"Một Cuốn Sách\"></a></div>"

        val result = DilibParser.parseSearchResults(single, domain, page = 1)

        assertEquals(1, result.totalPages)
        assertEquals("12", result.books.single().id)
    }

    @Test
    fun `a book page yields both downloads with their formats`() {
        val book = DilibParser.parseBookDetail(bookPage, domain, "403")

        assertEquals(listOf("pdf", "epub"), book.formats.map { it.format })
        assertEquals("https://dilib.vn/download/dc12e1", book.formats[0].url)
        assertEquals("https://dilib.vn/download/epub/dc12e1", book.formats[1].url)
    }

    @Test
    fun `a book page yields the labelled facts beside the cover`() {
        val book = DilibParser.parseBookDetail(bookPage, domain, "403")

        assertEquals("403", book.id)
        assertEquals("Đắc Nhân Tâm", book.title)
        assertEquals("Nguyễn Hiến Lê", book.author)
        assertEquals("Văn Hóa", book.publisher)
        assertEquals("1999", book.year)
        assertEquals("378", book.pages)
        assertEquals("1.5 MB", book.size)
        assertEquals("9560", book.downloads)
        assertEquals(listOf("Tâm Lý - Kỹ Năng", "Phát Triển Bản Thân"), book.categories)
        assertEquals("https://dilib.vn/403.html", book.url)
    }

    /**
     * The read-online link sits among the download buttons and looks like one.
     * Taking it would save an HTML page into the library instead of a book.
     */
    @Test
    fun `the read-online link is not mistaken for a download`() {
        val book = DilibParser.parseBookDetail(bookPage, domain, "403")

        assertTrue(book.formats.none { it.url.contains("/readbook/") })
    }

    @Test
    fun `epub is preferred over pdf when the writer names no format`() {
        val book = DilibParser.parseBookDetail(bookPage, domain, "403")

        assertEquals("epub", book.preferred()?.format)
        assertEquals("pdf", book.preferred("pdf")?.format)
        assertEquals("pdf", book.preferred(".PDF")?.format)
        assertNull(book.preferred("mobi"))
    }

    @Test
    fun `a book with only a pdf falls back to it`() {
        val pdfOnly = "<a class=\"button2\" href=\"/download/abc123\">PDF</a>"

        val book = DilibParser.parseBookDetail(pdfOnly, domain, "1")

        assertEquals("pdf", book.preferred()?.format)
    }

    @Test
    fun `the numeric id is read out of any slug dilib hands back`() {
        assertEquals("403", DilibParser.bookId("/dac-nhan-tam-403.html"))
        assertEquals("12083", DilibParser.bookId("https://dilib.vn/suc-manh-cua-tri-tue-xa-hoi-12083.html"))
        assertNull(DilibParser.bookId("/thu-vien/tam-ly-ky-nang/"))
        assertNull(DilibParser.bookId(""))
    }

    @Test
    fun `search asks the fragment endpoint, not the page the browser shows`() {
        val url = DilibClient.searchUrl(domain, "Đắc Nhân Tâm", page = 2)

        assertEquals(
            "https://dilib.vn/search/ajax-search.php?keyword=%C4%90%E1%BA%AFc+Nh%C3%A2n+T%C3%A2m&page=2",
            url,
        )
    }
}
