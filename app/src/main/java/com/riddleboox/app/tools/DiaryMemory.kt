package com.riddleboox.app.tools

import com.riddleboox.app.history.ConversationStore
import com.riddleboox.app.library.fold

/**
 * One exchange from an evening already past.
 *
 * [evening] is the conversation it came out of, carried so an exchange found
 * by [DiaryMemory.recall] can be named again — a recall is how the writer says
 * which evening they mean, and by the time they say "burn that one" the id has
 * to already be in hand.
 */
data class Recalled(val evening: String, val whenMs: Long, val wrote: String, val answered: String)

/** A whole evening on record, as it is named when one is to be picked out. */
data class Evening(val id: String, val whenMs: Long, val title: String, val turns: Int)

/**
 * The evenings the diary has already had, searchable.
 *
 * [com.riddleboox.app.reply.Conversation] carries only the last two dozen
 * messages into a request, which is an evening; everything older is on disk and
 * out of reach until something goes looking for it. This is that reach.
 */
interface DiaryMemory {

    /** Exchanges where [query] was written or answered, newest first. */
    fun recall(query: String, limit: Int): List<Recalled>

    /**
     * Whole evenings, newest first — every one of them when [query] is empty,
     * otherwise the ones something in them was written or answered in.
     */
    fun evenings(query: String, limit: Int): List<Evening>

    /**
     * Burns one evening whole: everything written and answered in it goes off
     * the disk, and nothing brings it back.
     *
     * [id] is a conversation id as [Evening.id] and [Recalled.evening] give it,
     * or a leading piece of one long enough to name a single evening.
     */
    fun forget(id: String): Boolean
}

/**
 * [DiaryMemory] over the conversations on disk.
 *
 * A plain scan of every file, because the whole archive is words — a year of
 * evenings is measured in kilobytes ([ConversationStore]) — and an index over
 * it would be more machinery than the thing it indexes.
 *
 * The conversation being had right now is in the store too, its answered turns
 * appended as they happen, so a recall can hand back a turn the model already
 * has in front of it. Harmless, and cheaper than threading the running
 * conversation's identity down here to exclude it.
 */
class StoredMemory(private val store: ConversationStore) : DiaryMemory {

    override fun recall(query: String, limit: Int): List<Recalled> {
        val wanted = fold(query.trim())
        if (wanted.isEmpty()) return emptyList()
        return store.list()
            .asSequence()
            .flatMap { conversation -> conversation.turns.asSequence().map { conversation.id to it } }
            .filter { (_, turn) -> fold(turn.transcript).contains(wanted) || fold(turn.reply).contains(wanted) }
            .sortedByDescending { (_, turn) -> turn.timestampMs }
            .take(limit)
            .map { (id, turn) ->
                Recalled(evening = id, whenMs = turn.timestampMs, wrote = turn.transcript, answered = turn.reply)
            }
            .toList()
    }

    override fun evenings(query: String, limit: Int): List<Evening> {
        val wanted = fold(query.trim())
        return store.list()
            .asSequence()
            .filter { conversation ->
                wanted.isEmpty() || conversation.turns.any {
                    fold(it.transcript).contains(wanted) || fold(it.reply).contains(wanted)
                }
            }
            .take(limit)
            .map {
                Evening(id = it.id, whenMs = it.updatedAtMs, title = it.title, turns = it.turns.size)
            }
            .toList()
    }

    /**
     * A leading piece of an id is taken as the id when exactly one evening
     * answers to it, and refused when several do.
     *
     * The model is handed these ids in the middle of a lookup and hands one
     * back a turn later; a truncated uuid is what actually survives that trip.
     * Ambiguity is the one case where refusing beats guessing — this deletes.
     */
    override fun forget(id: String): Boolean {
        val wanted = id.trim()
        if (wanted.isEmpty()) return false
        val all = store.list()
        val exact = all.firstOrNull { it.id == wanted }
        val named = exact ?: all.filter { it.id.startsWith(wanted) }.singleOrNull() ?: return false
        store.delete(named.id)
        return store.load(named.id) == null
    }
}
