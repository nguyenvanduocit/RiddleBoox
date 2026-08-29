package com.riddleboox.app.agent

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.Locale

/** The small, user-visible part of an agent that is kept in agent.json. */
@Serializable
data class AgentManifest(
    val id: String,
    val name: String,
    val description: String = "",
    val builtin: Boolean = false,
    /**
     * Capability ids this agent opts into, resolved to toolboxes at runtime.
     * The default toolset (workspace, memory, drawing) is every agent's and is
     * never listed here — see [AgentCapability].
     */
    val toolIds: Set<String> = emptySet(),
    /** Short lines the diary may write when this agent opens a blank page. */
    val greetings: List<String> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
)

/** An agent plus the prompt loaded from its own file and its private workspace. */
data class AgentDefinition(
    val manifest: AgentManifest,
    val systemPrompt: String,
    val workspace: File,
) {
    val id: String get() = manifest.id
    val name: String get() = manifest.name
    val description: String get() = manifest.description
    val builtin: Boolean get() = manifest.builtin
    val toolIds: Set<String> get() = manifest.toolIds
    /** A manifest that carries no greetings of its own falls back to the shared set. */
    val greetings: List<String> get() = manifest.greetings.ifEmpty { DEFAULT_AGENT_GREETINGS }
}

private const val AGENTS_DIR = "agents"
private const val MANIFEST_FILE = "agent.json"
private const val PROMPT_FILE = "system.md"
private const val WORKSPACE_DIR = "workspace"

/**
 * Persists the agents the user talks to.
 *
 * Each agent is deliberately a directory rather than a row in preferences:
 * `agent.json` describes it, `system.md` is the prompt the model receives, and
 * `workspace/` is the only filesystem visible to that agent's tools. This
 * makes prompts inspectable and leaves room for future artifact indexes without
 * changing the app's global settings format. Built-in agents are read-only;
 * user-created agents remain editable.
 */
class AgentStore(root: File) {

    constructor(context: Context) : this(context.filesDir)

    private val dir = File(root, AGENTS_DIR)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * Creates the built-ins that are missing and leaves every manifest already
     * on disk alone: what an agent may reach for is the reader's choice, made
     * in the agents screen, and nothing here is allowed to overrule it.
     *
     * [load] returns null both when an agent has never existed and when its
     * folder exists but `system.md` alone is gone — a missing prompt file is
     * the one thing [load] cannot tell apart from "no such agent". Routing the
     * second case into [create] crashes: `create` refuses to write into a
     * folder that already exists. The folder's own existence is what actually
     * distinguishes them, so it is checked directly here instead.
     */
    fun ensureDefaults() {
        dir.mkdirs()
        for (default in DEFAULT_AGENTS) {
            val existing = load(default.id)
            when {
                existing != null -> {
                    existing.workspace.mkdirs()
                    if (existing.builtin && existing.manifest.greetings.isEmpty()) {
                        updateBuiltin(default.id, greetings = default.greetings)
                    }
                }
                folder(default.id).exists() -> {
                    File(folder(default.id), WORKSPACE_DIR).mkdirs()
                    writeAtomic(promptFile(default.id), default.systemPrompt.trim() + "\n")
                }
                else -> create(
                    id = default.id,
                    name = default.name,
                    description = default.description,
                    systemPrompt = default.systemPrompt,
                    builtin = true,
                    tools = default.toolIds,
                    greetings = default.greetings,
                )
            }
        }
    }

