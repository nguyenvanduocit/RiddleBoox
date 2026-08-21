package com.riddleboox.app.tools

import com.riddleboox.app.history.ConversationStore
import com.riddleboox.app.history.StoredTurn
import com.riddleboox.app.library.Book
import com.riddleboox.app.library.ClosedLibrary
import com.riddleboox.app.library.Epub
import com.riddleboox.app.library.FakeLibrary
import com.riddleboox.app.library.Highlight
import com.riddleboox.app.library.Library
import com.riddleboox.app.library.writeSampleEpub
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

class DiaryToolsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val gombrich = Book(
        id = "b1",
        title = "Câu Chuyện Nghệ Thuật",
        authors = "E. H. Gombrich",
        path = "/sdcard/Books/gombrich.epub",
        format = "epub",
        page = 120,
        pages = 688,
        lastOpenedMs = 1_660_000_000_000,
    )

    private val istio = Book(
        id = "b2",
        title = "Istio in Action",
        authors = "Christian E. Posta",
        path = "/sdcard/Books/istio.pdf",
        format = "pdf",
        page = 25,
        pages = 436,
        lastOpenedMs = 1_657_880_252_657,
    )

    private val marks = listOf(
        Highlight("db6066f32afb442ba54491584d926770", "b2", "service mesh is critical infrastructure", "cửa ngõ", "Part 1", 25, 1_657_880_252_657),
        Highlight("c744c92c553040b9a17597d5f5b1780b", "b1", "Không có cái gọi là Nghệ Thuật", "", "Dẫn nhập", 15, 1_660_000_000_000),
    )

    private val shelf = FakeLibrary(listOf(gombrich, istio), marks)

    private fun tools(
        library: Library = shelf,
        memory: DiaryMemory = FakeMemory(emptyList()),
        fillerChars: Int = 0,
        openReader: suspend (Book) -> Boolean = { false },
    ) = DiaryTools(
        library = library,
        memory = memory,
        zone = ZoneId.of("UTC"),
        openBook = { book ->
            if (book.isEpub) Epub.open(writeSampleEpub(folder.newFile(), fillerChars)) else null
        },
        openReader = openReader,
    )

    private fun ask(name: String, vararg arguments: Pair<String, Any>, tools: DiaryTools = tools()): String =
        runBlocking {
            tools.call(name, JsonObject(arguments.associate { it.first to JsonPrimitive(it.second.toString()) }))
        }

    private fun note(name: String, vararg arguments: Pair<String, Any>, tools: DiaryTools = tools()): String =
        tools.note(name, JsonObject(arguments.associate { it.first to JsonPrimitive(it.second.toString()) }))

    // ---- the shelf ----

    @Test
    fun `with no query the shelf is what was opened last`() {
        val answer = ask("search_library")
        assertTrue(answer.startsWith("2 books on this reader; the 2 opened most recently:"))
        assertTrue(
            answer.contains(
                "Câu Chuyện Nghệ Thuật | E. H. Gombrich | epub | page 120 of 688 | opened 2022-08-08 | 1 marked passages",
            ),
        )
        assertTrue(answer.contains("Istio in Action | Christian E. Posta | pdf | page 25 of 436 | opened 2022-07-15"))
    }

    @Test
    fun `a query narrows the shelf`() {
        val answer = ask("search_library", "query" to "gombrich")
        assertTrue(answer.startsWith("1 of 2 books answer to \"gombrich\":"))
        assertFalse(answer.contains("Istio"))
    }

    @Test
    fun `open reader resolves a book and launches the selected title`() {
        var opened: Book? = null
        val answer = ask(
            "open_reader",
            "book" to "gombrich",
            tools = tools(openReader = { book -> opened = book; true }),
        )

        assertEquals("Câu Chuyện Nghệ Thuật", opened?.title)
        assertTrue(answer.contains("Opened \"Câu Chuyện Nghệ Thuật\" in the BOOX reader"))
    }

    @Test
    fun `a book that is not there is said to not be there`() {
        assertTrue(ask("search_library", "query" to "Chúa tể").startsWith("No book on this reader answers to"))
        assertTrue(ask("book_contents", "book" to "Chúa tể").startsWith("No book on this reader answers to"))
    }

    /** Whatever the model asks for, one call cannot drag back the whole library. */
    @Test
    fun `a limit the model invents is clamped`() {
        val many = FakeLibrary(List(100) { gombrich.copy(id = "b$it", title = "Cuốn $it") })
        val answer = ask("search_library", "limit" to 500, tools = tools(library = many))
        assertEquals(41, answer.lines().size)
    }

    // ---- inside a book ----

    @Test
    fun `the contents are numbered from one`() {
        val answer = ask("book_contents", "book" to "gombrich")
        assertTrue(answer.contains("1. Chương một"))
        assertTrue(answer.contains("2. Chương hai"))
        assertTrue("dòng đầu vẫn là cuốn sách", answer.startsWith("Câu Chuyện Nghệ Thuật"))
    }

    @Test
    fun `a chapter comes back as its words`() {
        val answer = ask("read_book", "book" to "gombrich", "chapter" to 1)
        assertTrue(answer.startsWith("\"Câu Chuyện Nghệ Thuật\", chapter 1 — Chương một"))
        assertTrue(answer.contains("Mèo con ngồi trên mái nhà"))
    }

    @Test
    fun `a chapter that is not there is said to not be there`() {
        assertEquals(
            "\"Câu Chuyện Nghệ Thuật\" has 3 chapters; there is no chapter 9.",
            ask("read_book", "book" to "gombrich", "chapter" to 9),
        )
    }

    /** A cut the model is not told about is a chapter it thinks it has read. */
    @Test
    fun `a long chapter says where it was cut and how to go on`() {
        val long = tools(fillerChars = 20_000)
        val answer = ask("read_book", "book" to "gombrich", "chapter" to 1, tools = long)
        assertTrue(answer.contains("(characters 0 to 5000 of "))
        assertTrue(answer.trimEnd().endsWith("[cut here; call read_book again with offset=5000 to read on]"))

        val onwards = ask("read_book", "book" to "gombrich", "chapter" to 1, "offset" to 5000, tools = long)
        assertTrue(onwards.contains("(characters 5000 to 10000 of "))
    }

    @Test
    fun `a phrase is found without knowing its chapter`() {
        val answer = ask("search_in_book", "book" to "gombrich", "query" to "con meo")
        assertTrue(answer.startsWith("1 passages in \"Câu Chuyện Nghệ Thuật\" containing \"con meo\":"))
        assertTrue(answer.contains("chapter 2 — Chương hai"))
        assertTrue("phải trích đúng chữ trong sách", answer.contains("Con mèo lại đến"))
    }

    /**
     * Half the reader's shelf is PDFs, and the answer for them has to point at
     * the half of the library that does work rather than just refusing.
     */
    @Test
    fun `a PDF cannot be read but its marks still can`() {
        val answer = ask("read_book", "book" to "istio", "chapter" to 1)
        assertTrue(answer.contains("is a pdf and cannot be read from the inside"))
        assertTrue(answer.contains("read_highlights"))
        assertTrue(ask("book_contents", "book" to "istio").contains("read_highlights"))
    }

    // ---- what the reader wrote ----

    @Test
    fun `the marks in one book come back with their notes`() {
        val answer = ask("read_highlights", "book" to "istio")
        assertTrue(answer.startsWith("1 of 1 marked passages in \"Istio in Action\", newest first:"))
        assertTrue(answer.contains("p.25 · Part 1 · 2022-07-15"))
        assertTrue(answer.contains("\"service mesh is critical infrastructure\""))
        assertTrue(answer.contains("note: cửa ngõ"))
    }

    /** Across the whole library a mark is meaningless without its book's name. */
    @Test
    fun `marks across the library say which book each came from`() {
        val answer = ask("read_highlights")
        assertTrue(answer.startsWith("2 of 2 marked passages across the library, newest first:"))
        assertTrue(answer.contains("Istio in Action · p.25 · Part 1"))
        assertTrue(answer.contains("Câu Chuyện Nghệ Thuật · p.15 · Dẫn nhập"))
    }

    @Test
    fun `an unmarked book says so`() {
        val clean = FakeLibrary(listOf(gombrich), emptyList())
        assertEquals(
            "The writer marked nothing in \"Câu Chuyện Nghệ Thuật\".",
            ask("read_highlights", "book" to "gombrich", tools = tools(library = clean)),
        )
    }

    // ---- earlier evenings ----

    @Test
    fun `an earlier evening can be recalled`() {
        val memory = FakeMemory(listOf(Recalled("evening-1", 1_660_000_000_000, "Tên tôi là Được", "Ta sẽ nhớ.")))
        val answer = ask("recall_diary", "query" to "được", tools = tools(memory = memory))
        assertTrue(answer.startsWith("1 earlier exchanges mention \"được\", newest first:"))
        assertTrue(answer.contains("2022-08-08 — they wrote: Tên tôi là Được"))
        assertTrue(answer.contains("you answered: Ta sẽ nhớ."))
    }

    /** The real memory, over the real store — tones folded away on both sides. */
    @Test
    fun `the store is searched without tones`() {
        val store = ConversationStore(folder.newFolder())
        store.appendTurn("c1", 1, StoredTurn(1_660_000_000_000, "Tên tôi là Được", "Ta sẽ nhớ."))
        store.appendTurn("c1", 1, StoredTurn(1_660_000_001_000, "Trời hôm nay đẹp", "Vậy sao."))

        val found = StoredMemory(store).recall("duoc", limit = 5)
        assertEquals(1, found.size)
        assertEquals("Tên tôi là Được", found.single().wrote)
        assertEquals(emptyList<Recalled>(), StoredMemory(store).recall("hắc ám", limit = 5))
    }

    // ---- taking things off the device ----

    @Test
    fun `deleting a book takes the file, the entry and the marks`() {
        val file = folder.newFile("gombrich.epub").also { it.writeText("nội dung") }
        val shelf = FakeLibrary(listOf(gombrich.copy(path = file.path), istio), marks)

        val answer = ask("delete_book", "book" to "gombrich", tools = tools(library = shelf))

        assertFalse("file phải biến mất", file.exists())
        assertEquals(listOf("Istio in Action"), shelf.books().map { it.title })
        assertEquals(1, shelf.highlights(null).size)
        assertTrue(answer.contains("\"Câu Chuyện Nghệ Thuật\": gone from the library"))
        assertTrue(answer.contains("1 marked passage gone"))
        assertTrue(answer.contains("the file is deleted (${file.path})"))
    }

    @Test
    fun `a book can be taken off the shelf with its file left alone`() {
        val file = folder.newFile("keep.epub").also { it.writeText("nội dung") }
        val shelf = FakeLibrary(listOf(gombrich.copy(path = file.path)), marks)

        val answer = ask(
            "delete_book",
            "book" to "gombrich",
            "keep_file" to "true",
            tools = tools(library = shelf),
        )

        assertTrue("file phải còn nguyên", file.exists())
        assertTrue(shelf.books().isEmpty())
        assertTrue(answer.contains("the file is left where it was"))
    }

    /**
     * The half-done state that cannot be recovered from is entry-gone,
     * file-still-there: nothing can name that file again to finish the job. So
     * a file that will not delete stops the whole removal.
     */
    @Test
    fun `a file that will not delete leaves the book on the shelf`() {
        val locked = folder.newFolder("locked")
        val file = File(locked, "stuck.epub").also { it.writeText("nội dung") }
        assertTrue("thư mục phải khoá được, nếu không test này vô nghĩa", locked.setWritable(false))
        val shelf = FakeLibrary(listOf(gombrich.copy(path = file.path)), marks)

        val answer = ask("delete_book", "book" to "gombrich", tools = tools(library = shelf))
        locked.setWritable(true)

        assertTrue("file vẫn phải còn", file.exists())
        assertEquals(listOf("Câu Chuyện Nghệ Thuật"), shelf.books().map { it.title })
        assertEquals(2, shelf.highlights(null).size)
        assertTrue(answer.contains("would not delete, so nothing was taken out of the library either"))
    }

    @Test
    fun `an unnamed book is not a book chosen at random`() {
        val shelf = FakeLibrary(listOf(gombrich, istio), marks)

        val answer = ask("delete_book", tools = tools(library = shelf))

        assertEquals("Name the book to remove. search_library says what is there.", answer)
        assertEquals(2, shelf.books().size)
    }

    @Test
    fun `a marked passage is removed by the id printed beside it`() {
        val shelf = FakeLibrary(listOf(gombrich, istio), marks)
        val printed = ask("read_highlights", "book" to "istio", tools = tools(library = shelf))
        assertTrue("đọc phải in id ra", printed.contains("#db6066f3"))

        val answer = ask("delete_highlight", "id" to "#db6066f3", tools = tools(library = shelf))

        assertEquals(listOf("Không có cái gọi là Nghệ Thuật"), shelf.highlights(null).map { it.quote })
        assertTrue(answer.startsWith("The marked passage is gone for good:"))
    }

    @Test
    fun `an id that names two marked passages removes neither`() {
        val twins = listOf(
            marks[0].copy(id = "aaaa1111"),
            marks[1].copy(id = "aaaa2222"),
        )
        val shelf = FakeLibrary(listOf(gombrich, istio), twins)

        val answer = ask("delete_highlight", "id" to "aaaa", tools = tools(library = shelf))

        assertEquals(2, shelf.highlights(null).size)
        assertTrue(answer.contains("names 2 marked passages; give more of the id"))
    }

    @Test
    fun `an evening is burned by id and words that name several burn none`() {
        val evenings = listOf(
            Evening("evening-1", 1_660_000_000_000, "Tên tôi là Được", 3),
            Evening("evening-2", 1_660_000_100_000, "Tên tôi là ai", 1),
        )
        val memory = FakeMemory(emptyList(), evenings)

        val many = ask("forget_diary", "query" to "Tên tôi", tools = tools(memory = memory))
        assertTrue(many.contains("runs through 2 evenings"))
        assertTrue(many.contains("#evening-"))
        assertTrue("chưa được đốt gì", memory.burned.isEmpty())

        val one = ask("forget_diary", "id" to "#evening-2", tools = tools(memory = memory))
        assertEquals(listOf("evening-2"), memory.burned)
        assertTrue(one.startsWith("That evening is burned"))
    }

    /** The real memory, over the real store: a burned evening is off the disk. */
    @Test
    fun `burning an evening takes it out of the store`() {
        val store = ConversationStore(folder.newFolder())
        store.appendTurn("c1", 1, StoredTurn(1_660_000_000_000, "Tên tôi là Được", "Ta sẽ nhớ."))
        store.appendTurn("c2", 2, StoredTurn(1_660_000_001_000, "Trời hôm nay đẹp", "Vậy sao."))
        val memory = StoredMemory(store)

        assertEquals(2, memory.evenings("", limit = 10).size)
        assertTrue("một mẩu id đủ gọi tên một buổi tối", memory.forget("c1"))

        assertEquals(listOf("c2"), memory.evenings("", limit = 10).map { it.id })
        assertEquals(null, store.load("c1"))
        assertFalse("không có buổi tối nào tên vậy", memory.forget("c9"))
    }

    // ---- when the shelf itself is shut ----

    /**
     * A reader whose library will not open is a different answer from a reader
     * with no books, and the diary must be able to tell the writer which.
     */
    @Test
    fun `a library that cannot be reached says so instead of throwing`() {
        val answer = ask("search_library", tools = tools(library = ClosedLibrary()))
        assertTrue(answer.startsWith("That could not be looked up:"))
        assertTrue(answer.contains("Metadata không mở được"))
    }

    @Test
    fun `a tool that does not exist is not a crash`() {
        assertEquals("There is nothing called summon_basilisk to consult.", ask("summon_basilisk"))
    }

    // ---- the descriptors themselves ----

    @Test
    fun `every tool the model is offered can actually be called`() {
        val offered = tools().tools.map { it.name }.toSet()
        assertEquals(
            setOf(
                "search_library", "open_reader", "book_contents", "read_book", "search_in_book", "read_highlights",
                "recall_diary", "delete_book", "delete_highlight", "forget_diary",
            ),
            offered,
        )
        for (name in offered) {
            assertFalse("$name không được rơi vào nhánh không tồn tại", ask(name).startsWith("There is nothing called"))
        }
    }

    // ---- what the page says while a tool is still running ----

    @Test
    fun `each tool says what it is doing, quoting the book by name`() {
        val book = "Đắc Nhân Tâm"
        val captions = mapOf(
            "search_library" to "Đang lần trên giá sách…",
            "open_reader" to "Đang mở “$book”…",
            "book_contents" to "Đang xem mục lục “$book”…",
            "read_book" to "Đang đọc “$book”…",
            "search_in_book" to "Đang lần trong “$book”…",
            "read_highlights" to "Đang xem chỗ đã đánh dấu trong “$book”…",
            "recall_diary" to "Đang lật lại những trang cũ…",
            "delete_book" to "Đang bỏ đi “$book”…",
            "delete_highlight" to "Đang xóa một chỗ đánh dấu…",
            "forget_diary" to "Đang đốt một buổi tối cũ…",
        )
        for ((name, caption) in captions) {
            assertEquals(name, caption, note(name, "book" to book))
        }
    }

    /** No book named is a different sentence, not the same one with a blank in it. */
    @Test
    fun `read_highlights without a book is not narrowed to one`() {
        assertEquals("Đang xem những chỗ đã đánh dấu…", note("read_highlights"))
    }

    @Test
    fun `a named tool without a book still says what it is doing, just not to whom`() {
        assertEquals("Đang mở sách…", note("open_reader"))
        assertEquals("Đang bỏ đi sách…", note("delete_book"))
    }

    @Test
    fun `a tool the model invented gets the same shrug as everything else unnamed`() {
        assertEquals("Đang lần giở…", note("summon_basilisk"))
    }
}

private class FakeMemory(
    private val found: List<Recalled>,
    private val evenings: List<Evening> = emptyList(),
) : DiaryMemory {
    val burned = mutableListOf<String>()

    override fun recall(query: String, limit: Int): List<Recalled> = found.take(limit)

    override fun evenings(query: String, limit: Int): List<Evening> =
        evenings.filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) }.take(limit)

    override fun forget(id: String): Boolean {
        val named = evenings.map { it.id }.singleOrNull { it.startsWith(id) } ?: return false
        burned += named
        return true
    }
}
