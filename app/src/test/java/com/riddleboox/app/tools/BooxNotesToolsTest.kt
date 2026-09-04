package com.riddleboox.app.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
    fun `vision failure becomes an actionable answer instead of failing the whole tool`() = runBlocking {
        val image = folder.newFile("page-failure.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val tools = BooxNotesTools(
            source = FakeSource(note, imageFile = image),
            visionReader = BooxNotesVisionReader { error("offline") },
        )

        val result = tools.call(
            "read_boox_note",
            JsonObject(mapOf("note" to JsonPrimitive("note-1"))),
        )

        assertTrue(result.contains("BOOX Notebook: \"Kế hoạch tuần\""))
        assertTrue(result.contains("Check the model and connection in Settings"))
        assertTrue(!result.contains("BOOX Notebook lookup failed"))
    }

    @Test
    fun `private handwriting answer explains how to make it readable`() = runBlocking {
        val result = BooxNotesTools(FakeSource(note)).call(
            "read_boox_note",
            JsonObject(mapOf("note" to JsonPrimitive("note-1"))),
        )

        assertTrue(result.contains("Exporting this notebook as PNG"))
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

/**
 * Characterizes [OnyxBooxNotes.exportedFiles] (a BFS over `note/` matching exported
 * page files to a notebook title) before refactoring it for nesting. It has no other
 * seam that reaches it without a real BOOX Notebook ContentProvider, hence Robolectric
 * here just for a working [android.content.ContentResolver] — the function itself never
 * calls it.
 */
@RunWith(RobolectricTestRunner::class)
class OnyxBooxNotesExportedFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun onyxNotes(noteRoot: File): OnyxBooxNotes {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        return OnyxBooxNotes(resolver, noteRoot = noteRoot, ksyncRoot = folder.newFolder())
    }

    private fun note(title: String) = BooxNote(
        id = "note-1",
        title = title,
        createdAtMs = 0,
        updatedAtMs = 0,
        pageCount = 1,
        pageIds = emptyList(),
        richTextPageIds = emptyList(),
        source = null,
        favorite = false,
    )

    @Test
    fun `no file matches an unrelated title`() {
        val root = folder.newFolder()
        File(root, "receipt.png").writeText("x")

        val result = onyxNotes(root).exportedFiles(note("Travel Journal"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a file directly under noteRoot that matches the title is found`() {
        val root = folder.newFolder()
        val match = File(root, "travel-journal-cover.png").apply { writeText("x") }
        File(root, "other.png").apply { writeText("x") }

        val result = onyxNotes(root).exportedFiles(note("Travel Journal"))

        assertEquals(listOf(match), result)
    }

    @Test
    fun `a shorter notebook title does not steal another notebooks exported pages`() {
        val root = folder.newFolder()
        File(root, "Planning 2026").mkdirs()
        File(root, "Planning 2026/page-1.png").writeText("x")

        val result = onyxNotes(root).exportedFiles(note("Plan"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a file nested inside a subdirectory that matches the title is found`() {
        val root = folder.newFolder()
        val sub = File(root, "trips").apply { mkdirs() }
        val match = File(sub, "travel-journal-page1.png").apply { writeText("x") }

        val result = onyxNotes(root).exportedFiles(note("Travel Journal"))

        assertEquals(listOf(match), result)
    }

    @Test
    fun `a file six directory levels deep is unreachable, five levels deep is not`() {
        val root = folder.newFolder()
        var dir = root
        repeat(5) { i -> dir = File(dir, "L${i + 1}").apply { mkdirs() } }
        val shallow = File(dir, "travel-journal-shallow.png").apply { writeText("x") }
        val tooDeep = File(dir, "L6").apply { mkdirs() }
        File(tooDeep, "travel-journal-deep.png").apply { writeText("x") }

        val result = onyxNotes(root).exportedFiles(note("Travel Journal"))

        assertEquals(listOf(shallow), result)
    }

    @Test
    fun `a matching file that is not an image or pdf is skipped`() {
        val root = folder.newFolder()
        File(root, "travel-journal-notes.txt").apply { writeText("x") }
        val pdfMatch = File(root, "travel-journal-scan.pdf").apply { writeText("x") }

        val result = onyxNotes(root).exportedFiles(note("Travel Journal"))

        assertEquals(listOf(pdfMatch), result)
    }

    @Test
    fun `results are ordered by natural sort, not string sort, of file name`() {
        val root = folder.newFolder()
        val page1 = File(root, "travel-journal-page1.png").apply { writeText("x") }
        val page2 = File(root, "travel-journal-page2.png").apply { writeText("x") }
        val page10 = File(root, "travel-journal-page10.png").apply { writeText("x") }

        val result = onyxNotes(root).exportedFiles(note("Travel Journal"))

        assertEquals(listOf(page1, page2, page10), result)
    }

    @Test
    fun `a missing noteRoot yields no exported files`() {
        val root = File(folder.newFolder(), "does-not-exist")

        val result = onyxNotes(root).exportedFiles(note("Travel Journal"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a title with no letters or digits yields no exported files`() {
        val root = folder.newFolder()
        File(root, "travel-journal-cover.png").apply { writeText("x") }

        val result = onyxNotes(root).exportedFiles(note("!!! --- ..."))

        assertTrue(result.isEmpty())
    }
}