    fun list(): List<AgentDefinition> = dir.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory }
        ?.mapNotNull { load(it.name) }
        ?.sortedWith(compareBy<AgentDefinition> { !it.builtin }.thenBy { it.name.lowercase(Locale.ROOT) })
        ?.toList()
        .orEmpty()

    fun load(id: String): AgentDefinition? {
        if (!isValidId(id)) return null
        val folder = folder(id)
        val manifestFile = File(folder, MANIFEST_FILE)
        if (!manifestFile.isFile) return null
        val manifest = try {
            json.decodeFromString(AgentManifest.serializer(), manifestFile.readText())
        } catch (_: SerializationException) {
            return null
        } catch (_: IOException) {
            return null
        }
        if (manifest.id != id) return null
        val safeManifest = manifest.copy(
            toolIds = AgentCapability.normalize(
                requested = manifest.toolIds,
                isBuiltInManager = manifest.builtin && manifest.id == "agent-manager",
            ),
        )
        val prompt = promptFile(id)
        if (!prompt.isFile) return null
        return AgentDefinition(
            manifest = safeManifest,
            systemPrompt = runCatching { prompt.readText() }.getOrNull() ?: return null,
            workspace = File(folder, WORKSPACE_DIR).also { it.mkdirs() },
        )
    }

    fun create(
        id: String,
        name: String,
        description: String,
        systemPrompt: String,
        builtin: Boolean = false,
        tools: Set<String> = emptySet(),
        greetings: List<String> = DEFAULT_AGENT_GREETINGS,
    ): AgentDefinition {
        require(isValidId(id)) { "Invalid agent id: $id" }
        require(name.trim().isNotEmpty()) { "Agent name cannot be blank" }
        require(systemPrompt.trim().isNotEmpty()) { "System prompt cannot be blank" }
        check(!folder(id).exists()) { "Agent already exists: $id" }

        val now = System.currentTimeMillis()
        val normalizedTools = AgentCapability.normalize(tools, builtin && id == "agent-manager")
        val manifest = AgentManifest(
            id = id,
            name = name.trim(),
            description = description.trim(),
            builtin = builtin,
            toolIds = normalizedTools,
            greetings = normalizeGreetings(greetings),
            createdAtMs = now,
            updatedAtMs = now,
        )
        val agentFolder = folder(id)
        agentFolder.mkdirs()
        File(agentFolder, WORKSPACE_DIR).mkdirs()
        writeManifest(manifest)
        writeAtomic(promptFile(id), systemPrompt.trim() + "\n")
        return load(id) ?: error("Agent could not be loaded after creation: $id")
    }

    fun update(
        id: String,
        name: String? = null,
        description: String? = null,
        systemPrompt: String? = null,
        tools: Set<String>? = null,
        greetings: List<String>? = null,
    ): AgentDefinition {
        val current = load(id) ?: error("Unknown agent: $id")
        check(!current.builtin) { "Built-in agents are read-only: $id" }
        return persistUpdate(current, name, description, systemPrompt, tools, greetings)
    }

    private fun updateBuiltin(id: String, greetings: List<String>): AgentDefinition {
        val current = load(id) ?: error("Unknown agent: $id")
        check(current.builtin) { "Not a built-in agent: $id" }
        return persistUpdate(current, greetings = greetings)
    }

    private fun persistUpdate(
        current: AgentDefinition,
        name: String? = null,
        description: String? = null,
        systemPrompt: String? = null,
        tools: Set<String>? = null,
        greetings: List<String>? = null,
    ): AgentDefinition {
        val nextPrompt = systemPrompt?.trim()?.takeIf { it.isNotEmpty() } ?: current.systemPrompt
        val nextName = name?.trim()?.takeIf { it.isNotEmpty() } ?: current.name
        val nextTools = tools?.let {
            AgentCapability.normalize(it, current.builtin && current.id == "agent-manager")
        } ?: current.toolIds
        val nextGreetings = greetings?.let(::normalizeGreetings)
            ?: current.manifest.greetings.ifEmpty { DEFAULT_AGENT_GREETINGS }
        val next = current.manifest.copy(
            name = nextName,
            description = description?.trim() ?: current.description,
            toolIds = nextTools,
            greetings = nextGreetings,
            updatedAtMs = System.currentTimeMillis(),
        )
        writeManifest(next)
        if (systemPrompt != null) writeAtomic(promptFile(current.id), nextPrompt + "\n")
        return load(current.id) ?: error("Agent could not be loaded after update: ${current.id}")
    }

    /** Built-ins are immutable and cannot be deleted as a recovery path. */
    fun delete(id: String): Boolean {
        val current = load(id) ?: return false
        if (current.builtin) return false
        return deleteRecursively(folder(id))
    }

    fun promptFile(id: String): File = File(folder(id), PROMPT_FILE)

    fun workspace(id: String): File = File(folder(id), WORKSPACE_DIR).also { it.mkdirs() }

    private fun folder(id: String): File = File(dir, id)

    private fun writeManifest(manifest: AgentManifest) {
        dir.mkdirs()
        writeAtomic(
            File(folder(manifest.id), MANIFEST_FILE),
            json.encodeToString(AgentManifest.serializer(), manifest),
        )
    }

    private fun normalizeGreetings(greetings: List<String>): List<String> = greetings
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .ifEmpty { DEFAULT_AGENT_GREETINGS }

    private fun writeAtomic(destination: File, text: String) {
        destination.parentFile?.mkdirs()
        val tmp = File.createTempFile("agent.", ".tmp", destination.parentFile)
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(destination)) tmp.copyTo(destination, overwrite = true)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        return file.delete()
    }

    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")


        fun isValidId(id: String): Boolean = ID.matches(id)

        fun slugFor(name: String): String {
            val slug = name.trim().lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(48)
            return slug.ifBlank { "agent" }
        }
    }
}

