package com.riddleboox.app.history

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One conversation as plain text, the shape it takes leaving the app: two
 * labelled lines per turn — what the writer wrote, then what the diary
 * answered — with a blank line between turns so a run of them still reads as
 * a conversation once it is outside [TranscriptActivity]'s two-hand layout.
 *
 * Used by the "share" action, not by [ConversationStore] — the on-disk
 * record stays JSON; this is only ever built to hand to a share sheet.
 */
fun StoredConversation.toPlainText(): String = turns.joinToString(separator = "\n\n") { it.toPlainText() }

/**
 * A rule between two conversations in the all-history export — visually
 * distinct from the blank-line turn separator inside [StoredConversation.toPlainText]
 * so a reader can tell "next turn" from "next evening" at a glance.
 */
internal val CONVERSATION_SEPARATOR = "=".repeat(40)

/** Same day-and-time shape [HistoryActivity] and [TranscriptActivity] head their pages with. */
private val EXPORT_DAY_AND_TIME = SimpleDateFormat("d/M/yyyy · HH:mm", Locale.getDefault())

/**
 * The whole history for one agent as a single plain-text file: every
 * conversation headed by the day it was written, in whatever order [this]
 * arrives in.
 *
 * Order is not this function's job — [ConversationStore.list] already hands
 * back newest-first, and re-sorting here would be a second, easy-to-drift copy
 * of that rule. The heading uses [StoredConversation.updatedAtMs], never
 * [StoredConversation.startedAtMs], for the same reason [HistoryActivity.writtenOn]
 * does: the order this file's headings will actually be read in is
 * newest-*answered*-first, and a heading dated by when a resumed conversation
 * *began* would print out of step with it.
 *
 * An empty list is empty text, not a file with a lone separator in it — there
 * is nothing to export, so nothing is written.
 */
fun List<StoredConversation>.toPlainText(): String =
    joinToString(separator = "\n\n$CONVERSATION_SEPARATOR\n\n") { conversation ->
        EXPORT_DAY_AND_TIME.format(Date(conversation.updatedAtMs)) + "\n\n" + conversation.toPlainText()
    }

/**
 * A blank side of a turn is left out rather than printed as an empty
 * labelled line: "(smudged ink)" is a placeholder for the reader looking at the
 * page in [TranscriptActivity], but a share target reading a plain-text file
 * has no page to explain it is missing from, so an empty line there would
 * just look like a formatting mistake.
 */
private fun StoredTurn.toPlainText(): String =
    listOfNotNull(
        transcript.takeIf { it.isNotBlank() }?.let { "You: $it" },
        reply.takeIf { it.isNotBlank() }?.let { "Diary: $it" },
    ).joinToString(separator = "\n")
