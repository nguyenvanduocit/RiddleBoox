package com.riddleboox.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryTest {

    private fun book(
        title: String,
        authors: String = "",
        path: String = "/sdcard/Books/$title.epub",
        opened: Long = 0,
    ) = Book(
        id = title,
        title = title,
        authors = authors,
        path = path,
        format = "epub",
        page = 0,
        pages = 0,
        lastOpenedMs = opened,
    )

    private val shelf = listOf(
        book("Câu Chuyện Nghệ Thuật", "E. H. Gombrich"),
        book("Nghệ Thuật Tinh Tế Của Việc Đếch Quan Tâm", "Mark Manson"),
        book("Ego Is the Enemy", "Ryan Holiday", path = "/sdcard/Books/ego-is-the-enemy-thuviensach.vn.epub"),
    )

    /**
     * Ranked, not filtered: two books answer to "nghệ thuật" and the writer
     * meant one of them. The title that *begins* with what was asked for is the
     * likelier one — a book is named by how it starts, and a phrase buried in
     * the middle of a longer title is a weaker claim on it.
     */
    @Test
    fun `a title that begins with the query beats one that merely contains it`() {
        assertEquals("Câu Chuyện Nghệ Thuật", shelf.named("Câu Chuyện Nghệ Thuật")?.title)
        assertEquals(
            listOf("Nghệ Thuật Tinh Tế Của Việc Đếch Quan Tâm", "Câu Chuyện Nghệ Thuật"),
            shelf.matching("nghệ thuật").map { it.title },
        )
    }

    /** The writer's tones survive their handwriting, the model's reading of it, or neither. */
    @Test
    fun `a query without tones finds the book anyway`() {
        assertEquals("Câu Chuyện Nghệ Thuật", shelf.named("cau chuyen nghe thuat")?.title)
        assertEquals("Nghệ Thuật Tinh Tế Của Việc Đếch Quan Tâm", shelf.named("dech quan tam")?.title)
    }

    @Test
    fun `an author names a book too`() {
        assertEquals("Câu Chuyện Nghệ Thuật", shelf.named("Gombrich")?.title)
        assertEquals("Ego Is the Enemy", shelf.named("holiday")?.title)
    }

    /** Half the shelf is titled by whoever uploaded the file, not by the book. */
    @Test
    fun `the file name is the last thing tried`() {
        assertEquals("Ego Is the Enemy", shelf.named("thuviensach")?.title)
    }

    /** "gombrich nghe thuat" names one book and matches no single field of it. */
    @Test
    fun `every word somewhere in the entry is enough`() {
        assertEquals("Câu Chuyện Nghệ Thuật", shelf.named("gombrich chuyện")?.title)
    }

    @Test
    fun `among equals the one read most recently wins`() {
        val twoKinh = listOf(
            book("Kinh Nikaya I", opened = 1_000),
            book("Kinh Nikaya II", opened = 9_000),
        )
        assertEquals("Kinh Nikaya II", twoKinh.named("kinh nikaya")?.title)
    }

    @Test
    fun `a name nothing answers to is no book at all`() {
        assertNull(shelf.named("Chúa tể hắc ám"))
        assertEquals(emptyList<Book>(), shelf.matching("Chúa tể hắc ám"))
    }

    /** An empty query is not a search; it is the shelf as it stands. */
    @Test
    fun `an empty query keeps the shelf in its own order`() {
        assertEquals(shelf.map { it.title }, shelf.matching("  ").map { it.title })
    }

    @Test
    fun `progress is read off the readers own notation`() {
        assertEquals(140 to 770, progress("140/770"))
        assertEquals(0 to 0, progress(""))
        assertEquals(0 to 0, progress("chưa đọc"))
        assertEquals(3 to 0, progress("3"))
    }

    /**
     * The line under a title on the shelf: who wrote it and where the reader
     * is in it, whichever of the two the entry actually knows.
     */
    @Test
    fun `a shelf line names the author and the place the reader has reached`() {
        assertEquals(
            "Mark Manson · page 12 of 300",
            book("Ego", "Mark Manson").copy(page = 12, pages = 300).shelfLine(),
        )
        assertEquals("Mark Manson", book("Ego", "Mark Manson").shelfLine())
        assertEquals("page 12 of 300", book("Ego").copy(page = 12, pages = 300).shelfLine())
        assertEquals("a book with neither says nothing", "", book("Ego").shelfLine())
        assertEquals("a page count without a total is not progress", "", book("Ego").copy(page = 3).shelfLine())
    }
}
