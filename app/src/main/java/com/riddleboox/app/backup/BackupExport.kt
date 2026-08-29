package com.riddleboox.app.backup

import com.riddleboox.app.agent.AgentDefinition
import com.riddleboox.app.agent.toPlainText
import com.riddleboox.app.history.StoredConversation
import com.riddleboox.app.history.toPlainText
import com.riddleboox.app.tools.MemoryEntry
import com.riddleboox.app.tools.toPlainText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val EXPORT_DAY_AND_TIME = SimpleDateFormat("d/M/yyyy · HH:mm", Locale.getDefault())

/**
 * A rule between two agents' blocks within one section — the same role
 * `CONVERSATION_SEPARATOR`/`MEMORY_SEPARATOR` play one level down, inside a
 * single agent's own export.
 */
private const val AGENT_RULE = "----------------------------------------"

/**
 * Everything the diary holds, as one plain-text file: every agent's own
 * definition, every conversation, every memory — grouped by which agent they
 * belong to, since a reader trying to make sense of a backup file needs that
 * more than a strict chronological interleave would give them.
 *
 * The one export nothing else on the device already is:
 * [com.riddleboox.app.history.HistoryActivity.exportAll] and
 * [com.riddleboox.app.MemoriesActivity]'s own export both stop at the single
 * agent they were opened for, and [com.riddleboox.app.AgentsActivity]'s
 * "export" hands over one definition at a time. On a tablet with no cloud sync
 * of its own, this is the only way off the device that isn't "copy every
 * agent by hand".
 *
 * Pure text in, text out — [agents], [conversations] and [memories] are
 * exactly what the caller already read from
 * [com.riddleboox.app.agent.AgentStore], [com.riddleboox.app.history.ConversationStore]
 * and [com.riddleboox.app.tools.readMemories]; this function only arranges
 * them. [exportedAtMs] arrives as a parameter rather than being read here
 * with `System.currentTimeMillis()`, so the whole thing stays a plain
 * function of its inputs — no clock to fake in a test.
 */
fun wholeDiaryBackup(
    agents: List<AgentDefinition>,
    conversations: List<StoredConversation>,
    memories: Map<String, List<MemoryEntry>>,
    exportedAtMs: Long,
): String {
    val header = "RIDDLEBOOX BACKUP — ${EXPORT_DAY_AND_TIME.format(Date(exportedAtMs))}"
    val agentsBlock = "AGENTS (${agents.size})\n\n" +
        agents.joinToString(separator = "\n\n$AGENT_RULE\n\n") { it.toPlainText() }
    val conversationsBlock = perAgentBlock("CONVERSATIONS", agents) { agent ->
        conversations.filter { it.agentId == agent.id }.toPlainText()
    }
    val memoriesBlock = perAgentBlock("MEMORIES", agents) { agent ->
        memories[agent.id].orEmpty().toPlainText()
    }
    return listOf(header, agentsBlock, conversationsBlock, memoriesBlock).joinToString(separator = "\n\n\n")
}

/**
 * One section of the backup (conversations, memories), one block per agent that
 * actually has something in it — an agent nobody has written to yet does not
 * get an empty heading, the same reasoning [wholeDiaryBackup]'s own
 * `toPlainText` callees use for a blank list.
 */
private fun perAgentBlock(title: String, agents: List<AgentDefinition>, bodyFor: (AgentDefinition) -> String): String {
    val perAgent = agents.mapNotNull { agent ->
        bodyFor(agent).takeIf { it.isNotBlank() }?.let { "${agent.name}\n\n$it" }
    }
    if (perAgent.isEmpty()) return "$title\n\n(nothing yet)"
    return "$title\n\n" + perAgent.joinToString(separator = "\n\n$AGENT_RULE\n\n")
}
