package com.riddleboox.app.tools

import com.riddleboox.app.library.Book
import com.riddleboox.app.library.ClosedLibrary
import com.riddleboox.app.library.FakeLibrary
import com.riddleboox.app.library.Library
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class BooxStateToolsTest {

    private val newest = Book(
        id = "b1",
        title = "Câu Chuyện Nghệ Thuật",
        authors = "E. H. Gombrich",
        path = "/sdcard/Books/gombrich.epub",
        format = "epub",
        page = 120,
        pages = 688,
        lastOpenedMs = 1_660_000_000_000,
    )

    private val older = Book(
        id = "b2",
        title = "Istio in Action",
        authors = "Christian E. Posta",
        path = "/sdcard/Books/istio.pdf",
        format = "pdf",
        page = 25,
        pages = 436,
        lastOpenedMs = 1_657_880_252_657,
    )

    private val oldest = Book(
        id = "b3",
        title = "Thi Nhân Việt Nam",
        authors = "Hoài Thanh",
        path = "/sdcard/Books/thinhan.epub",
        format = "epub",
        page = 5,
        pages = 300,
        lastOpenedMs = 1_600_000_000_000,
    )

    private val plan = BooxNote(
        id = "note-1",
        title = "Kế hoạch tuần",
        createdAtMs = 1_700_000_000_000,
        updatedAtMs = 1_700_000_100_000,
        pageCount = 2,
        pageIds = listOf("page-1", "page-2"),
        richTextPageIds = emptyList(),
        source = null,
        favorite = false,
    )

    private val olderNote = plan.copy(id = "note-0", title = "Ý tưởng cũ", updatedAtMs = 1_699_000_000_000)

    private fun tools(
        library: Library = FakeLibrary(listOf(newest, older, oldest)),
        notes: BooxNotesSource = FakeNotesSource(listOf(plan, olderNote)),
    ) = BooxStateTools(library, notes, zone = ZoneId.of("UTC"))

    private fun ask(tools: BooxStateTools = tools()): String =
        runBlocking { tools.call("get_current_boox_state", JsonObject(emptyMap())) }

    @Test
    fun `shows the 2 most recently opened books and the newest note`() {
        val answer = ask()

        assertTrue(answer.contains("Câu Chuyện Nghệ Thuật | E. H. Gombrich | page 120 of 688 | opened 2022-08-08"))
        assertTrue(answer.contains("Istio in Action | Christian E. Posta | page 25 of 436 | opened 2022-07-15"))
        assertTrue("chỉ lấy 2 cuốn gần nhất, không lấy cuốn cũ nhất", !answer.contains("Thi Nhân Việt Nam"))
        assertTrue(answer.contains("Kế hoạch tuần"))
        assertTrue("chỉ note mới nhất, không phải note cũ", !answer.contains("Ý tưởng cũ"))
    }

    @Test
    fun `fewer than 2 books still works`() {
        val answer = ask(tools(library = FakeLibrary(listOf(newest))))

        assertTrue(answer.contains("Câu Chuyện Nghệ Thuật"))
        assertTrue(!answer.contains("Istio"))
    }

    @Test
    fun `no books at all is said plainly`() {
        val answer = ask(tools(library = FakeLibrary(emptyList())))

        assertTrue(answer.contains("No books have been opened yet."))
        assertTrue(answer.contains("Kế hoạch tuần"))
    }

    @Test
    fun `no notes at all is said plainly`() {
        val answer = ask(tools(notes = FakeNotesSource(emptyList())))

        assertTrue(answer.contains("Câu Chuyện Nghệ Thuật"))
        assertTrue(answer.contains("No BOOX Notebook note has been written yet."))
    }

    @Test
    fun `an unreachable library still reports the note`() {
        val answer = ask(tools(library = ClosedLibrary()))

        assertTrue(answer.contains("could not reach the library"))
        assertTrue(answer.contains("Kế hoạch tuần"))
    }

    @Test
    fun `an unreachable notes source still reports the books`() {
        val answer = ask(tools(notes = ClosedNotesSource()))

        assertTrue(answer.contains("Câu Chuyện Nghệ Thuật"))
        assertTrue(answer.contains("could not reach BOOX Notebook"))
    }

    @Test
    fun `note() gives one line for the tool`() {
        val line = tools().note("get_current_boox_state", JsonObject(emptyMap()))
        assertEquals("Checking the shelf and the notebook…", line)
    }

    private class FakeNotesSource(private val notes: List<BooxNote>) : BooxNotesSource {
        override fun listNotes(): List<BooxNote> = notes
        override fun readPage(note: String, pageNumber: Int): BooxNotePage = throw NotImplementedError()
        override fun deleteNote(note: String): DeletedNote = throw NotImplementedError()
        override fun createNote(title: String): BooxNote = throw NotImplementedError()
        override fun renameNote(note: String, title: String): BooxNote = throw NotImplementedError()
    }

    private class ClosedNotesSource : BooxNotesSource {
        override fun listNotes(): List<BooxNote> = throw RuntimeException("BOOX Notebook provider không mở được")
        override fun readPage(note: String, pageNumber: Int): BooxNotePage = throw NotImplementedError()
        override fun deleteNote(note: String): DeletedNote = throw NotImplementedError()
        override fun createNote(title: String): BooxNote = throw NotImplementedError()
        override fun renameNote(note: String, title: String): BooxNote = throw NotImplementedError()
    }
}
