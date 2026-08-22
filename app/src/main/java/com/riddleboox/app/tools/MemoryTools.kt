package com.riddleboox.app.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.riddleboox.app.reply.Toolbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val REMEMBER = "remember"
private const val RECALL_MEMORIES = "recall_memories"
private const val FORGET_MEMORY = "forget_memory"

private const val MEMORIES_FILE = "memories.jsonl"
private const val MEMORIES_LISTED = 20
private const val ID_SHOWN = 8
private const val RECENT_MEMORIES_SHOWN = 5

/**
 * One durable fact, apart from anything said on a page.
 *
 * [id] is short because it is only ever read back to the model to name the
 * entry again in [FORGET_MEMORY] — a full UUID beside every line would cost
 * more than the fact itself. [ms] and [conversation] are never typed by the
 * model: a language model has no clock and no reason to know which evening it
 * is in, so a guessed timestamp would be worse than none at all.
 *
 * Public rather than private to [MemoryTools]: the agents screen reads these
 * back too, to list what an agent remembers — see
 * [com.riddleboox.app.MemoriesActivity].
 */
@Serializable
data class MemoryEntry(
    val id: String,
    val ms: Long,
    val conversation: String,
    val content: String,
)

/**
 * Every memory kept in [workspace]'s [MEMORIES_FILE], in the order it was
 * written.
 *
 * The one place this file is parsed, so [MemoryTools] and anything that only
 * reads it — the agents screen's memory list — see exactly the same entries.
 * A line kotlinx.serialization cannot decode is dropped rather than failing
 * the whole read: an agent's memory should stay readable even if one line was
 * ever damaged.
 */
fun readMemories(workspace: File): List<MemoryEntry> {
    val file = File(workspace, MEMORIES_FILE)
    if (!file.isFile) return emptyList()
    val json = Json { ignoreUnknownKeys = true }
    return file.readLines()
        .filter { it.isNotBlank() }
        .mapNotNull { line -> runCatching { json.decodeFromString(MemoryEntry.serializer(), line) }.getOrNull() }
}

/**
 * Rewrites [workspace]'s [MEMORIES_FILE] to hold exactly [entries], atomically —
 * a temp file written alongside it and renamed over it, so a crash mid-write
 * never leaves a half-written file behind.
 *
 * The same write [MemoryTools] itself uses for `forget_memory`/`remember`,
 * pulled out here so anything that only reads the file — the agents screen's
 * memory list — can remove an entry too, without reaching into
 * [MemoryTools]'s private id-prefix-matching rules it has no use for: the UI
 * already knows the exact [MemoryEntry.id] of what it is showing.
 */
