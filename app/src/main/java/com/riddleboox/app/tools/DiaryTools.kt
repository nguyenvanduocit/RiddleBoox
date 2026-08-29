package com.riddleboox.app.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.riddleboox.app.library.Book
import com.riddleboox.app.library.Epub
import com.riddleboox.app.library.Highlight
import com.riddleboox.app.library.Library
import com.riddleboox.app.library.bestMatches
import com.riddleboox.app.library.canOpenBooks
import com.riddleboox.app.library.matching
import com.riddleboox.app.library.named
import com.riddleboox.app.reply.Toolbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

private const val SEARCH_LIBRARY = "search_library"
private const val OPEN_READER = "open_reader"
private const val BOOK_CONTENTS = "book_contents"
private const val READ_BOOK = "read_book"
private const val SEARCH_IN_BOOK = "search_in_book"
private const val READ_HIGHLIGHTS = "read_highlights"
private const val RECALL_DIARY = "recall_diary"
private const val DELETE_BOOK = "delete_book"
private const val DELETE_HIGHLIGHT = "delete_highlight"
private const val FORGET_DIARY = "forget_diary"

/**
 * How much of a book one [READ_BOOK] hands back.
 *
 * A chapter of a novel runs to tens of thousands of characters and the diary
 * answers in two sentences; the rest would be paid for and thrown away. What
 * is cut is said out loud in the answer, with the offset to ask for next, so
 * the model can go on reading when it really needs to rather than guess it has
 * the whole thing.
 */
private const val READ_CHARS = 5000

/** Beyond this a table of contents is a wall of text, not an index. */
private const val CHAPTERS_LISTED = 200

/** A marked passage is a sentence or two; anything longer was a slip of the finger. */
private const val QUOTE_CHARS = 400

/**
 * How much of an id is shown, and how much of one is needed back.
 *
 * A reader's id is 32 hex characters; printing whole ones beside fifteen
 * marked passages costs more tokens than the passages. Eight is short enough to
 * be free and long enough that two marks colliding on it is not a thing that
 * happens on one device — and a collision is refused rather than guessed at.
 */
private const val ID_SHOWN = 8

private const val BOOKS_LISTED = 8
private const val MARKS_LISTED = 15
private const val PASSAGES_FOUND = 5
private const val EVENINGS_RECALLED = 6

/** The most of anything the model can ask for in one call, whatever it asks. */
private const val MOST = 40

/**
 * What the diary can find out about the reader who is writing to it: the books
 * on their device, where they are in each, what they marked, and the evenings
 * it has already spent with them.
 *
 * Every answer is plain lines of text rather than JSON. The model reads these
 * and nobody else ever does, and prose costs a third of the tokens the same
 * facts cost wrapped in braces and quotes — on a device where the writer is
 * watching an empty page until the first word arrives, that is time.
 *
 * [openBook] is a seam for tests: the real one opens the file the library
 * points at, a test hands back a book built in memory. [booksReadable] is the
 * same kind of seam — the real one asks Android whether all-files access is
 * on, which is what decides how [unopenable] explains a book that will not
 * open.
 */
