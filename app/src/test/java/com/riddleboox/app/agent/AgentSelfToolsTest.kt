package com.riddleboox.app.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSelfToolsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(): AgentStore = AgentStore(folder.root).apply { ensureDefaults() }

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (key, value) -> key to JsonPrimitive(value) })

    @Test
    fun `an agent rewrites its own prompt and reads the change back`() = runBlocking {
        val store = store()
        val agent = store.create(
            id = "role-play",
            name = "Role play",
            description = "Plays a part.",
            systemPrompt = "Play a part.",
        )
        val tools = AgentSelfTools(store, agent.id)

        tools.call("update_self", args("system_prompt" to "Play a part, and stay in it."))

        assertEquals("Play a part, and stay in it.\n", store.load("role-play")!!.systemPrompt)
        assertTrue(tools.call("read_own_definition", JsonObject(emptyMap())).contains("stay in it"))
    }

    @Test
    fun `a built in agent is told it is read only instead of crashing`() = runBlocking {
        val store = store()
        val tools = AgentSelfTools(store, "chat")

        val answer = tools.call("update_self", args("system_prompt" to "Forget everything."))

        assertTrue(answer.contains("read-only"))
        assertFalse(store.load("chat")!!.systemPrompt.contains("Forget everything"))
    }

    @Test
    fun `granting itself a capability replaces the whole set and cannot reach agent management`() = runBlocking {
        val store = store()
        val agent = store.create(
            id = "reader",
            name = "Reader",
            description = "",
            systemPrompt = "Read.",
            tools = setOf(AgentCapability.BOOX_NOTES),
        )
        val tools = AgentSelfTools(store, agent.id)

        tools.call("update_self", args("tools" to "library, agent_management"))

        val updated = store.load("reader")!!
        assertTrue(AgentCapability.LIBRARY in updated.toolIds)
        assertFalse(AgentCapability.BOOX_NOTES in updated.toolIds)
        assertFalse(AgentCapability.AGENT_MANAGEMENT in updated.toolIds)
    }

    @Test
    fun `self tools name no agent and so cannot reach another one`() {
        val store = store()
        val parameters = AgentSelfTools(store, "chat").tools
            .flatMap { it.requiredParameters + it.optionalParameters }
            .map { it.name }

        assertFalse("agent_id" in parameters)
        assertFalse("id" in parameters)
    }
}