fun writeMemories(workspace: File, entries: List<MemoryEntry>) {
    val file = File(workspace, MEMORIES_FILE)
    val json = Json { ignoreUnknownKeys = true }
    val text = entries.joinToString("") { json.encodeToString(MemoryEntry.serializer(), it) + "\n" }
    val tmp = File.createTempFile("memories.", ".tmp", file.parentFile)
    try {
        tmp.writeText(text)
        if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}

/**
 * The most recent memories in [workspace], newest first, one bullet per
 * line — empty when there are none.
 *
 * Meant to be folded straight into a system prompt rather than left for the
 * model to go fetch with `recall_memories`: "look this up before answering"
 * is exactly the step agentic tool-calling skips most often, and entries this
 * short cost nothing to just already be there. See
 * [com.riddleboox.app.reply.Conversation]'s `recentMemories` parameter.
 */
fun recentMemoriesText(workspace: File, limit: Int = RECENT_MEMORIES_SHOWN): String =
    readMemories(workspace)
        .sortedByDescending { it.ms }
        .take(limit)
        .joinToString("\n") { "- ${it.content}" }

/**
 * A short, curated memory the diary keeps on purpose, apart from the raw
 * evenings [DiaryMemory] already makes searchable.
 *
 * The difference is what each is for. [DiaryMemory] is every word actually
 * exchanged, found again by matching text — nothing is lost, but nothing was
 * ever picked out either. This is the opposite: nothing is kept unless the
 * model chose to keep it, one line at a time, in [MEMORIES_FILE] inside the
 * agent's own workspace. Deliberately plain JSON Lines rather than a database —
 * a memory is added or removed by rewriting one line, and the whole file is
 * small enough to read back in full on every recall.
 *
 * [conversationId] names the evening a fact was learned in, for the model's
 * own reference; it is fixed once per toolbox, at the same place a fresh
 * evening is decided to have started.
 */
class MemoryTools(
    workspace: File,
    private val conversationId: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : Toolbox {

    private val workspace = workspace.also { it.mkdirs() }
    private val file = File(this.workspace, MEMORIES_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    override val tools: List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = REMEMBER,
            description = "Keep one durable fact about the writer past this evening — who they are, what " +
                "matters to them, a standing preference. Not for what was merely said once and already " +
                "answered; that already lives on the page. One fact per call.",
            requiredParameters = listOf(
                ToolParameterDescriptor("content", "The fact to remember, in one or two sentences.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
        ToolDescriptor(
            name = RECALL_MEMORIES,
            description = "What has been remembered so far. Call this at the start of an evening, or " +
                "whenever something about the writer is unclear, before asking them again or guessing.",
            requiredParameters = emptyList(),
            optionalParameters = listOf(
                ToolParameterDescriptor("query", "Words to search for; empty returns the most recent.", ToolParameterType.String),
                ToolParameterDescriptor("limit", "How many. Default $MEMORIES_LISTED.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = FORGET_MEMORY,
            description = "Remove one remembered fact for good, named by the id $RECALL_MEMORIES prints " +
                "beside it. Only when the writer says it is wrong or asks you to forget it.",
            requiredParameters = listOf(
                ToolParameterDescriptor("id", "The id shown beside the memory, as printed or in full.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
    )

    override suspend fun call(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                REMEMBER -> remember(arguments.text("content"))
                RECALL_MEMORIES -> recall(arguments.text("query"), arguments.count("limit", MEMORIES_LISTED))
                FORGET_MEMORY -> forget(arguments.text("id"))
                else -> "There is nothing called $name in memory."
            }
        }.getOrElse { error -> "Memory operation failed: ${error.message ?: error.javaClass.simpleName}" }
    }

    override fun note(name: String, arguments: JsonObject): String = when (name) {
        REMEMBER -> "đang ghi nhớ…"
        RECALL_MEMORIES -> "đang nhớ lại…"
        FORGET_MEMORY -> "đang quên đi…"
        else -> "đang xử lý trí nhớ…"
    }

    private fun remember(content: String): String {
        val fact = content.trim()
        if (fact.isEmpty()) return "Nothing was given to remember."
        val entry = MemoryEntry(
            id = UUID.randomUUID().toString().take(ID_SHOWN),
            ms = System.currentTimeMillis(),
            conversation = conversationId,
            content = fact,
        )
        file.appendText(json.encodeToString(MemoryEntry.serializer(), entry) + "\n")
        return "Remembered (#${entry.id})."
    }

    private fun recall(query: String, limit: Int): String {
        val entries = readEntries()
        if (entries.isEmpty()) return "Nothing has been remembered yet."
        val wanted = query.trim()
        val matches = if (wanted.isEmpty()) entries else entries.filter { it.content.contains(wanted, ignoreCase = true) }
        if (matches.isEmpty()) return "Nothing remembered mentions \"$wanted\"."
        val shown = matches.takeLast(limit).asReversed()
        return "${shown.size} of ${matches.size} remembered things, newest first:\n" +
            shown.joinToString("\n") { memoryLine(it) }
    }

    /**
     * A leading piece of an id is taken as the id when exactly one memory
     * answers to it, and refused when several do — the same rule
     * [com.riddleboox.app.tools.DiaryTools] uses to burn an evening, kept here
     * because a wrong guess here is just as unrecoverable.
     */
    private fun forget(id: String): String {
        val wanted = id.removePrefix("#").trim()
        if (wanted.isEmpty()) return "No memory was named. $RECALL_MEMORIES prints an id beside each one."
        val entries = readEntries()
        val named = entries.firstOrNull { it.id.equals(wanted, ignoreCase = true) }
            ?: entries.filter { it.id.startsWith(wanted, ignoreCase = true) }.let { matches ->
                when (matches.size) {
                    0 -> return "No remembered thing answers to \"$id\". $RECALL_MEMORIES prints the ids."
                    1 -> matches.single()
                    else -> return "\"$id\" names ${matches.size} remembered things; give more of the id."
                }
            }
        writeEntries(entries.filterNot { it.id == named.id })
        return "Forgotten: \"${named.content.take(200)}\"."
    }

    private fun readEntries(): List<MemoryEntry> = readMemories(workspace)

    private fun writeEntries(entries: List<MemoryEntry>) = writeMemories(workspace, entries)

    private fun memoryLine(entry: MemoryEntry): String = "#${entry.id} · ${day(entry.ms)} — ${entry.content}"

    private fun day(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ISO_LOCAL_DATE)
}

private fun JsonObject.text(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun JsonObject.count(name: String, fallback: Int): Int =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()?.coerceIn(1, 100) ?: fallback
