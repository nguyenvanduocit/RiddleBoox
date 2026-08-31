package com.riddleboox.app.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentCapabilitiesTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `built ins receive all regular capabilities and only manager receives agent management`() {
        val store = AgentStore(folder.root)
        store.ensureDefaults()

        assertEquals(AgentCapability.builtinDefaults, store.load("chat")!!.toolIds)
        assertFalse(AgentCapability.AGENT_MANAGEMENT in store.load("chat")!!.toolIds)
        assertEquals(AgentCapability.builtinDefaults, store.load("library")!!.toolIds)
        assertEquals(AgentCapability.builtinDefaults, store.load("notes")!!.toolIds)
        assertFalse(AgentCapability.AGENT_MANAGEMENT in store.load("notes")!!.toolIds)
        assertEquals(AgentCapability.supported, store.load("agent-manager")!!.toolIds)
        assertThrows(IllegalStateException::class.java) {
            store.update("agent-manager", tools = setOf(AgentCapability.LIBRARY))
        }
        assertTrue(AgentCapability.AGENT_MANAGEMENT in store.load("agent-manager")!!.toolIds)

        val custom = store.create(
            id = "role-play",
            name = "Role play",
            description = "",
            systemPrompt = "Nhập vai.",
            tools = setOf(AgentCapability.AGENT_MANAGEMENT),
        )
        assertFalse(AgentCapability.AGENT_MANAGEMENT in custom.toolIds)
    }

    @Test
    fun `manager descriptors are not part of ordinary workspace tools`() {
        val store = AgentStore(folder.root)
        store.ensureDefaults()
        val managerNames = AgentManagerTools(store).tools.map { it.name }
        val workspaceNames = WorkspaceTools(folder.newFolder()).tools.map { it.name }

        assertTrue("create_agent" in managerNames)
        assertTrue("update_agent" in managerNames)
        assertFalse("create_agent" in workspaceNames)
        assertFalse("delete_agent" in workspaceNames)
    }

    @Test
    fun `manager can configure ordinary capabilities but cannot grant itself to another agent`() = runBlocking {
        val store = AgentStore(folder.root)
        store.ensureDefaults()
        val manager = AgentManagerTools(store)
        val args = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Book role"),
                "system_prompt" to JsonPrimitive("Đọc sách."),
                "tools" to JsonPrimitive("library"),
                "greetings" to JsonPrimitive("Mở sách đi.\nTa cùng đọc."),
            ),
        )
        manager.call("create_agent", args)

        val created = store.load("book-role")!!
        assertTrue(AgentCapability.LIBRARY in created.toolIds)
        assertFalse(AgentCapability.AGENT_MANAGEMENT in created.toolIds)
        assertEquals(listOf("Mở sách đi.", "Ta cùng đọc."), created.greetings)

        val denied = manager.call(
            "update_agent",
            JsonObject(
                mapOf(
                    "agent_id" to JsonPrimitive("chat"),
                    "name" to JsonPrimitive("Không được sửa"),
                ),
            ),
        )
        assertTrue(denied.contains("read-only"))
    }

    @Test
    fun `manager cannot rewrite a builtin or change its capabilities`() = runBlocking {
        val store = AgentStore(folder.root).apply { ensureDefaults() }
        val manager = AgentManagerTools(store)

        val refusedTools = manager.call(
            "update_agent",
            JsonObject(
                mapOf(
                    "agent_id" to JsonPrimitive("notes"),
                    "tools" to JsonPrimitive("boox_notes, library"),
                ),
            ),
        )
        val refusedPrompt = manager.call(
            "update_agent",
            JsonObject(
                mapOf(
                    "agent_id" to JsonPrimitive("notes"),
                    "system_prompt" to JsonPrimitive("Replace the factory."),
                ),
            ),
        )

        assertTrue(refusedTools.contains("read-only"))
        assertTrue(refusedPrompt.contains("read-only"))
        assertEquals(AgentCapability.builtinDefaults, store.load("notes")!!.toolIds)
        assertFalse(store.load("notes")!!.systemPrompt.contains("Replace the factory"))
    }

    @Test
    fun `manager reads greetings and capabilities before replacing them`() = runBlocking {
        val store = AgentStore(folder.root).apply { ensureDefaults() }
        val answer = AgentManagerTools(store).call(
            "read_agent_prompt",
            JsonObject(mapOf("agent_id" to JsonPrimitive("library"))),
        )

        assertTrue(answer.contains("library"))
        assertTrue(answer.contains("dilib"))
        assertTrue(answer.contains("boox_notes"))
        assertTrue(answer.contains("greetings:"))
        assertTrue(answer.contains("Some book lies open in front of you."))
        assertTrue(answer.contains("[library/system.md]"))
    }
}
