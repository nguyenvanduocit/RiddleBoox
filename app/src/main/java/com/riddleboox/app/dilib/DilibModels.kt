package com.riddleboox.app.dilib

/**
 * One file dilib offers for a book.
 *
 * A book is usually PDF, sometimes PDF and EPUB, and the choice belongs to the
 * reader — an e-ink panel reflows EPUB and merely photographs a PDF — so the
 * formats are carried as a list rather than collapsed into one download url.
 */
data class DilibFile(
    val format: String,
    val url: String,
)

/** A book as dilib describes it, from either a search card or its own page. */
data class DilibBook(
    val id: String,
    val title: String,
    val author: String = "",
    val publisher: String = "",
    val year: String = "",
    val pages: String = "",
    val size: String = "",
    val downloads: String = "",
    val categories: List<String> = emptyList(),
    val formats: List<DilibFile> = emptyList(),
    val coverUrl: String = "",
    val url: String = "",
) {
    /** "PDF, EPUB" — what the writer is offered, in the order dilib lists it. */
    val formatLabel: String
        get() = formats.joinToString(", ") { it.format.uppercase() }

    /** The file to take when the writer did not name a format. */
    fun preferred(format: String = ""): DilibFile? {
        val wanted = format.trim().lowercase().removePrefix(".")
        if (wanted.isNotBlank()) {
            return formats.firstOrNull { it.format.equals(wanted, ignoreCase = true) }
        }
        // EPUB first: it reflows on the panel, and a PDF of a scanned Vietnamese
        // book is the one thing a 7.8" e-ink screen genuinely cannot show well.
        return formats.firstOrNull { it.format.equals("epub", ignoreCase = true) }
            ?: formats.firstOrNull()
    }
}

data class DilibSearchResult(
    val books: List<DilibBook>,
    val page: Int,
    val totalPages: Int,
)

open class DilibException(message: String, cause: Throwable? = null) : Exception(message, cause)
