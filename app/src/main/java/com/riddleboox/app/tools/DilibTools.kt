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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
        try {
            when (name) {
                SEARCH_BOOKS -> search(
                    arguments.text("query"),
                    arguments.count("limit", DEFAULT_LIMIT),
                    arguments.count("page", 1),
                )
                DOWNLOAD_BOOK -> download(arguments.text("book_id"), arguments.text("format"))
                else -> "Không có tool dilib tên là $name."
            }
        } catch (error: Exception) {
            "dilib không thực hiện được yêu cầu: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    override fun note(name: String, arguments: JsonObject): String = when (name) {
        SEARCH_BOOKS -> arguments.text("query").takeIf { it.isNotBlank() }
            ?.let { "Đang tìm \"$it\" trên dilib…" }
            ?: "Đang tìm sách trên dilib…"
        DOWNLOAD_BOOK -> "Đang tải sách từ dilib vào thư viện BOOX…"
        else -> "Đang làm việc với dilib…"
    }

    private suspend fun search(query: String, limit: Int, page: Int): String {
        require(query.isNotBlank()) { "Cần từ khóa để tìm sách." }
        val result = client.search(query, page = page, count = limit.coerceIn(1, MAX_LIMIT))
        if (result.books.isEmpty()) return "Không tìm thấy sách nào trên dilib cho \"$query\"."
        return buildString {
            append("Tìm thấy ${result.books.size} kết quả trên dilib cho \"$query\" · trang ${result.page}/${result.totalPages}:\n")
            result.books.forEachIndexed { index, book ->
                append(index + 1)
                append(". [id=${book.id}] ")
                append(book.title.ifBlank { "Không có tiêu đề" })
                append(" | ")
                append(book.author.ifBlank { "không rõ tác giả" })
                if (book.formatLabel.isNotBlank()) append(" | ${book.formatLabel}")
                if (book.pages.isNotBlank()) append(" | ${book.pages} trang")
                if (book.downloads.isNotBlank()) append(" | ${book.downloads} lượt tải")
                append('\n')
            }
            // dilib's search cards name one format even for books it also has
            // as EPUB, so the list above must not be quoted back as the whole
            // truth — download_dilib_book reads the book page and knows.
            append("Định dạng ở trên là dilib in trên thẻ tìm kiếm, có thể còn định dạng khác. ")
            append("Muốn tải, hãy xác nhận đúng id rồi gọi $DOWNLOAD_BOOK.")
        }
    }

    private suspend fun download(id: String, format: String): String {
        require(id.isNotBlank()) { "Cần book_id của sách muốn tải." }
        val book = client.fetchBook(id)
        val file = book.preferred(format)
            ?: return noFile(book, format)
        val saved = client.download(book, file, appContext)
        client.index(saved, appContext)
        return "Đã tải \"${book.title}\" (${file.format.uppercase()}) vào thư viện BOOX: ${saved.absolutePath}. " +
            "Hãy làm mới NeoReader nếu sách chưa hiện ngay."
    }

    private fun noFile(book: DilibBook, format: String): String = when {
        book.formats.isEmpty() ->
            "\"${book.title}\" trên dilib không có file tải xuống, chỉ có bản đọc online tại ${book.url}."
        format.isNotBlank() ->
            "\"${book.title}\" không có bản $format. Định dạng đang có: ${book.formatLabel}."
        else -> "\"${book.title}\" không có file tải xuống."
    }

    private fun JsonObject.text(name: String): String =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.count(name: String, fallback: Int): Int =
        text(name).toIntOrNull() ?: fallback
}
