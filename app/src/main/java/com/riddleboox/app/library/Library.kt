package com.riddleboox.app.library

/**
 * One book as the reader's own device knows it — not a file on a disk but an
 * entry in their library, carrying where they have got to in it.
 *
 * [id] is the reader app's document id, which is what a [Highlight] hangs off.
 * [path] is the file, which is what [Epub] opens.
 */
data class Book(
    val id: String,
    val title: String,
    val authors: String,
    val path: String,
    val format: String,
    val page: Int,
    val pages: Int,
    val lastOpenedMs: Long,
) {
    /** Whether the words inside can be read, and not only the cover described. */
    val isEpub: Boolean get() = format.equals("epub", ignoreCase = true)

    /** The file's own name, which is often all a badly-tagged book has. */
    val fileName: String get() = path.substringAfterLast('/')
}

/**
 * One passage the reader marked, and whatever they wrote beside it.
 *
 * [id] is the reader app's own id for the mark, carried so a single passage
 * can be named again later — two marks on the same page of the same book are
 * otherwise indistinguishable, and one of them being the one meant matters as
 * soon as marks can be removed.
 */
data class Highlight(
    val id: String,
    val bookId: String,
    val quote: String,
    val note: String,
    val chapter: String,
    val page: Int,
    val markedAtMs: Long,
)

/**
 * The books on the device and what the reader did in them.
 *
 * An interface because the only real implementation talks to another app's
 * database over a content provider ([OnyxLibrary]) — everything above it is
 * testable against a list held in memory instead.
 *
 * Both calls read across a process boundary and both throw rather than return
 * empty when that boundary is shut: a reader with no books and a reader whose
 * library cannot be reached are different answers, and the second one is worth
 * saying out loud.
 */
interface Library {
    /** Every book the reader keeps, most recently opened first. */
    fun books(): List<Book>

    /**
     * What the reader marked in [bookId], or across every book when it is
     * null. Newest first.
     */
    fun highlights(bookId: String?): List<Highlight>

    /**
     * Takes [bookId] out of the reader's library along with every passage
     * marked in it, and says what went.
     *
     * The file the book lives in is not touched here. That is [Book.path] and
     * belongs to shared storage, not to the reader's database; whoever removes
     * the entry decides separately whether the file goes with it.
     */
    fun deleteBook(bookId: String): Removed

    /** Takes out one marked passage, named by its [Highlight.id]. */
    fun deleteHighlight(id: String): Boolean
}

/** What a [Library.deleteBook] actually took out of the reader's database. */
data class Removed(val entry: Boolean, val marks: Int)

/**
 * The books [query] names, best match first.
 *
 * Ranked rather than filtered, because the writer names a book the way they
 * remember it — a word of the title, an author's surname, sometimes the file
 * they downloaded. A title that *is* the query beats one that merely contains
 * it, and among equals the one opened most recently wins: of two books that
 * both answer to "kinh", the one read last night is the one meant.
 */
fun List<Book>.matching(query: String): List<Book> {
    val wanted = fold(query.trim())
    if (wanted.isEmpty()) return this
    return asSequence()
        .mapNotNull { book -> rank(book, wanted)?.let { book to it } }
        .sortedWith(compareBy({ it.second }, { -it.first.lastOpenedMs }))
        .map { it.first }
        .toList()
}

/** The one book [query] names, or null when nothing on the shelf answers to it. */
fun List<Book>.named(query: String): Book? = matching(query).firstOrNull()

/**
 * Every book tied for [query]'s best rank — usually one, more when the shelf
 * holds duplicates or the query is too broad to tell two books apart.
 *
 * [named] breaks that tie by recency, which is the right call for reading:
 * guessing wrong costs nothing more than asking again. Deleting a book costs
 * the book, so [deleteBook][com.riddleboox.app.tools.DiaryTools] uses this
 * instead — a caller that needs to know a guess was made, not just get one.
 */
fun List<Book>.bestMatches(query: String): List<Book> {
    val wanted = fold(query.trim())
    if (wanted.isEmpty()) return this
    val ranked = asSequence().mapNotNull { book -> rank(book, wanted)?.let { book to it } }.toList()
    val bestRank = ranked.minOfOrNull { it.second } ?: return emptyList()
    return ranked.filter { it.second == bestRank }
        .sortedByDescending { it.first.lastOpenedMs }
        .map { it.first }
}

/** How well [book] answers to the already-folded [wanted], lower being better. */
private fun rank(book: Book, wanted: String): Int? {
    val title = fold(book.title)
    val authors = fold(book.authors)
    val file = fold(book.fileName)
    return when {
        title == wanted -> 0
        title.startsWith(wanted) -> 1
        title.contains(wanted) -> 2
        authors.contains(wanted) -> 3
        file.contains(wanted) -> 4
        // Last resort: every word of the query is somewhere in the entry, in
        // any order. "gombrich nghe thuat" names one book and matches no
        // single field of it.
        else -> {
            val haystack = "$title $authors $file"
            val words = wanted.split(' ').filter { it.isNotEmpty() }
            if (words.isNotEmpty() && words.all { haystack.contains(it) }) 5 else null
        }
    }
}
