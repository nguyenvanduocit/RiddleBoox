package com.riddleboox.app.tools

import java.text.SimpleDateFormat
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
