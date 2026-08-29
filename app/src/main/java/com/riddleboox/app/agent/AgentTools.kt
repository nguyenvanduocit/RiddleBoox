package com.riddleboox.app.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.riddleboox.app.reply.Toolbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val LIST_AGENTS = "list_agents"
private const val CREATE_AGENT = "create_agent"
private const val UPDATE_AGENT = "update_agent"
private const val DELETE_AGENT = "delete_agent"
private const val READ_PROMPT = "read_agent_prompt"

/** Tools exposed only to the built-in agent-manager agent. */
class AgentManagerTools(private val store: AgentStore) : Toolbox {

    init {
        store.ensureDefaults()
    }

    override val tools: List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = LIST_AGENTS,
            description = "When the writer asks to list or show agents, call this tool. List every RiddleBoox agent, including built-ins and user-created agents.",
            requiredParameters = emptyList(),
            optionalParameters = emptyList(),
        ),
        ToolDescriptor(
            name = CREATE_AGENT,
            description = "Create an agent with a private workspace and a system prompt saved as system.md.",
            requiredParameters = listOf(
                ToolParameterDescriptor("name", "Human-readable agent name.", ToolParameterType.String),
                ToolParameterDescriptor("system_prompt", "The complete system prompt.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("id", "Stable lowercase id; generated from name when omitted.", ToolParameterType.String),
                ToolParameterDescriptor("description", "Short purpose shown in the agent list.", ToolParameterType.String),
                ToolParameterDescriptor("tools", "Comma-separated capabilities: library, dilib, or boox_notes. Every agent already carries workspace, memory and drawing by default. Agent-management is reserved for the built-in manager.", ToolParameterType.String),
                ToolParameterDescriptor("greetings", "Opening lines, one greeting per line, for this agent.", ToolParameterType.String),
            ),
        ),
        ToolDescriptor(
            name = UPDATE_AGENT,
            description = "Update a user-created agent's name, description, capabilities, greetings, or system prompt file. Built-in agents are read-only.",
            requiredParameters = listOf(
                ToolParameterDescriptor("agent_id", "The stable agent id.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("name", "New display name.", ToolParameterType.String),
                ToolParameterDescriptor("description", "New purpose description.", ToolParameterType.String),
                ToolParameterDescriptor("system_prompt", "New complete system prompt.", ToolParameterType.String),
                ToolParameterDescriptor("tools", "Comma-separated capabilities: library, dilib, or boox_notes. Workspace, memory and drawing are every agent's by default. Agent-management remains manager-only.", ToolParameterType.String),
                ToolParameterDescriptor("greetings", "Replacement opening lines, one greeting per line, for this agent.", ToolParameterType.String),
            ),
        ),
        ToolDescriptor(
            name = DELETE_AGENT,
            description = "Delete a user-created agent and its private workspace. Built-ins cannot be deleted.",
            requiredParameters = listOf(
                ToolParameterDescriptor("agent_id", "The stable agent id.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
        ToolDescriptor(
            name = READ_PROMPT,
            description = "Read an agent's system prompt from its system.md file.",
            requiredParameters = listOf(
                ToolParameterDescriptor("agent_id", "The stable agent id.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
    )

    override suspend fun call(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                LIST_AGENTS -> listAgents()
                CREATE_AGENT -> createAgent(arguments)
                UPDATE_AGENT -> updateAgent(arguments)
                DELETE_AGENT -> deleteAgent(arguments)
                READ_PROMPT -> readPrompt(arguments)
                else -> "There is nothing called $name in the agent manager."
            }
        }.getOrElse { error -> "Agent manager operation failed: ${error.message ?: error.javaClass.simpleName}" }
    }

    override fun note(name: String, arguments: JsonObject): String = when (name) {
        LIST_AGENTS, READ_PROMPT -> "looking over the agents…"
        CREATE_AGENT -> "creating an agent…"
        UPDATE_AGENT -> "updating an agent…"
        DELETE_AGENT -> "deleting an agent…"
        else -> "managing the agents…"
    }

    private fun listAgents(): String = store.list().joinToString("\n") { agent ->
        "${agent.id} | ${agent.name} | ${if (agent.builtin) "built-in" else "user"} | tools=${agent.toolIds.joinToString(",")} | greetings=${agent.greetings.size} | ${agent.description}"
    }.ifBlank { "No agents exist." }

    private fun createAgent(arguments: JsonObject): String {
        val name = arguments.text("name")
        val prompt = arguments.text("system_prompt")
        require(name.isNotBlank()) { "name is required" }
        require(prompt.isNotBlank()) { "system_prompt is required" }
        val requested = AgentStore.slugFor(arguments.text("id").ifBlank { name })
        var id = requested
        var suffix = 2
        while (store.load(id) != null) id = "$requested-${suffix++}"
        val created = store.create(
            id = id,
            name = name,
            description = arguments.text("description"),
            systemPrompt = prompt,
            tools = arguments.text("tools").toToolIds(),
            greetings = arguments.text("greetings").toGreetings(),
        )
        return "Created agent ${created.id} (${created.name}); tools=${created.toolIds.joinToString(",")}; greetings=${created.greetings.size}; prompt: system.md; workspace: workspace/."
    }

    private fun updateAgent(arguments: JsonObject): String {
        val id = arguments.text("agent_id")
        require(id.isNotBlank()) { "agent_id is required" }
        val current = store.load(id) ?: return "Unknown agent: $id"
        if (current.builtin) return "Cannot update built-in agent $id; built-in agents are read-only."
        val updated = store.update(
            id = id,
            name = arguments.text("name").ifBlank { null },
            description = arguments.text("description").ifBlank { null },
            systemPrompt = arguments.text("system_prompt").ifBlank { null },
            tools = arguments.text("tools").takeIf { it.isNotBlank() }?.toToolIds(),
            greetings = arguments.text("greetings").takeIf { it.isNotBlank() }?.toGreetings(),
        )
        return "Updated agent ${updated.id} (${updated.name}); tools=${updated.toolIds.joinToString(",")}; greetings=${updated.greetings.size}."
    }

    private fun deleteAgent(arguments: JsonObject): String {
        val id = arguments.text("agent_id")
        require(id.isNotBlank()) { "agent_id is required" }
        val agent = store.load(id) ?: return "Unknown agent: $id"
        if (agent.builtin) return "Cannot delete built-in agent $id; it is a recovery path."
        return if (store.delete(id)) "Deleted agent $id and its workspace." else "Could not delete agent $id."
    }

    private fun readPrompt(arguments: JsonObject): String {
        val id = arguments.text("agent_id")
        val agent = store.load(id) ?: return "Unknown agent: $id"
        return "[$id/system.md]\n${agent.systemPrompt}"
    }
}

/** Routes a model's tool calls to several capability-specific toolboxes. */
class CompositeToolbox(private val boxes: List<Toolbox>) : Toolbox {

    private val byName = boxes.flatMap { it.tools }.associateBy { it.name }

    init {
        check(byName.size == boxes.sumOf { it.tools.size }) { "Tool names must be unique" }
    }

    override val tools: List<ToolDescriptor> = boxes.flatMap { it.tools }

    override suspend fun call(name: String, arguments: JsonObject): String =
        boxes.firstOrNull { box -> box.tools.any { it.name == name } }
            ?.call(name, arguments)
            ?: "There is nothing called $name to consult."

    override fun note(name: String, arguments: JsonObject): String =
        boxes.firstOrNull { box -> box.tools.any { it.name == name } }
            ?.note(name, arguments)
            ?: "looking something up…"
}

private fun JsonObject.text(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun String.toToolIds(): Set<String> = split(',', ' ', '\n', '\t')
    .map { it.trim().lowercase() }
    .filter { it.isNotBlank() }
    .toSet()

private fun String.toGreetings(): List<String> = lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .toList()
