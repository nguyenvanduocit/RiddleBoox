package com.riddleboox.app.tools

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * A rule between two memories in the all-memories export — visually distinct
 * from the day-and-content pairing inside a single entry, the same role
 * [com.riddleboox.app.history.CONVERSATION_SEPARATOR] plays for conversations.
 *
 * Kept private to this file rather than shared with `history`'s separator:
 * the two packages export unrelated things, and a memory export changing
 * shape should never risk moving a conversation export's rule too.
 */
private val MEMORY_SEPARATOR = "=".repeat(40)

/** Same day-and-time shape [com.riddleboox.app.MemoriesActivity] labels each entry with. */
private val EXPORT_DAY_AND_TIME = SimpleDateFormat("d/M/yyyy · HH:mm", Locale.getDefault())

/**
 * Every memory as a single plain-text file: each one headed by the day it
 * was learned, in whatever order [this] arrives in.
 *
 * Order is not this function's job, the same rule [List<StoredConversation>.toPlainText]
 * in `history/ConversationText.kt` follows — [readMemories] callers decide the
 * order (typically newest first, via `sortedByDescending { it.ms }`), and
 * re-sorting here would be a second, easy-to-drift copy of that rule.
 *
 * An empty list is empty text, not a file with a lone separator in it —
 * there is nothing to export, so nothing is written.
 */
fun List<MemoryEntry>.toPlainText(): String =
    joinToString(separator = "\n\n$MEMORY_SEPARATOR\n\n") { entry ->
        EXPORT_DAY_AND_TIME.format(Date(entry.ms)) + "\n\n" + entry.content
    }

/**
 * The note the memorize label sends up with the conversation — see
 * [com.riddleboox.app.riddle.RiddleStateMachine.memorize] for the flow it
 * starts and [com.riddleboox.app.reply.Conversation.memorize] for how it is
 * carried.
 *
 * Every kept memory rides along in full, ids included, rather than being left
 * for `recall_memories`: "look it up before deciding" is exactly the step
 * agentic tool-calling skips most often (the lesson [recentMemoriesText]
 * already encodes), and judging what is outdated takes all of the list, not
 * the five newest. Each line carries the same `#id · day — fact` shape
 * `recall_memories` prints, so `forget_memory` can be called straight off it.
 */
fun memorizeInstruction(entries: List<MemoryEntry>, zone: ZoneId = ZoneId.systemDefault()): String {
    val held = if (entries.isEmpty()) {
        "Nothing is held yet."
    } else {
        entries.joinToString("\n") { entry ->
            val day = Instant.ofEpochMilli(entry.ms).atZone(zone).format(DateTimeFormatter.ISO_LOCAL_DATE)
            "#${entry.id} · $day — ${entry.content}"
        }
    }
    return """
        The writer has asked you to put your kept memories in order. This note is
        the diary's own bookkeeping, not a line written on the page.

        Below is everything you currently hold. Weigh it against this conversation:
        - Call `remember` for each durable fact this conversation revealed that is
          not yet held — who the writer is, what matters to them, a standing
          preference. Not what was merely said once and already answered.
        - Call `forget_memory`, by the id shown, for anything this conversation
          showed to be wrong or outdated. To correct an entry, forget it and
          remember the corrected fact.
        - Leave what still stands alone, and never keep the same fact twice.

        Held now, oldest first:
    """.trimIndent() + "\n" + held + "\n\n" + """
        When it is in order, write one short line in your own voice saying what
        you kept or corrected — or that there was nothing new worth keeping. No
        drawings, no separator, no transcript: there is no page this turn.
    """.trimIndent()
}
