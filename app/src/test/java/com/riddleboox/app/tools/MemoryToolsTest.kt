package com.riddleboox.app.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

class MemoryToolsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun tools(conversationId: String = "evening-1") =
        MemoryTools(folder.newFolder(), conversationId = conversationId, zone = ZoneId.of("UTC"))

    private fun ask(name: String, tools: MemoryTools, vararg arguments: Pair<String, String>): String =
        runBlocking { tools.call(name, JsonObject(arguments.associate { it.first to JsonPrimitive(it.second) })) }

    @Test
    fun `remembering writes one entry that recall finds`() {
        val memory = tools()
        val remembered = ask("remember", memory, "content" to "Thích trà xanh, không thích cà phê.")
        assertTrue(remembered.startsWith("Remembered (#"))

        val recalled = ask("recall_memories", memory)
        assertTrue(recalled.contains("Thích trà xanh, không thích cà phê."))
        assertTrue(recalled.startsWith("1 of 1 remembered things"))
    }

    @Test
    fun `blank content is refused`() {
        val memory = tools()
        assertEquals("Nothing was given to remember.", ask("remember", memory, "content" to "  "))
        assertEquals("Nothing has been remembered yet.", ask("recall_memories", memory))
    }

    @Test
    fun `recall with a query only returns matching entries`() {
        val memory = tools()
        ask("remember", memory, "content" to "Tên là An.")
        ask("remember", memory, "content" to "Đang đọc dở một cuốn tiểu thuyết trinh thám.")

        val matched = ask("recall_memories", memory, "query" to "trinh thám")
        assertTrue(matched.startsWith("1 of 1 remembered things"))
        assertTrue(matched.contains("trinh thám"))

        val unmatched = ask("recall_memories", memory, "query" to "không có gì khớp cả")
        assertEquals("Nothing remembered mentions \"không có gì khớp cả\".", unmatched)
    }

    @Test
    fun `recall matches separated words without requiring accents`() {
        val memory = tools()
        ask("remember", memory, "content" to "Con gái tên Bi; sinh nhật vào tháng mười.")
        ask("remember", memory, "content" to "Đang đọc truyện trinh thám.")

        val recalled = ask("recall_memories", memory, "query" to "con gai sinh nhat")

        assertTrue(recalled.startsWith("1 of 1 remembered things"))
        assertTrue(recalled.contains("Con gái tên Bi"))
        assertTrue(!recalled.contains("trinh thám"))
    }

    @Test
    fun `forgetting by full id removes exactly that entry`() {
        val memory = tools()
        val id = ask("remember", memory, "content" to "Sẽ xóa cái này.")
            .removePrefix("Remembered (#").removeSuffix(").")
        ask("remember", memory, "content" to "Giữ cái này lại.")

        val forgotten = ask("forget_memory", memory, "id" to id)
        assertEquals("Forgotten: \"Sẽ xóa cái này.\".", forgotten)

        val recalled = ask("recall_memories", memory)
        assertTrue(recalled.contains("Giữ cái này lại."))
        assertTrue(!recalled.contains("Sẽ xóa cái này."))
    }

    @Test
    fun `an id naming nothing is refused rather than guessed`() {
        val memory = tools()
        ask("remember", memory, "content" to "Một điều gì đó.")
        val answer = ask("forget_memory", memory, "id" to "zzzzzzzz")
        assertTrue(answer.startsWith("No remembered thing answers to"))
    }

    @Test
    fun `every entry carries the toolbox's conversation id`() {
        val workspace = folder.newFolder()
        val memory = MemoryTools(workspace, conversationId = "evening-42", zone = ZoneId.of("UTC"))
        ask("remember", memory, "content" to "Ghi nhớ một điều.")

        val stored = workspace.resolve("memories.jsonl").readText()
        assertTrue(stored.contains("\"conversation\":\"evening-42\""))
    }

    @Test
    fun `recent memories text lists the newest first, capped at the limit`() {
        val workspace = folder.newFolder()
        File(workspace, "memories.jsonl").writeText(
            (1..7).joinToString("") { i -> """{"id":"id$i","ms":$i,"conversation":"e","content":"fact-$i"}""" + "\n" },
        )

        assertEquals(
            listOf("- fact-7", "- fact-6", "- fact-5", "- fact-4", "- fact-3"),
            recentMemoriesText(workspace, limit = 5).lines(),
        )
    }

    @Test
    fun `recent memories text is empty when nothing has been remembered`() {
        assertEquals("", recentMemoriesText(folder.newFolder()))
    }

    @Test
    fun `writeMemories then readMemories round-trips exactly`() {
        val workspace = folder.newFolder()
        val entries = listOf(
            MemoryEntry(id = "id1", ms = 1L, conversation = "e", content = "fact-1"),
            MemoryEntry(id = "id2", ms = 2L, conversation = "e", content = "fact-2"),
        )

        writeMemories(workspace, entries)

        assertEquals(entries, readMemories(workspace))
    }

    @Test
    fun `writeMemories with an entry filtered out removes only that entry`() {
        val workspace = folder.newFolder()
        val entries = listOf(
            MemoryEntry(id = "id1", ms = 1L, conversation = "e", content = "fact-1"),
            MemoryEntry(id = "id2", ms = 2L, conversation = "e", content = "fact-2"),
            MemoryEntry(id = "id3", ms = 3L, conversation = "e", content = "fact-3"),
        )
        writeMemories(workspace, entries)

        writeMemories(workspace, readMemories(workspace).filterNot { it.id == "id2" })

        assertEquals(
            listOf(
                MemoryEntry(id = "id1", ms = 1L, conversation = "e", content = "fact-1"),
                MemoryEntry(id = "id3", ms = 3L, conversation = "e", content = "fact-3"),
            ),
            readMemories(workspace),
        )
    }
}
