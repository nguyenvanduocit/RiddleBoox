package com.riddleboox.app.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import android.content.Context
import com.riddleboox.app.dilib.DilibBook
import com.riddleboox.app.dilib.DilibClient
import com.riddleboox.app.reply.Toolbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val SEARCH_BOOKS = "search_dilib_books"
private const val DOWNLOAD_BOOK = "download_dilib_book"
private const val DEFAULT_LIMIT = 8
private const val MAX_LIMIT = 20

/**
 * dilib.vn — the online shelf the diary searches and pulls a book down from.
 *
 * It is a Vietnamese library, so a title the writer names in Vietnamese is
 * likely to be there under the words they used. The tools are named for the
 * shelf rather than for "online books", so the model knows which shelf it is
 * reaching into instead of trusting a source it cannot see.
 *
 * It needs no account, which is why nothing here reads a settings store.
 */
class DilibTools(
    context: Context,
    private val client: DilibClient = DilibClient(),
) : Toolbox {

    private val appContext = context.applicationContext

    override val tools: List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = SEARCH_BOOKS,
            description = "Search Vietnamese books on dilib.vn, a free Vietnamese digital library. " +
                "Prefer this over other book search when the writer names a Vietnamese title or author. " +
                "Returns ids and metadata so download_dilib_book can be called after the writer chooses one.",
            requiredParameters = listOf(
                ToolParameterDescriptor("query", "Title or author to search for.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("limit", "Maximum number of results; default 8.", ToolParameterType.Integer),
                ToolParameterDescriptor("page", "Result page, starting at 1.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = DOWNLOAD_BOOK,
            description = "Download one chosen dilib.vn book into the BOOX library at /sdcard/Books. " +
                "Only call after the writer explicitly asks to download a result and gives or confirms the book id.",
            requiredParameters = listOf(
                ToolParameterDescriptor("book_id", "The numeric book id returned by search_dilib_books.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("format", "Preferred file format, epub or pdf. Defaults to epub when the book has one.", ToolParameterType.String),
            ),
        ),
    )

    override suspend fun call(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                SEARCH_BOOKS -> search(
                    arguments.text("query"),
                    arguments.count("limit", DEFAULT_LIMIT),
                    arguments.count("page", 1),
                )
                DOWNLOAD_BOOK -> download(arguments.text("book_id"), arguments.text("format"))
                else -> "There is no dilib tool called $name."
            }
        }.getOrElse { error -> "dilib could not carry that out: ${error.message ?: error.javaClass.simpleName}" }
    }

    override fun note(name: String, arguments: JsonObject): String = when (name) {
        SEARCH_BOOKS -> arguments.text("query").takeIf { it.isNotBlank() }
            ?.let { "Searching dilib for \"$it\"…" }
            ?: "Searching dilib for books…"
        DOWNLOAD_BOOK -> "Fetching a book from dilib into the BOOX library…"
        else -> "Working with dilib…"
    }

    private suspend fun search(query: String, limit: Int, page: Int): String {
        require(query.isNotBlank()) { "A search needs a keyword." }
        val result = client.search(query, page = page, count = limit.coerceIn(1, MAX_LIMIT))
        if (result.books.isEmpty()) return "dilib has no books matching \"$query\"."
        return buildString {
            append("Found ${result.books.size} results on dilib for \"$query\" · page ${result.page}/${result.totalPages}:\n")
            result.books.forEachIndexed { index, book ->
                append(index + 1)
                append(". [id=${book.id}] ")
                append(book.title.ifBlank { "Untitled" })
                append(" | ")
                append(book.author.ifBlank { "unknown author" })
                if (book.formatLabel.isNotBlank()) append(" | ${book.formatLabel}")
                if (book.pages.isNotBlank()) append(" | ${book.pages} pages")
                if (book.downloads.isNotBlank()) append(" | ${book.downloads} downloads")
                append('\n')
            }
            // dilib's search cards name one format even for books it also has
            // as EPUB, so the list above must not be quoted back as the whole
            // truth — download_dilib_book reads the book page and knows.
            append("The formats above are what dilib prints on its search cards; others may exist. ")
            append("To download, confirm the right id and call $DOWNLOAD_BOOK.")
        }
    }

    private suspend fun download(id: String, format: String): String {
        require(id.isNotBlank()) { "Downloading needs the book_id of the book." }
        val book = client.fetchBook(id)
        val file = book.preferred(format)
            ?: return noFile(book, format)
        val saved = client.download(book, file, appContext)
        return "Downloaded \"${book.title}\" (${file.format.uppercase()}) into the BOOX library: $saved. " +
            "Refresh NeoReader if the book does not appear right away."
    }

    private fun noFile(book: DilibBook, format: String): String = when {
        book.formats.isEmpty() ->
            "\"${book.title}\" has no downloadable file on dilib, only an online reading copy at ${book.url}."
        format.isNotBlank() ->
            "\"${book.title}\" has no $format edition. Available formats: ${book.formatLabel}."
        else -> "\"${book.title}\" has no downloadable file."
    }
}