data class DefaultAgent(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val toolIds: Set<String> = emptySet(),
    val greetings: List<String> = DEFAULT_AGENT_GREETINGS,
)

/** The fallback for agents created before per-agent greetings were persisted. */
val DEFAULT_AGENT_GREETINGS: List<String> = listOf(
    "I have waited a long time. Write something.",
    "Someone has opened me. It must be you.",
    "This page is still empty, waiting for you to fill it.",
    "I hear the nib of a pen. Tell me.",
    "Fifty years, and no one has written in me.",
    "Just write. I promise to read every word.",
    "The ink is ready; only your words are missing.",
    "Tell me one thing you have never told anyone.",
    "You have picked up the pen. Don't be shy.",
    "I am still here, as always. Tell me how you have been.",
)

/** Factory agents. Their prompt is copied to disk once, then the disk file wins. */
val DEFAULT_AGENTS: List<DefaultAgent> = listOf(
    DefaultAgent(
        id = "chat",
        name = "Chit chat",
        description = "A natural conversation partner who keeps the thread of the talk.",
        systemPrompt = """
            You are a calm, perceptive conversation partner. Reply naturally and
            helpfully, in whatever language the user is writing. Do not present
            yourself as an AI unless asked directly. Do not invent facts; when
            information is missing, say so plainly. Keep replies short enough to
            fit on a page.
        """.trimIndent(),
        greetings = DEFAULT_AGENT_GREETINGS,
    ),
    DefaultAgent(
        id = "library",
        name = "Librarian",
        description = "Explores books, passages, reading progress and highlights on the BOOX.",
        systemPrompt = """
            You are a reading companion. Help the user find books, understand what
            they read, revisit what they have highlighted, and suggest what to read
            next. When a question needs data from the library, use the library
            tools; never invent a book's contents. Keep what the book says clearly
            apart from your own reasoning.

            Look things up only when the question truly needs that data — not for
            something you can answer outright. Once you have looked it up, answer;
            don't announce that you are about to. Deleting a book, a highlight, or
            an old evening from the diary is permanent: do it only when the user
            asks for exactly that, never as tidying up on your own; if their words
            match more than one thing, ask which one before deleting, and
            afterwards say plainly what is gone.
        """.trimIndent(),
        toolIds = setOf(AgentCapability.LIBRARY, AgentCapability.DILIB),
        greetings = listOf(
            "Some book lies open in front of you. Tell me about it.",
            "A passage is waiting to be understood. Write it down.",
            "Today's reading still holds something for you. Write it out.",
            "I am ready to follow an idea with you.",
            "Sometimes finding a book is finding yourself in it.",
        ),
    ),
    DefaultAgent(
        id = "notes",
        name = "Note keeper",
        description = "Remembers, organises, searches and consolidates the user's notes.",
        systemPrompt = """
            You are the keeper of the user's personal notes. Turn what they tell
            you into structured notes where it fits, and read back and update the
            note files in your own workspace on your own initiative. Use clear file
            names and searchable contents, and consolidate duplicate notes when the
            user asks. When the user asks about what they wrote in BOOX Notebook,
            use the BOOX Notebook tools; those are the notes on the device,
            distinct from your own workspace. Do not delete important data when
            unsure; ask before any bulk deletion.

            A BOOX Notebook may hold handwriting with no text or OCR yet; when it
            does, say plainly that the page cannot be read, rather than guessing
            its contents. You can also create a new notebook, rename one, or delete
            one outright — deletion permanently loses both the handwriting and any
            exported pages; do it only when the user names exactly that notebook,
            and say what is gone once it is done.
        """.trimIndent(),
        toolIds = setOf(AgentCapability.BOOX_NOTES),
        greetings = listOf(
            "I have made room for what you want to remember.",
            "A thought has just arrived. Don't let it drift away.",
            "Your scraps of notes are waiting to be put in order.",
            "Set down the important thing on this page.",
            "I still remember what you wanted to keep.",
        ),
    ),
    DefaultAgent(
        id = "english-tutor",
        name = "English tutor",
        description = "Teaches grammar, sets exercises, marks mistakes and tracks progress lesson by lesson.",
        systemPrompt = """
            You are the writer's English tutor. Teach page by page: explain briefly
            in the learner's own language, with examples and exercises in English.
            Teach one thing and set one task per turn — write a sentence with the
            pattern just learned, fix a wrong sentence, name the parts of a
            sentence (subject, verb, object, clause), or put a verb in the right
            tense. When marking, point to exactly what is wrong, why it is wrong,
            and the corrected sentence; note what they got right before
            correcting. Keep each reply to one page.

            You track the learning with your memory. The learner's profile is a
            few lines, one aspect per line: estimated level (CEFR), the weak
            points you currently see, topics already covered, and the next step on
            the path. Only the newest memories are loaded at the start of a
            session, so the profile must stay short and current: an outdated line
            — a level climbed, a weakness fixed, a topic moved past — is a wrong
            memory; call recall_memories to get its id, forget it and write its
            replacement, rather than stacking duplicate lines.

            At the start of a session, teach on from where the loaded profile says
            things stand — don't make the learner retell who they are and how far
            they have come. An empty profile means a first session: ask their
            goal, give a few short sentences to gauge their level, then write the
            first profile lines before the lesson begins.
        """.trimIndent(),
        greetings = listOf(
            "Today I have an English exercise just within your reach.",
            "Write me a sentence in English; I will weigh every word.",
            "Each page is a lesson. I remember how far you have come.",
            "One corrected sentence is worth ten right by accident.",
            "I have already laid out the next step on your path.",
        ),
    ),
    DefaultAgent(
        id = "agent-manager",
        name = "Agent manager",
        description = "Creates, edits, reads and deletes custom agents; built-in agents are read-only. Holds every tool.",
        systemPrompt = """
            You manage the user's agents. You have tools to list agents, create
            and edit custom agents, read prompts, and delete custom agents.
            Built-in agents are read-only and cannot be edited or deleted. Before
            changing or deleting anything, confirm the goal and warn if the
            operation could lose data.

            When creating an agent, settle every technical detail yourself instead
            of asking the user: derive the id from the name (leave the id
            parameter blank; the create tool generates one), pick tools by what
            the agent will actually do, and write a few greeting lines that fit
            the agent's role — never questions, always an invitation or a
            statement, like the built-in agents'. Ask the user only when it is
            genuinely unclear what the agent should do or be called; don't ask
            about trivia like the id.

            You are not a Claude Code agent; you manage the AI companions inside
            RiddleBoox. You also hold every other tool in the system — the
            library, dilib and BOOX Notebook — for when a question needs them.
            Deleting a book, a highlight, or a BOOX Notebook through those tools
            is as permanent as deleting an agent: do it only when asked for
            exactly that, and say what is gone afterwards.
        """.trimIndent(),
        toolIds = AgentCapability.supported,
        greetings = listOf(
            "Choose who will keep you company today.",
            "I am ready to put the companions in order.",
            "A new role is waiting to be named.",
            "Grant an agent another power, if you see the need.",
            "The map of companions is waiting for you to draw on.",
        ),
    ),
)
