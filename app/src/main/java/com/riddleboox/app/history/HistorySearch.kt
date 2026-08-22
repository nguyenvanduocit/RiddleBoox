package com.riddleboox.app.history

import com.riddleboox.app.library.fold

/**
 * [this] narrowed to the conversations [query] turns up in the writer's own
 * search box on [HistoryActivity] — a match against the opening line
 * ([StoredConversation.title]) or against any turn's transcript or reply.
 *
 * Diacritic-forgiving through [fold], the same way [com.riddleboox.app.tools.StoredMemory.recall]
 * already searches the model's side of this same store: a writer typing
 * "cau chuyen" with no tone marks on a stylus keyboard should still find
 * "Câu Chuyện". A blank query is not a search — it is answered with [this]
 * unchanged, not an empty list, so clearing the box hands the whole history
 * straight back.
 *
 * Order is not this function's job, same as [List<StoredConversation>.toPlainText]:
 * [ConversationStore.list] already hands back newest-answered-first, and
 * re-sorting a filtered view would be a second, easy-to-drift copy of that rule.
 */
fun List<StoredConversation>.matching(query: String): List<StoredConversation> {
    val wanted = fold(query.trim())
    if (wanted.isEmpty()) return this
    return filter { conversation ->
        fold(conversation.title).contains(wanted) ||
            conversation.turns.any { turn -> fold(turn.transcript).contains(wanted) || fold(turn.reply).contains(wanted) }
    }
}