class DiaryTools(
    private val library: Library,
    private val memory: DiaryMemory,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val openBook: (Book) -> Epub? = { Epub.open(File(it.path)) },
    private val openReader: suspend (Book) -> Boolean = { false },
    private val booksReadable: () -> Boolean = ::canOpenBooks,
) : Toolbox {

    override val tools: List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = SEARCH_LIBRARY,
            description = "The books on the writer's e-reader and how far they have read into each. " +
                "Give words from a title or an author to find one; give nothing at all for what " +
                "they have been reading lately.",
            requiredParameters = emptyList(),
            optionalParameters = listOf(
                ToolParameterDescriptor("query", "Words from a title or an author.", ToolParameterType.String),
                ToolParameterDescriptor("limit", "How many books to name. Default $BOOKS_LISTED.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = OPEN_READER,
            description = "Open one book in the BOOX reader app. Name a title, title fragment, author, " +
                "or file name; use $SEARCH_LIBRARY first when more than one book could be meant.",
            requiredParameters = listOf(
                ToolParameterDescriptor("book", "A title, title fragment, author, or file name.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
        ToolDescriptor(
            name = BOOK_CONTENTS,
            description = "The numbered chapters of one book, and which page of it the writer has reached. " +
                "The chapter numbers are what $READ_BOOK takes.",
            requiredParameters = listOf(
                ToolParameterDescriptor("book", "A title, or enough of one to know it by.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
        ToolDescriptor(
            name = READ_BOOK,
            description = "The actual words of one chapter of a book. EPUB only; a PDF cannot be read this way. " +
                "Long chapters come back in pieces — the answer says where it was cut.",
            requiredParameters = listOf(
                ToolParameterDescriptor("book", "A title, or enough of one to know it by.", ToolParameterType.String),
                ToolParameterDescriptor("chapter", "Chapter number as $BOOK_CONTENTS gave it.", ToolParameterType.Integer),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("offset", "Skip this many characters, to read on past a cut.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = SEARCH_IN_BOOK,
            description = "Find a phrase inside a book and read the passages around it, without " +
                "knowing which chapter it is in. EPUB only.",
            requiredParameters = listOf(
                ToolParameterDescriptor("book", "A title, or enough of one to know it by.", ToolParameterType.String),
                ToolParameterDescriptor("query", "The phrase to look for.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("limit", "How many passages. Default $PASSAGES_FOUND.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = READ_HIGHLIGHTS,
            description = "The passages the writer marked in a book and the notes they wrote beside them — " +
                "what they themselves thought worth keeping. Name a book, or give none for the newest " +
                "marks across the whole library. Works for PDFs too.",
            requiredParameters = emptyList(),
            optionalParameters = listOf(
                ToolParameterDescriptor("book", "A title, or enough of one to know it by.", ToolParameterType.String),
                ToolParameterDescriptor("limit", "How many marks. Default $MARKS_LISTED.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = RECALL_DIARY,
            description = "Earlier evenings written in this diary, from before the pages you can still see. " +
                "Search by a word that was written to you or that you answered.",
            requiredParameters = listOf(
                ToolParameterDescriptor("query", "A word or name to look for.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("limit", "How many exchanges. Default $EVENINGS_RECALLED.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = DELETE_BOOK,
            description = "Remove one book from the writer's reader for good: its file, its place in the " +
                "library, and every passage they marked in it. Nothing is kept and nothing can be brought " +
                "back. Only when the writer asked for that book to go; name it back to them in the answer.",
            requiredParameters = listOf(
                ToolParameterDescriptor("book", "A title, or enough of one to know it by.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "keep_file",
                    "String true to take the book out of the library but leave the file on the device; default false.",
                    ToolParameterType.String,
                ),
            ),
        ),
        ToolDescriptor(
            name = DELETE_HIGHLIGHT,
            description = "Remove one marked passage for good, named by the id $READ_HIGHLIGHTS printed " +
                "beside it. Read the marks first; the id is not guessable.",
            requiredParameters = listOf(
                ToolParameterDescriptor("id", "The id shown beside the mark, as printed or in full.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
        ToolDescriptor(
            name = FORGET_DIARY,
            description = "Burn one earlier evening out of this diary for good — everything written and " +
                "answered in it. Give the id $RECALL_DIARY printed, or words to find it by; when words " +
                "name more than one evening nothing is burned and the evenings are listed instead.",
            requiredParameters = emptyList(),
            optionalParameters = listOf(
                ToolParameterDescriptor("id", "The evening's id, as printed or in full.", ToolParameterType.String),
                ToolParameterDescriptor("query", "Words written in the evening to burn.", ToolParameterType.String),
            ),
        ),
    )

    /**
     * Runs one lookup and puts the result into words.
     *
     * Nothing escapes: a missing book, an unreadable file, a library that
     * cannot be reached and an outright bug all come back as a sentence the
     * model can answer around. The alternative is an exception thrown through
     * a half-written page.
     */
    override suspend fun call(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                SEARCH_LIBRARY -> searchLibrary(arguments.text("query"), arguments.count("limit", BOOKS_LISTED, 0..MOST))
                OPEN_READER -> openBookInReader(arguments.text("book"))
                BOOK_CONTENTS -> bookContents(arguments.text("book"))
                // A chapter number and a character offset are positions in a
                // book, not amounts asked for, so neither is held to [MOST].
                READ_BOOK -> readBook(
                    arguments.text("book"),
                    arguments.count("chapter", 1),
                    arguments.count("offset", 0),
                )
                SEARCH_IN_BOOK -> searchInBook(
                    arguments.text("book"),
                    arguments.text("query"),
                    arguments.count("limit", PASSAGES_FOUND, 0..MOST),
                )
                READ_HIGHLIGHTS -> readHighlights(arguments.text("book"), arguments.count("limit", MARKS_LISTED, 0..MOST))
                RECALL_DIARY -> recallDiary(arguments.text("query"), arguments.count("limit", EVENINGS_RECALLED, 0..MOST))
                DELETE_BOOK -> deleteBook(arguments.text("book"), arguments.text("keep_file").equals("true", true))
                DELETE_HIGHLIGHT -> deleteHighlight(arguments.text("id"))
                FORGET_DIARY -> forgetDiary(arguments.text("id"), arguments.text("query"))
                else -> "There is nothing called $name to consult."
            }
        }.getOrElse { error -> "That could not be looked up: ${error.message ?: error.javaClass.simpleName}" }
    }

    /**
     * What the page says while a lookup runs.
     *
     * Named after what the writer would call it, not after the tool: they know
     * they asked about a book, and the shelf and the diary's own older pages
     * are things in their world. The book's title is quoted back when the model
     * gave one, because that is the whole difference between a wait that makes
     * sense and a wait that does not.
     */
    override fun note(name: String, arguments: JsonObject): String {
        val book = arguments.text("book")
        return when (name) {
            SEARCH_LIBRARY -> "Feeling along the bookshelf…"
            OPEN_READER -> named("Opening", book)
            BOOK_CONTENTS -> named("Looking at the contents of", book)
            READ_BOOK -> named("Reading", book)
            SEARCH_IN_BOOK -> named("Searching through", book)
            READ_HIGHLIGHTS -> if (book.isEmpty()) "Looking over the marked passages…" else named("Looking at the marked passages in", book)
            RECALL_DIARY -> "Turning back through the old pages…"
            DELETE_BOOK -> named("Putting away", book)
            DELETE_HIGHLIGHT -> "Erasing a marked passage…"
            FORGET_DIARY -> "Burning an old evening…"
            else -> "Leafing through…"
        }
    }

    private fun named(doing: String, book: String): String =
        if (book.isEmpty()) "$doing a book…" else "$doing “$book”…"

    // ---- the shelf ----

    private suspend fun openBookInReader(query: String): String {
        if (query.isEmpty()) return "Name the book to open. $SEARCH_LIBRARY says what is there."
        val book = library.books().named(query) ?: return unknown(query)
        return if (openReader(book)) {
            "Opened \"${book.title}\" in the BOOX reader."
        } else {
            "Could not open \"${book.title}\" in the BOOX reader. The book file may be missing, " +
                "or the reader app may not be available."
        }
    }

    private fun searchLibrary(query: String, limit: Int): String {
        val books = library.books()
        if (books.isEmpty()) return "There are no books on this reader."
        val marks = library.highlights(null).groupingBy { it.bookId }.eachCount()
        val hits = if (query.isEmpty()) books else books.matching(query)
        if (hits.isEmpty()) return "No book on this reader answers to \"$query\"."
        val shown = hits.take(limit)
        val head = if (query.isEmpty()) {
            "${books.size} books on this reader; the ${shown.size} opened most recently:"
        } else {
            "${hits.size} of ${books.size} books answer to \"$query\"" +
                if (shown.size < hits.size) ", the first ${shown.size}:" else ":"
        }
        return head + "\n" + shown.joinToString("\n") { shelfLine(it, marks[it.id] ?: 0) }
    }

    private fun shelfLine(book: Book, marks: Int): String = buildString {
        append(book.title)
        if (book.authors.isNotBlank()) append(" | ").append(book.authors)
        append(" | ").append(book.format.ifBlank { "?" })
        if (book.pages > 0) append(" | page ").append(book.page).append(" of ").append(book.pages)
        if (book.lastOpenedMs > 0) append(" | opened ").append(day(book.lastOpenedMs))
        if (marks > 0) append(" | ").append(marks).append(" marked passages")
    }

    // ---- inside one book ----

    private fun bookContents(query: String): String {
        val book = library.books().named(query) ?: return unknown(query)
        val head = shelfLine(book, library.highlights(book.id).size)
        if (!book.isEpub) return "$head\nOnly an EPUB can be opened and read; this is a ${book.format}. " +
            "What the writer marked in it can still be read with $READ_HIGHLIGHTS."
        val epub = openBook(book) ?: return "$head\n${unopenable(book)}"
        return epub.use {
            if (it.chapters.isEmpty()) return@use "$head\nThis book lists no chapters."
            val shown = it.chapters.take(CHAPTERS_LISTED)
            val cut = if (shown.size < it.chapters.size) {
                "\n(${it.chapters.size - shown.size} further chapters not listed)"
            } else {
                ""
            }
            head + "\n" + shown.mapIndexed { i, c -> "${i + 1}. ${c.title}" }.joinToString("\n") + cut
        }
    }

    private fun readBook(query: String, chapter: Int, offset: Int): String {
        val book = library.books().named(query) ?: return unknown(query)
        if (!book.isEpub) return "\"${book.title}\" is a ${book.format} and cannot be read from the inside. " +
            "What the writer marked in it can be read with $READ_HIGHLIGHTS."
        val epub = openBook(book) ?: return unopenable(book)
        return epub.use {
            val index = chapter - 1
            if (index !in it.chapters.indices) {
                return@use "\"${book.title}\" has ${it.chapters.size} chapters; there is no chapter $chapter."
            }
            val text = it.text(index)
            if (text.isBlank()) return@use "Chapter $chapter of \"${book.title}\" has no words in it."
            val from = offset.coerceIn(0, text.length)
            val to = (from + READ_CHARS).coerceAtMost(text.length)
            val head = "\"${book.title}\", chapter $chapter — ${it.chapters[index].title} " +
                "(characters $from to $to of ${text.length})"
            val tail = if (to < text.length) "\n[cut here; call $READ_BOOK again with offset=$to to read on]" else ""
            head + "\n" + text.substring(from, to) + tail
        }
    }

    private fun searchInBook(query: String, phrase: String, limit: Int): String {
        if (phrase.isEmpty()) return "Nothing was given to look for."
        val book = library.books().named(query) ?: return unknown(query)
        if (!book.isEpub) return "\"${book.title}\" is a ${book.format} and cannot be searched from the inside."
        val epub = openBook(book) ?: return unopenable(book)
        return epub.use {
            val found = it.passages(phrase, limit)
            if (found.isEmpty()) return@use "\"$phrase\" does not appear in \"${book.title}\"."
            "${found.size} passages in \"${book.title}\" containing \"$phrase\":\n" +
                found.joinToString("\n") { p -> "chapter ${p.chapter + 1} — ${p.chapterTitle}: ${p.text}" }
        }
    }

    // ---- what the reader themselves wrote ----

    private fun readHighlights(query: String, limit: Int): String {
        val books = library.books()
        val book = if (query.isEmpty()) null else books.named(query) ?: return unknown(query)
        val marks = library.highlights(book?.id)
        if (marks.isEmpty()) {
            return if (book == null) {
                "The writer has marked nothing anywhere on this reader."
            } else {
                "The writer marked nothing in \"${book.title}\"."
            }
        }
        val shown = marks.take(limit)
        val titles = books.associate { it.id to it.title }
        val head = if (book == null) {
            "${shown.size} of ${marks.size} marked passages across the library, newest first:"
        } else {
            "${shown.size} of ${marks.size} marked passages in \"${book.title}\", newest first:"
        }
        return head + "\n" + shown.joinToString("\n") { markLines(it, if (book == null) titles[it.bookId] else null) }
    }

    private fun markLines(mark: Highlight, title: String?): String = buildString {
        listOfNotNull(
            mark.id.takeIf { it.isNotBlank() }?.let { "#" + it.take(ID_SHOWN) },
            title,
            mark.page.takeIf { it > 0 }?.let { "p.$it" },
            mark.chapter.takeIf { it.isNotBlank() },
            day(mark.markedAtMs).takeIf { mark.markedAtMs > 0 },
        ).joinToString(" · ").let { if (it.isNotEmpty()) append(it).append('\n') }
        if (mark.quote.isNotBlank()) append("  \"").append(mark.quote.take(QUOTE_CHARS)).append("\"")
        if (mark.note.isNotBlank()) {
            if (mark.quote.isNotBlank()) append('\n')
            append("  note: ").append(mark.note.take(QUOTE_CHARS))
        }
    }

    private fun recallDiary(query: String, limit: Int): String {
        if (query.isEmpty()) return "Nothing was given to remember."
        val found = memory.recall(query, limit)
        if (found.isEmpty()) return "Nothing in the earlier pages mentions \"$query\"."
        return "${found.size} earlier exchanges mention \"$query\", newest first:\n" +
            found.joinToString("\n") {
                "#${it.evening.take(ID_SHOWN)} · ${day(it.whenMs)} — they wrote: ${it.wrote}\n  you answered: ${it.answered}"
            }
    }

    // ---- taking things off the device ----

    /**
     * Removes a book entirely: the file, the library entry, and the marks.
     *
     * The file goes first and the entry only follows it, because of which
     * half-done state can be recovered from. An entry gone with the file still
     * there is unreachable — every tool here finds books through the library,
     * so nothing can name that file again to finish the job. The other way
     * round is merely wrong on the shelf, and calling this a second time
     * finishes it.
     */
    private fun deleteBook(query: String, keepFile: Boolean): String {
        // An empty query means "any book" to [named]/[bestMatches], which is
        // harmless when reading and a book destroyed when not.
        if (query.isEmpty()) return "Name the book to remove. $SEARCH_LIBRARY says what is there."
        val candidates = library.books().bestMatches(query)
        if (candidates.isEmpty()) return unknown(query)
        // Reading tolerates a guess — deleting does not. [named] would break
        // this same tie by recency and delete the wrong one without a word.
        if (candidates.size > 1) {
            return "\"$query\" names ${candidates.size} books: " +
                candidates.take(8).joinToString(", ") { "\"${it.title}\"" } +
                ". Say more of the title to pick one."
        }
        val book = candidates.single()
        val file = File(book.path)
        val fileStood = book.path.isNotBlank() && file.isFile
        if (fileStood && !keepFile && !file.delete()) {
            return "\"${book.title}\" is still here: its file at ${book.path} would not delete, so nothing " +
                "was taken out of the library either. The storage may be read-only, or all-files access off."
        }
        val removed = library.deleteBook(book.id)
        return "\"${book.title}\": " + listOf(
            if (removed.entry) "gone from the library" else "was not in the library to remove",
            when (removed.marks) {
                0 -> "no marked passages went with it"
                1 -> "1 marked passage gone"
                else -> "${removed.marks} marked passages gone"
            },
            when {
                !fileStood -> "there was no file at ${book.path.ifBlank { "any recorded path" }}"
                keepFile -> "the file is left where it was (${book.path})"
                else -> "the file is deleted (${book.path})"
            },
        ).joinToString("; ") + ". None of it can be brought back."
    }

    /** Removes one marked passage, named by the id [READ_HIGHLIGHTS] printed. */
    private fun deleteHighlight(id: String): String {
        val wanted = id.removePrefix("#").trim()
        if (wanted.isEmpty()) return "No mark was named. $READ_HIGHLIGHTS prints an id beside each one."
        val marks = library.highlights(null)
        val named = marks.firstOrNull { it.id.equals(wanted, ignoreCase = true) }
            ?: marks.filter { it.id.startsWith(wanted, ignoreCase = true) }.let { matches ->
                when (matches.size) {
                    0 -> return "No marked passage answers to \"$id\". $READ_HIGHLIGHTS prints the ids."
                    1 -> matches.single()
                    else -> return "\"$id\" names ${matches.size} marked passages; give more of the id."
                }
            }
        if (!library.deleteHighlight(named.id)) {
            return "That marked passage is still there — the reader would not let it go."
        }
        val what = named.quote.ifBlank { named.note }.take(QUOTE_CHARS)
        return "The marked passage is gone for good" + if (what.isBlank()) "." else ": \"$what\"."
    }

    /**
     * Burns one evening out of the diary.
     *
     * Words rather than an id are allowed because that is how the writer says
     * it — "the evening we talked about the cat" — but words that name more
     * than one evening burn nothing and list them instead. Nothing here is
     * recoverable, so an ambiguous instruction is answered rather than acted on.
     */
    private fun forgetDiary(id: String, query: String): String {
        val named = id.removePrefix("#").trim()
        if (named.isNotEmpty()) {
            return if (memory.forget(named)) {
                "That evening is burned; nothing written or answered in it is left."
            } else {
                "No single evening answers to \"$id\". $RECALL_DIARY prints the ids."
            }
        }
        if (query.isEmpty()) return "Nothing was named to burn. Give an id, or words written in that evening."
        val found = memory.evenings(query, MOST)
        return when (found.size) {
            0 -> "No evening in this diary mentions \"$query\"."
            1 -> {
                val evening = found.single()
                if (memory.forget(evening.id)) {
                    "The evening of ${day(evening.whenMs)} is burned; nothing of it is left."
                } else {
                    "That evening could not be burned."
                }
            }
            else -> "\"$query\" runs through ${found.size} evenings; name one by its id and nothing was burned:\n" +
                found.take(EVENINGS_RECALLED).joinToString("\n") { eveningLine(it) }
        }
    }

    private fun eveningLine(evening: Evening): String =
        "#${evening.id.take(ID_SHOWN)} · ${day(evening.whenMs)} · ${evening.turns} exchanges" +
            if (evening.title.isBlank()) "" else " · ${evening.title.take(QUOTE_CHARS)}"

    // ---- odds and ends ----

    /**
     * Why a book the library lists could not be opened from disk.
     *
     * Two causes wear the same null from [openBook] and send the writer to
     * different places: with all-files access off every book on the shelf is
     * unopenable and the diary's Settings page is the cure; with it on, this
     * one file is broken or gone. The answer names which, because the model
     * can only pass on what it is told — left as one sentence, "cannot open
     * the file" reads as a mood, and the switch never gets found.
     */
    private fun unopenable(book: Book): String = if (booksReadable()) {
        "\"${book.title}\" will not open — the file may be gone, or not readable from here."
    } else {
        "\"${book.title}\" cannot be opened: reading inside books is switched off for this diary. " +
            "Tell the writer it can be turned on in the diary's Settings, on the row about reading " +
            "whole books; titles, progress and marked passages all work without it."
    }

    private fun unknown(query: String): String =
        "No book on this reader answers to \"$query\". $SEARCH_LIBRARY will say what is there."

    private fun day(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
}
