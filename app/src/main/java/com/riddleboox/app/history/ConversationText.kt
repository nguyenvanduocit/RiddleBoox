package com.riddleboox.app.history

/**
 * One conversation as plain text, the shape it takes leaving the app: two
 * labelled lines per turn — what the writer wrote, then what the diary
 * answered — with a blank line between turns so a run of them still reads as
 * a conversation once it is outside [TranscriptActivity]'s two-hand layout.
 *
 * Used by the "chia sẻ" action, not by [ConversationStore] — the on-disk
 * record stays JSON; this is only ever built to hand to a share sheet.
 */
fun StoredConversation.toPlainText(): String = turns.joinToString(separator = "\n\n") { it.toPlainText() }

/**
 * A blank side of a turn is left out rather than printed as an empty
 * labelled line: "(mực nhoè)" is a placeholder for the reader looking at the
 * page in [TranscriptActivity], but a share target reading a plain-text file
 * has no page to explain it is missing from, so an empty line there would
 * just look like a formatting mistake.
 */
private fun StoredTurn.toPlainText(): String =
    listOfNotNull(
        transcript.takeIf { it.isNotBlank() }?.let { "Bạn: $it" },
        reply.takeIf { it.isNotBlank() }?.let { "Nhật ký: $it" },
    ).joinToString(separator = "\n")
