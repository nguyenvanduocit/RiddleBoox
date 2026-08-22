package com.riddleboox.app.agent

/**
 * An agent's definition as plain text, the shape it takes leaving the app
 * through the "xuất" action in [com.riddleboox.app.AgentsActivity] — same
 * export-as-`.txt`-then-share pattern as [com.riddleboox.app.history.TranscriptActivity.share]
 * and [com.riddleboox.app.history.HistoryActivity.exportAll].
 *
 * Only the definition — name, description, tools, greetings, system prompt —
 * never [AgentDefinition.workspace]: that directory is the agent's own
 * filesystem (memories, notes, artifacts a tool wrote), not configuration,
 * and could be arbitrarily large or contain content the writer did not mean
 * to hand off when sharing "how this agent is set up".
 *
 * A blank [AgentDefinition.description] is left out rather than printed as
 * an empty "Mô tả:" line, the same reasoning [com.riddleboox.app.history.StoredTurn.toPlainText]
 * uses for a blank transcript or reply: an empty labelled line reads as a
 * formatting mistake to whoever opens the shared file, not as "there was no
 * description".
 */
fun AgentDefinition.toPlainText(): String {
    val header = listOfNotNull(
        "Tên: $name",
        description.takeIf { it.isNotBlank() }?.let { "Mô tả: $it" },
        "Tools: ${toolIds.joinToString(", ")}",
    ).joinToString(separator = "\n")

    val greetingLines = greetings.joinToString(separator = "\n") { "- $it" }

    return listOf(
        header,
        "Greetings:\n$greetingLines",
        "System prompt:\n$systemPrompt",
    ).joinToString(separator = "\n\n")
}
