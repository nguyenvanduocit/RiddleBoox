package com.riddleboox.app.history

import com.riddleboox.app.reply.DiaryTurn
import kotlinx.serialization.Serializable

/**
 * One finished turn as the diary will remember it: what the writer wrote, read
 * back off the page by the model, and what the diary answered.
 *
 * It is the transcript rather than the ink that is kept, because the transcript
 * is what a resumed conversation is rebuilt from — the model's own memory of a
 * page is its words (see [com.riddleboox.app.reply.Conversation]). The strokes
 * themselves are a rendering of those words that nothing reads back; keeping
 * them would make every listing of the history parse megabytes of point arrays
 * to show a line of text.
 */
@Serializable
data class StoredTurn(
    val timestampMs: Long,
    val transcript: String,
    val reply: String,
)

/**
 * One conversation with the diary, start to finish, as it sits on disk.
 *
 * Deliberately separate from [com.riddleboox.app.reply.Conversation]: that one
 * is the live memory being spoken to, this one is the record of it. [toTurns]
 * is the bridge back.
 */
@Serializable
data class StoredConversation(
    val id: String,
    val startedAtMs: Long,
    val turns: List<StoredTurn> = emptyList(),
    /** Which user-defined RiddleBoox agent owns this conversation. */
    val agentId: String = "chat",
) {

    /** When the diary last answered in this conversation. */
    val updatedAtMs: Long get() = turns.lastOrNull()?.timestampMs ?: startedAtMs

    /**
     * The first line the writer wrote, which is how a conversation is
     * recognised in a list of them — the same way a letter is known by its
     * opening rather than by a name nobody gave it.
     *
     * Blank when the page was never legible; the list falls back to the date.
     */
    val title: String
        get() = turns.asSequence()
            .map { it.transcript.lineSequence().firstOrNull { line -> line.isNotBlank() }.orEmpty().trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

    /** This conversation as memory the diary can be handed back. */
    fun toTurns(): List<DiaryTurn> = turns.map { DiaryTurn(transcript = it.transcript, reply = it.reply) }
}
