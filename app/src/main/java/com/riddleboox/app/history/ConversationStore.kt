package com.riddleboox.app.history

import android.content.Context
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** The folder under `filesDir` holding one JSON file per conversation. */
private const val CONVERSATIONS_DIR = "conversations"

private const val FILE_SUFFIX = ".json"

/**
 * Every conversation the diary has had, one JSON file each on internal
 * storage.
 *
 * A file per conversation rather than one big notebook, because the two things
 * asked of this store pull in opposite directions: a turn is appended after
 * every single reply, and the whole history is listed whenever the writer looks
 * for an old evening. One file keeps an append cheap — it rewrites the current
 * conversation, not the archive — and the listing affordable, because a
 * conversation is only its words (see [StoredTurn]) and a year of them is
 * measured in kilobytes.
 *
 * Writes are atomic: a temp file in the same directory is renamed over the real
 * one, so a kill mid-write leaves the previous good conversation untouched
 * rather than a truncated file. Ported from
 * references/Inka/app/src/main/java/co/podzim/inka/data/NotebookStore.kt:47-62.
 *
 * The primary constructor takes the directory so JVM unit tests can point it at
 * a temp folder; app code uses `ConversationStore(context)`.
 */
class ConversationStore(filesDir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val dir = File(filesDir, CONVERSATIONS_DIR)

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun fileFor(id: String): File = File(dir, id + FILE_SUFFIX)

    /**
     * Every conversation on disk, the most recently answered first — the order
     * a history is read in.
     *
     * A file that will not parse is skipped rather than thrown over: one
     * damaged evening must not make the other fifty unreachable.
     */
    fun list(): List<StoredConversation> {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) } ?: return emptyList()
        return files.mapNotNull { read(it) }.sortedByDescending { it.updatedAtMs }
    }

    /** One conversation by [id], or null when it was never written or is damaged. */
    fun load(id: String): StoredConversation? = read(fileFor(id))

    /**
     * Adds [turn] to the conversation [id], starting the record if this is its
     * first turn — which is why [startedAtMs] is needed: a conversation exists
     * in the app from the moment it is opened, but reaches the disk only once
     * something was actually said in it, so empty ones never litter the list.
     *
     * A file that exists but will not parse stops the append rather than being
     * written over: [read] answers null for "never written" and for "damaged"
     * alike, and treating the second as the first would silently replace a
     * whole evening with the one turn being appended. [list] still skips it, so
     * one damaged file costs its own conversation and nothing else.
     *
     * IO failures propagate so a caller that cares can react.
     */
    fun appendTurn(id: String, startedAtMs: Long, turn: StoredTurn, agentId: String = "chat") {
        val file = fileFor(id)
        val current = if (file.exists()) {
            read(file) ?: throw IOException("conversation $id is damaged; refusing to write over it")
        } else {
            StoredConversation(id = id, startedAtMs = startedAtMs, agentId = agentId)
        }
        save(current.copy(turns = current.turns + turn))
    }

    fun save(conversation: StoredConversation) {
        dir.mkdirs()
        val destination = fileFor(conversation.id)
        // A fixed prefix, not the id: `createTempFile` rejects a prefix shorter
        // than three characters, and the name is thrown away by the rename
        // below anyway — it only has to be unique, which is what it guarantees.
        val tmp = File.createTempFile("conversation.", FILE_SUFFIX + ".tmp", dir)
        try {
            tmp.writeText(json.encodeToString(StoredConversation.serializer(), conversation))
            if (!tmp.renameTo(destination)) {
                tmp.copyTo(destination, overwrite = true)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Rewrites the reply of the turn just recorded, or drops that turn when
     * [reply] is blank.
     *
     * The one case where a turn changes after the fact, and it is not an edit
     * of history but a correction of it: a streamed reply is recorded whole the
     * moment the stream closes, while the pen is still drawing it, so a turn
     * stopped by hand a few seconds later was recorded as saying more than the
     * writer was ever shown. What they saw is what the conversation has to
     * carry into the next one.
     *
     * Silent when the conversation is gone or damaged — the caller is finishing
     * a turn on a page, not in a position to do anything about either.
     */
    fun replaceLastReply(id: String, reply: String) {
        val current = read(fileFor(id)) ?: return
        val last = current.turns.lastOrNull() ?: return
        val kept = if (reply.isBlank()) {
            current.turns.dropLast(1)
        } else {
            current.turns.dropLast(1) + last.copy(reply = reply)
        }
        if (kept.isEmpty()) delete(id) else save(current.copy(turns = kept))
    }

    /** Burn one conversation: the file goes, and with it the diary's record of it. */
    fun delete(id: String) {
        fileFor(id).delete()
    }

    private fun read(file: File): StoredConversation? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString(StoredConversation.serializer(), file.readText())
        } catch (e: SerializationException) {
            null
        } catch (e: IOException) {
            null
        }
    }
}
