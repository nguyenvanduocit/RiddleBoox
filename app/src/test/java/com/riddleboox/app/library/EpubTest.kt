package com.riddleboox.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun book(): Epub =
        Epub.open(writeSampleEpub(folder.newFile("book.epub"))) ?: error("sample epub would not open")

    @Test
    fun `the spine is the book, and what it excludes is not`() {
        book().use {
            assertEquals(3, it.chapters.size)
            assertTrue("bìa bị linear=no loại ra", it.chapters.none { c -> c.title == "Bìa" })
        }
    }

    /**
     * The reader's own table of contents wins where it has an entry, because
     * that is the name the writer sees on their device; a chapter it skips
     * falls back to the heading printed at the top of the page.
     */
    @Test
    fun `chapters are named by the table of contents, then by their heading`() {
        book().use {
            assertEquals("Chương một", it.chapters[0].title)
            assertEquals("Chương hai", it.chapters[1].title)
            assertEquals("Chương ba", it.chapters[2].title)
        }
    }

    /** A relative href from a subfolder, and a name the index percent-escaped. */
    @Test
    fun `hrefs resolve to the entries actually in the zip`() {
        book().use {
            assertEquals("OEBPS/text/ch2.xhtml", it.chapters[1].href)
            assertEquals("OEBPS/text/ch three.xhtml", it.chapters[2].href)
            assertTrue(it.text(2).contains("Không có mèo nào"))
        }
    }

    @Test
    fun `a chapter comes back as words, not markup`() {
        book().use {
            val text = it.text(0)
            assertTrue(text.contains("Mèo con ngồi trên mái nhà"))
            assertTrue("thực thể phải được giải mã", text.contains("—"))
            assertTrue("hai đoạn phải là hai dòng", text.contains("lặng lẽ.\nTrời đã tối."))
            assertTrue("style trong head phải biến mất", !text.contains("color"))
            assertTrue("thẻ không được lọt ra", !text.contains("<"))
        }
    }

    /**
     * The whole reason for [Folded]: the writer's tones reach the model through
     * handwriting and come back however it read them, but the passage quoted
     * back has to be the book's own words.
     */
    @Test
    fun `a search without tones finds the passage and quotes it with them`() {
        book().use {
            val found = it.passages("meo", limit = 5)
            assertEquals(listOf(0, 1), found.map { p -> p.chapter }.distinct().take(2))
            assertTrue(found[0].text.contains("Mèo con"))
            assertTrue(found.any { p -> p.text.contains("Con mèo lại đến") })
        }
    }

    @Test
    fun `the limit is a limit`() {
        book().use {
            assertEquals(1, it.passages("mèo", limit = 1).size)
        }
    }

    @Test
    fun `something that is not a book is not opened`() {
        val notAnEpub = folder.newFile("notes.pdf").apply { writeText("%PDF-1.4 not a zip") }
        assertNull(Epub.open(notAnEpub))
        assertNull(Epub.open(folder.root.resolve("gone.epub")))
    }

    /** A zip that opens but has no package document is not a book either. */
    @Test
    fun `a zip without a package document is not opened`() {
        val bare = folder.newFile("bare.epub")
        java.util.zip.ZipOutputStream(bare.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("hello.txt"))
            zip.write("hi".toByteArray())
            zip.closeEntry()
        }
        assertNull(Epub.open(bare))
    }

    // ---- the pieces, on their own ----

    @Test
    fun `markup becomes paragraphs`() {
        assertEquals(
            "Một\nHai",
            htmlToText("<div><p>Một</p>\n  <p>Hai</p></div>"),
        )
        assertEquals("a & b < c", htmlToText("a &amp; b &lt; c"))
        assertEquals("→", htmlToText("&#x2192;"))
        assertEquals("→", htmlToText("&#8594;"))
        assertEquals("&unknown;", htmlToText("&unknown;"))
    }

    @Test
    fun `an excerpt is cut at word boundaries and says where it was cut`() {
        val text = "một hai ba bốn năm sáu bảy tám chín mười"
        val at = text.indexOf("năm")
        assertEquals("…ba bốn năm sáu bảy…", excerpt(text, at, at + 3, window = 8))
        assertEquals(text, excerpt(text, at, at + 3, window = 1000))
    }

    @Test
    fun `relative hrefs are resolved against the package document`() {
        assertEquals("OEBPS/ch1.xhtml", resolve("OEBPS", "ch1.xhtml"))
        assertEquals("images/x.png", resolve("OEBPS/text", "../../images/x.png"))
        assertEquals("ch1.xhtml", resolve("", "./ch1.xhtml"))
        assertEquals("OEBPS/a b.xhtml", resolve("OEBPS", "a%20b.xhtml#frag"))
    }

    @Test
    fun `the spine leaves out what it marks non-linear`() {
        val opf = """
            <manifest>
              <item id="a" href="a.xhtml"/><item id="b" href="b.xhtml"/>
            </manifest>
            <spine><itemref idref="a" linear="no"/><itemref idref="b"/></spine>
        """.trimIndent()
        assertEquals(listOf("b.xhtml"), spine(opf))
    }

    @Test
    fun `folding keeps a way back to the original characters`() {
        val folded = Folded("Nghệ Thuật")
        assertEquals("nghe thuat", folded.value)
        val at = folded.value.indexOf("thuat")
        assertEquals("Thuật", "Nghệ Thuật".substring(folded.sourceIndex(at), folded.sourceIndex(at + 5)))
    }

    @Test
    fun `folding flattens the letters Vietnamese loses in handwriting`() {
        assertEquals("duoc", fold("Được"))
        assertEquals("cau chuyen nghe thuat", fold("Câu Chuyện Nghệ Thuật"))
    }
}
