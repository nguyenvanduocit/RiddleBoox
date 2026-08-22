package com.riddleboox.app.backup

import com.riddleboox.app.agent.AgentDefinition
import com.riddleboox.app.agent.AgentManifest
import com.riddleboox.app.history.StoredConversation
import com.riddleboox.app.history.StoredTurn
import com.riddleboox.app.tools.MemoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupExportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dayAndTime = SimpleDateFormat("d/M/yyyy · HH:mm", Locale.getDefault())

    private fun agent(id: String, name: String) = AgentDefinition(
        manifest = AgentManifest(id = id, name = name),
        systemPrompt = "Bạn là $name.",
        workspace = folder.newFolder(id),
    )

    @Test
    fun `header carries the export time`() {
        val ms = 1_700_000_000_000L
        val text = wholeDiaryBackup(emptyList(), emptyList(), emptyMap(), exportedAtMs = ms)

        assertTrue(text.startsWith("SAO LƯU RIDDLEBOOX — " + dayAndTime.format(Date(ms))))
    }

    @Test
    fun `each agent's conversations and memories land under its own name, not another agent's`() {
        val chat = agent("chat", "Chit chat")
        val notes = agent("notes", "Quản lý note")

        val chatConversation = StoredConversation(
            id = "c1",
            startedAtMs = 1_000L,
            agentId = "chat",
            turns = listOf(StoredTurn(timestampMs = 1_000L, transcript = "Chào nhé", reply = "Chào bạn")),
        )
        val notesConversation = StoredConversation(
            id = "c2",
            startedAtMs = 2_000L,
            agentId = "notes",
            turns = listOf(StoredTurn(timestampMs = 2_000L, transcript = "Ghi lại việc này", reply = "Đã ghi")),
        )
        val chatMemory = MemoryEntry(id = "m1", ms = 1_000L, conversation = "c1", content = "Thích trà xanh")
        val notesMemory = MemoryEntry(id = "m2", ms = 2_000L, conversation = "c2", content = "Đang học guitar")

        val text = wholeDiaryBackup(
            agents = listOf(chat, notes),
            conversations = listOf(chatConversation, notesConversation),
            memories = mapOf("chat" to listOf(chatMemory), "notes" to listOf(notesMemory)),
            exportedAtMs = 0L,
        )

        val chatSection = text.substringAfter("HỘI THOẠI").substringBefore("GHI NHỚ")
        assertTrue(chatSection.contains("Chào nhé"))
        assertTrue(chatSection.contains("Ghi lại việc này"))
        // "Chit chat" heading precedes its own conversation and not the other agent's.
        assertTrue(chatSection.indexOf("Chit chat") < chatSection.indexOf("Chào nhé"))
        assertTrue(chatSection.indexOf("Chit chat") < chatSection.indexOf("Ghi lại việc này"))

        val memorySection = text.substringAfter("GHI NHỚ")
        assertTrue(memorySection.contains("Thích trà xanh"))
        assertTrue(memorySection.contains("Đang học guitar"))
        // Each memory sits under its own agent's heading, not the other one's.
        assertTrue(memorySection.indexOf("Chit chat") < memorySection.indexOf("Thích trà xanh"))
        assertTrue(memorySection.indexOf("Quản lý note") < memorySection.indexOf("Đang học guitar"))
    }

    @Test
    fun `an agent with no conversations and no memories gets no empty heading in either section`() {
        val silent = agent("silent", "Im lặng")
        val text = wholeDiaryBackup(
            agents = listOf(silent),
            conversations = emptyList(),
            memories = emptyMap(),
            exportedAtMs = 0L,
        )

        // Its own definition still names it in CÁC AGENT — only the two
        // per-agent sections skip an agent with nothing to show.
        val conversationsSection = text.substringAfter("HỘI THOẠI").substringBefore("GHI NHỚ")
        val memoriesSection = text.substringAfter("GHI NHỚ")
        assertFalse(conversationsSection.contains("Im lặng"))
        assertFalse(memoriesSection.contains("Im lặng"))
        assertTrue(text.contains("(chưa có gì)"))
    }

    @Test
    fun `agent definitions all appear, counted in the section heading`() {
        val chat = agent("chat", "Chit chat")
        val notes = agent("notes", "Quản lý note")

        val text = wholeDiaryBackup(listOf(chat, notes), emptyList(), emptyMap(), exportedAtMs = 0L)

        assertTrue(text.contains("CÁC AGENT (2)"))
        assertTrue(text.contains("Tên: Chit chat"))
        assertTrue(text.contains("Tên: Quản lý note"))
    }
}
