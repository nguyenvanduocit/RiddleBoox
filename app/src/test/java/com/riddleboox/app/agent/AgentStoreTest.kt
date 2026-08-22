package com.riddleboox.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.Json
import java.io.File

class AgentStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `defaults are materialized as prompt files and private workspaces`() {
        val store = AgentStore(folder.root)
        store.ensureDefaults()

        val notes = store.load("notes")!!
        assertEquals("notes", notes.id)
        assertTrue(notes.systemPrompt.isNotBlank())
        assertTrue(store.promptFile("notes").isFile)
        assertTrue(notes.workspace.isDirectory)
        assertTrue(AgentCapability.BOOX_NOTES in notes.toolIds)
        assertTrue(notes.greetings.size >= 5)
        assertNotEquals(store.load("chat")!!.greetings.toSet(), notes.greetings.toSet())

        val tutor = store.load("english-tutor")!!
        assertTrue(tutor.builtin)
        assertTrue(tutor.systemPrompt.contains("recall_memories"))
        assertEquals(setOf(AgentCapability.WORKSPACE), tutor.toolIds)

        assertEquals(5, store.list().size)
    }

    /**
     * A missing `system.md` beside an otherwise-intact folder must not be
     * treated as "no such agent" — [AgentStore.create] refuses to write into
     * a folder that already exists, so routing this case there crashes
     * [ensureDefaults] outright instead of reseeding the prompt.
     */
    @Test
    fun `a prompt file missing beside an intact folder is reseeded, not treated as a new agent`() {
        val store = AgentStore(folder.root)
        store.ensureDefaults()
        assertTrue(store.promptFile("library").delete())

        store.ensureDefaults()

        val reseeded = store.load("library")!!
        assertTrue(reseeded.systemPrompt.isNotBlank())
        assertEquals(5, store.list().size)
    }

    @Test
    fun `custom edits survive a second default initialization`() {
        val store = AgentStore(folder.root)
        store.ensureDefaults()
        store.create("notes-custom", "Notes riêng", "", "Prompt mặc định")
        store.update("notes-custom", systemPrompt = "Prompt riêng của tôi")

        store.ensureDefaults()

        assertEquals("Prompt riêng của tôi", store.load("notes-custom")!!.systemPrompt.trim())
    }

    /**
     * The agents screen is the only thing that decides what a built-in may
     * reach for. Opening the app again must not quietly hand back a capability
     * the reader took away.
     */
    @Test
    fun `a builtin capability choice survives the next start`() {
        val store = AgentStore(folder.root)
        store.ensureDefaults()
        val current = store.load("library")!!
        val chosen = current.manifest.copy(
            toolIds = setOf(AgentCapability.WORKSPACE, AgentCapability.LIBRARY),
        )
        File(folder.root, "agents/library/agent.json").writeText(
            Json { encodeDefaults = true }.encodeToString(AgentManifest.serializer(), chosen),
        )

        store.ensureDefaults()

        assertFalse(AgentCapability.DILIB in store.load("library")!!.toolIds)
        assertTrue(AgentCapability.LIBRARY in store.load("library")!!.toolIds)
    }

    @Test
    fun `custom agents can be updated and deleted but builtins cannot`() {
        val store = AgentStore(folder.root)
        store.ensureDefaults()
        store.create(
            "novel",
            "Role play",
            "Một nhân vật",
            "Hãy nhập vai.",
            greetings = listOf("  Xin chào riêng. ", "", "Xin chào riêng.", "Câu thứ hai."),
        )

        assertEquals(listOf("Xin chào riêng.", "Câu thứ hai."), store.load("novel")!!.greetings)

        assertEquals("Nhân vật", store.update("novel", name = "Nhân vật").name)
        assertEquals(
            listOf("Câu cập nhật."),
            store.update("novel", greetings = listOf("Câu cập nhật.")).greetings,
        )
        assertThrows(IllegalStateException::class.java) {
            store.update("chat", name = "Không được sửa")
        }
        assertTrue(store.delete("novel"))
        assertFalse(store.delete("chat"))
        assertEquals(null, store.load("novel"))
    }

    @Test
    fun `ids are constrained and names get stable slugs`() {
        assertTrue(AgentStore.isValidId("book-club_2"))
        assertFalse(AgentStore.isValidId("../outside"))
        assertEquals("my-role-play", AgentStore.slugFor(" My role play "))
    }
}
