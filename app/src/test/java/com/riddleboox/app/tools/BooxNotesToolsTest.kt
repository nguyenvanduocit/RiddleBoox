package com.riddleboox.app.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BooxNotesToolsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val note = BooxNote(
        id = "note-1",
        title = "Kế hoạch tuần",
        createdAtMs = 1_700_000_000_000,
        updatedAtMs = 1_700_000_100_000,
        pageCount = 2,
        pageIds = listOf("page-1", "page-2"),
        richTextPageIds = emptyList(),
        source = null,
        favorite = true,
    )

    @Test
    fun `list and search expose BOOX notebooks, not workspace files`() = runBlocking {
        val tools = BooxNotesTools(FakeSource(note))

        val listed = tools.call("list_boox_notes", JsonObject(emptyMap()))
        val searched = tools.call(
            "search_boox_notes",
            JsonObject(mapOf("query" to JsonPrimitive("tuần"))),
        )

        assertTrue(listed.contains("Kế hoạch tuần"))
        assertTrue(listed.contains("pages=2"))
        assertTrue(listed.contains("favorite"))
        assertTrue(searched.contains("Kế hoạch tuần"))
        assertTrue(searched.contains("pages=2"))
    }

    @Test
    fun `read returns typed page text`() = runBlocking {
        val tools = BooxNotesTools(FakeSource(note, text = "Gọi cho Lan\nMua mực."))

        val result = tools.call(
            "read_boox_note",
            JsonObject(
                mapOf(
                    "note" to JsonPrimitive("Kế hoạch tuần"),
                    "page" to JsonPrimitive("1"),
                ),
            ),
        )

        assertTrue(result.contains("BOOX Notebook: \"Kế hoạch tuần\" — page 1/2"))
        assertTrue(result.contains("Gọi cho Lan"))
        assertTrue(result.contains("Mua mực."))
    }

    @Test
    fun `open note resolves the requested notebook before launching Notebook`() = runBlocking {
        var opened: BooxNote? = null
        val tools = BooxNotesTools(
            source = FakeSource(note),
            openNote = { selected -> opened = selected; true },
        )

        val result = tools.call(
            "open_note",
            JsonObject(mapOf("note" to JsonPrimitive("Kế hoạch tuần"))),
        )

        assertTrue(opened?.title == "Kế hoạch tuần")
        assertTrue(result.contains("Opened BOOX Notebook for \"Kế hoạch tuần\""))
    }

    @Test
    fun `read uses vision reader when the page has an exported image`() = runBlocking {
        val image = folder.newFile("page.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val tools = BooxNotesTools(
            source = FakeSource(note, imageFile = image),
            visionReader = BooxNotesVisionReader { "Chữ viết trên trang" },
        )

        val result = tools.call(
            "read_boox_note",
            JsonObject(mapOf("note" to JsonPrimitive("note-1"))),
        )

        assertTrue(result.contains("Chữ viết trên trang"))
    }

    @Test
    fun `a note is deleted whole, row and pages`() = runBlocking {
        val source = FakeSource(note)
        val tools = BooxNotesTools(source)

        val answer = tools.call("delete_boox_note", JsonObject(mapOf("note" to JsonPrimitive("Kế hoạch tuần"))))

        assertTrue(source.deleted.contains("Kế hoạch tuần"))
        assertTrue(answer.contains("\"Kế hoạch tuần\" is gone from BOOX Notebook"))
        assertTrue(answer.contains("2 page files went with it"))
        assertTrue(answer.contains("cannot be brought back"))
    }

    @Test
    fun `an unnamed note is not a note chosen at random`() = runBlocking {
        val source = FakeSource(note)
        val tools = BooxNotesTools(source)

        val answer = tools.call("delete_boox_note", JsonObject(emptyMap()))

        assertTrue("không được đụng vào note nào", source.deleted.isEmpty())
        assertTrue(answer.contains("Name the BOOX note to delete."))
    }

    @Test
    fun `a notebook can be started and renamed`() = runBlocking {
        val source = FakeSource(note)
        val tools = BooxNotesTools(source)

        val made = tools.call("create_boox_note", JsonObject(mapOf("title" to JsonPrimitive("Sổ mới"))))
        val renamed = tools.call(
            "rename_boox_note",
            JsonObject(mapOf("note" to JsonPrimitive("Kế hoạch tuần"), "title" to JsonPrimitive("Kế hoạch tháng"))),
        )

        assertTrue(source.created.contains("Sổ mới"))
        assertTrue(made.contains("Started BOOX Notebook note \"Sổ mới\" with one blank page"))
        assertTrue(renamed.contains("\"Kế hoạch tuần\" is now called \"Kế hoạch tháng\""))
    }

    @Test
    fun `every tool the model is offered can actually be called`() = runBlocking {
        val source = FakeSource(note)
        val offered = BooxNotesTools(source).tools.map { it.name }.toSet()

        assertTrue(
            offered.containsAll(
                setOf(
                    "list_boox_notes", "search_boox_notes", "open_note", "read_boox_note",
                    "delete_boox_note", "create_boox_note", "rename_boox_note",
                ),
            ),
        )
        for (name in offered) {
            val answer = BooxNotesTools(source).call(name, JsonObject(emptyMap()))
            assertTrue("$name rơi vào nhánh không tồn tại", !answer.startsWith("There is nothing called"))
        }
        assertTrue("gọi thiếu tham số không được xóa gì", source.deleted.isEmpty())
    }

    private class FakeSource(
        private val note: BooxNote,
        private val text: String = "",
        private val imageFile: File? = null,
    ) : BooxNotesSource {
        override fun listNotes(): List<BooxNote> = listOf(note)

        override fun readPage(note: String, pageNumber: Int): BooxNotePage = BooxNotePage(
            note = this.note,
            pageNumber = pageNumber,
            pageId = this.note.pageIds.getOrNull(pageNumber - 1),
            text = text,
            imageFile = imageFile,
            hasPrivatePageData = true,
        )

        val deleted = mutableListOf<String>()
        val created = mutableListOf<String>()

        override fun deleteNote(note: String): DeletedNote {
            deleted += note
            return DeletedNote(note = this.note, entry = true, files = 2)
        }

        override fun createNote(title: String): BooxNote {
            created += title
            return this.note.copy(id = "note-new", title = title, pageCount = 1)
        }

        override fun renameNote(note: String, title: String): BooxNote = this.note.copy(title = title)
    }
}
