package com.riddleboox.app.tools

import ai.koog.agents.core.tools.ToolDescriptor
import com.riddleboox.app.library.Book
import com.riddleboox.app.library.Library
import com.riddleboox.app.reply.Toolbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId

private const val GET_CURRENT_BOOX_STATE = "get_current_boox_state"
private const val RECENT_BOOKS = 2

/**
 * A snapshot of where the writer's device stands right now: the books read
 * most recently and how far into each, and the newest BOOX Notebook note.
 *
 * Both halves already sort by recency at their source — [Library.books] by
 * `lastAccess`, [BooxNotesSource.listNotes] by `updatedAt` — so this reads
 * off the front of each rather than searching for "recent" itself.
 *
 * The two halves are caught separately rather than behind one `runCatching`
 * around the whole call: this tool is the one place that reads both the
 * library and BOOX Notebook at once, so one side being unreachable should
 * not blank out the side that answered fine.
 */
class BooxStateTools(
    private val library: Library,
    private val notes: BooxNotesSource,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : Toolbox {

    override val tools: List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = GET_CURRENT_BOOX_STATE,
            description = "A snapshot of the writer's device right now: the $RECENT_BOOKS books read most " +
                "recently with how far into each, and the newest BOOX Notebook note.",
            requiredParameters = emptyList(),
            optionalParameters = emptyList(),
        ),
    )

    override suspend fun call(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        when (name) {
            GET_CURRENT_BOOX_STATE -> snapshot()
            else -> "There is nothing called $name to consult."
        }
    }

    override fun note(name: String, arguments: JsonObject): String = when (name) {
        GET_CURRENT_BOOX_STATE -> "Checking the shelf and the notebook…"
        else -> "Checking the current state…"
    }

    private fun snapshot(): String =
        booksSection() + "\n\n" + noteSection()

    private fun booksSection(): String = runCatching { library.books().take(RECENT_BOOKS) }.fold(
        onSuccess = { shown ->
            if (shown.isEmpty()) "No books have been opened yet." else
                "Recently read:\n" + shown.mapIndexed { i, book -> "${i + 1}. ${bookLine(book)}" }.joinToString("\n")
        },
        onFailure = { "Recently read: could not reach the library (${it.message ?: it.javaClass.simpleName})." },
    )

    private fun bookLine(book: Book): String = buildString {
        append(book.title)
        if (book.authors.isNotBlank()) append(" | ").append(book.authors)
        if (book.pages > 0) append(" | page ").append(book.page).append(" of ").append(book.pages)
        if (book.lastOpenedMs > 0) append(" | opened ").append(day(book.lastOpenedMs))
    }

    private fun noteSection(): String = runCatching { notes.listNotes().firstOrNull() }.fold(
        onSuccess = { newest ->
            if (newest == null) "No BOOX Notebook note has been written yet." else
                "Latest BOOX note: \"${newest.title}\" | ${newest.pageCount} pages | edited ${day(newest.updatedAtMs)}"
        },
        onFailure = { "Latest BOOX note: could not reach BOOX Notebook (${it.message ?: it.javaClass.simpleName})." },
    )

    private fun day(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
}
