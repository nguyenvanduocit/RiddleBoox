package com.riddleboox.app.dilib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DilibBook.preferred] picks which downloadable file a writer gets, and
 * [DilibBook.formatLabel] shows what is on offer. Both branch on the shape of
 * `formats`, so the fixtures below deliberately vary its order and contents
 * rather than always leading with EPUB.
 */
class DilibModelsTest {

    private val epub = DilibFile(format = "epub", url = "https://dilib.vn/epub")
    private val pdf = DilibFile(format = "pdf", url = "https://dilib.vn/pdf")

    private fun book(vararg files: DilibFile) = DilibBook(
        id = "1",
        title = "Đắc Nhân Tâm",
        formats = files.toList(),
    )

    @Test
    fun `preferred with no format asked picks EPUB even when it is not first`() {
        val result = book(pdf, epub).preferred()

        assertEquals(epub, result)
    }

    @Test
    fun `preferred with no format asked and no EPUB falls back to the first file`() {
        val mobi = DilibFile(format = "mobi", url = "https://dilib.vn/mobi")

        val result = book(pdf, mobi).preferred()

        assertEquals(pdf, result)
    }

    @Test
    fun `preferred pdf returns the pdf file even when EPUB is also offered`() {
        val result = book(epub, pdf).preferred("pdf")

        assertEquals(pdf, result)
    }

    @Test
    fun `preferred normalizes a leading dot and uppercase before matching`() {
        val result = book(epub, pdf).preferred(".PDF")

        assertEquals(pdf, result)
    }

    @Test
    fun `preferred with a format nobody offers returns null instead of falling back to EPUB`() {
        val result = book(epub, pdf).preferred("mobi")

        assertNull(result)
    }

    @Test
    fun `preferred on a book with no formats returns null without crashing`() {
        val result = book().preferred()

        assertNull(result)
    }

    @Test
    fun `formatLabel joins formats in the order they were listed, uppercased`() {
        val label = book(epub, pdf).formatLabel

        assertEquals("EPUB, PDF", label)
    }

    @Test
    fun `formatLabel on a book with no formats is an empty string`() {
        val label = book().formatLabel

        assertEquals("", label)
    }
}
