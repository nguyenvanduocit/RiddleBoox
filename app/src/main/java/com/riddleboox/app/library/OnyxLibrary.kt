package com.riddleboox.app.library

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri

/**
 * The authority NeoReader publishes its own library database under.
 *
 * Found on the device rather than in any SDK: `adb shell dumpsys package
 * providers` lists the class as `com.onyx.android.sdk.readerview.db.
 * ContentDatabaseContentProvider` and this string as the authority it answers
 * to. It is exported and asks for no permission, which is what makes the
 * reader's own shelf legible to another app at all.
 *
 * Package visibility filtering applies on top of that: an app targeting API 30
 * or later cannot see a provider in a package it has not declared, so
 * `AndroidManifest.xml` carries a `<queries><provider>` element naming this
 * authority. Without it every query below comes back null.
 */
private const val AUTHORITY = "com.onyx.content.database.ContentProvider"

private const val BOOKS = "Metadata"
private const val MARKS = "Annotation"

/**
 * Columns are named explicitly rather than taken wholesale, and not for
 * tidiness: `Metadata.extraAttributes` is a kilobyte of reader settings per
 * row, and 250 books of it does not fit through the cursor window a content
 * provider hands back.
 */
private val BOOK_COLUMNS = arrayOf(
    "uuid", "title", "name", "authors", "nativeAbsolutePath", "type", "progress", "lastAccess",
)

private val MARK_COLUMNS = arrayOf("uuid", "idString", "quote", "note", "chapter", "pageNumber", "createdAt")

/**
 * The reader's own library, kept in NeoReader's database and reached over its
 * exported provider: what is on the shelf, and what comes off it.
 *
 * Removals go through the same provider the reads do — `uuid` for a book,
 * `idString` for the marks hanging off it — so the reader app sees them the
 * moment they happen. Deleting the file alone would not: NeoReader would go on
 * listing the book and open it onto nothing.
 *
 * A removal here is final. There is no bin behind this provider and no undo:
 * the row is gone when it returns, and the marks with it.
 */
class OnyxLibrary(private val resolver: ContentResolver) : Library {

    override fun books(): List<Book> =
        read(BOOKS, BOOK_COLUMNS, sort = "lastAccess DESC") { c ->
            val name = c.text("name")
            val title = c.text("title").ifBlank { name.substringBeforeLast('.') }
            val (page, pages) = progress(c.text("progress"))
            Book(
                id = c.text("uuid"),
                title = title,
                authors = c.text("authors"),
                path = c.text("nativeAbsolutePath"),
                format = c.text("type"),
                page = page,
                pages = pages,
                lastOpenedMs = c.number("lastAccess"),
            )
        }.filter { it.id.isNotBlank() && it.title.isNotBlank() }

    override fun highlights(bookId: String?): List<Highlight> =
        read(
            table = MARKS,
            columns = MARK_COLUMNS,
            where = bookId?.let { "idString = ?" },
            arguments = bookId?.let { arrayOf(it) },
            sort = "createdAt DESC",
        ) { c ->
            Highlight(
                id = c.text("uuid"),
                bookId = c.text("idString"),
                quote = c.text("quote"),
                note = c.text("note"),
                chapter = c.text("chapter"),
                page = c.number("pageNumber").toInt(),
                markedAtMs = c.number("createdAt"),
            )
        }.filter { it.quote.isNotBlank() || it.note.isNotBlank() }

    override fun deleteBook(bookId: String): Removed {
        require(bookId.isNotBlank()) { "a book id is needed" }
        // Marks first: the entry is what the shelf is read by, so losing the
        // entry and keeping the marks would leave passages nothing can name.
        val marks = delete(MARKS, "idString = ?", arrayOf(bookId))
        val entry = delete(BOOKS, "uuid = ?", arrayOf(bookId))
        return Removed(entry = entry > 0, marks = marks)
    }

    override fun deleteHighlight(id: String): Boolean {
        require(id.isNotBlank()) { "a mark id is needed" }
        return delete(MARKS, "uuid = ?", arrayOf(id)) > 0
    }

    /** Rows actually taken out of [table]. */
    private fun delete(table: String, where: String, arguments: Array<String>): Int =
        resolver.delete(Uri.parse("content://$AUTHORITY/$table"), where, arguments)

    /**
     * A query that fails loudly. A null cursor means the provider was not
     * reachable — the reader app gone, or this app's manifest not declaring
     * it — and that is worth telling the writer, not rounding down to an empty
     * shelf.
     */
    private fun <T> read(
        table: String,
        columns: Array<String>,
        where: String? = null,
        arguments: Array<String>? = null,
        sort: String? = null,
        row: (Cursor) -> T,
    ): List<T> {
        val uri = Uri.parse("content://$AUTHORITY/$table")
        val cursor = resolver.query(uri, columns, where, arguments, sort)
            ?: throw LibraryUnreachable("$table không mở được qua $AUTHORITY")
        return cursor.use { c ->
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out.add(row(c))
            out
        }
    }
}

/** The reader's library could not be opened at all — see [OnyxLibrary]. */
class LibraryUnreachable(message: String) : RuntimeException(message)

/** "140/770" as the page reached and the pages there are; zeroes when unset. */
internal fun progress(value: String): Pair<Int, Int> {
    val page = value.substringBefore('/').trim().toIntOrNull() ?: 0
    val pages = value.substringAfter('/', "").trim().toIntOrNull() ?: 0
    return page to pages
}

private fun Cursor.text(column: String): String {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return ""
    return getString(index).orEmpty()
}

private fun Cursor.number(column: String): Long {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return 0L
    return runCatching { getLong(index) }.getOrDefault(0L)
}
