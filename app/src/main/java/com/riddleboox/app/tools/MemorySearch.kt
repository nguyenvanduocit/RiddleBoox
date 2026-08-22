package com.riddleboox.app.tools

import com.riddleboox.app.library.fold

/**
 * [this] narrowed to the memories [query] turns up in the writer's own
 * search box on [com.riddleboox.app.MemoriesActivity] — a match against
 * [MemoryEntry.content], the only text a memory carries.
 *
 * Diacritic-forgiving through [fold], the same way history search already
 * narrows conversations: a writer typing "tra xanh" with no tone marks
 * should still find "trà xanh". A blank query is not a search — it is
 * answered with [this] unchanged, not an empty list, so clearing the box
 * hands the whole list straight back.
 *
 * Order is not this function's job: [this] is expected to already be
 * sorted — see [com.riddleboox.app.MemoriesActivity]'s `loadEntries` — and
 * re-sorting a filtered view would be a second, easy-to-drift copy of that
 * rule.
 */
fun List<MemoryEntry>.matching(query: String): List<MemoryEntry> {
    val wanted = fold(query.trim())
    if (wanted.isEmpty()) return this
    return filter { entry -> fold(entry.content).contains(wanted) }
}
