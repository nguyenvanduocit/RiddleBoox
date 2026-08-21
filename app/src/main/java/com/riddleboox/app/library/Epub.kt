package com.riddleboox.app.library

import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** One document of a book, in the order it is meant to be read. */
data class Chapter(val title: String, val href: String)

/** A stretch of a book with something the reader was looking for inside it. */
data class Passage(val chapter: Int, val chapterTitle: String, val text: String)

/** How much text is quoted around a match found by [Epub.passages]. */
private const val PASSAGE_CHARS = 220

/**
 * How far into a document [Epub] reads when all it wants is the heading at the
 * top of it. A chapter is megabytes; its title is in the first line.
 */
private const val HEADING_BYTES = 8 * 1024

/**
 * An EPUB opened for reading — its chapters, and the words in them.
 *
 * An EPUB is a zip holding XHTML documents plus two indexes: a *spine* saying
 * which documents are the book and in what order, and a table of contents
 * giving them names. Both are read here; nothing is unpacked to disk and no
 * document is inflated until a chapter is actually asked for, because a book
 * is tens of megabytes and the diary usually wants one page of it.
 *
 * The markup is reduced to text with expressions rather than an XML parser on
 * purpose. Real EPUBs in a real library are frequently not well-formed —
 * unclosed tags, stray entities, mismatched namespaces — and a parser's answer
 * to that is to throw, which would make exactly the badly-made books
 * unreadable. Text extraction has no structure to protect: whatever is between
 * the angle brackets goes, and what is left is the words.
 *
 * Not thread-safe: [ZipFile] is not, and one diary reads one book at a time.
 * Close it when done.
 */
class Epub private constructor(
    private val zip: ZipFile,
    private val entries: List<String>,
    val chapters: List<Chapter>,
) : Closeable {

    /**
     * The words of chapter [index], or an empty string when there is no such
     * chapter or its document is missing from the zip.
     */
    fun text(index: Int): String {
        val chapter = chapters.getOrNull(index) ?: return ""
        return htmlToText(zip.text(entries, chapter.href))
    }

    /**
     * Every place [query] occurs in the book, as the sentence-or-so around it,
     * up to [limit] of them.
     *
     * The search is diacritic-blind ([Folded]) but the passage handed back is
     * cut out of the original text, tones and capitals intact — the writer
     * asked in whatever they could spell, and should be quoted the book.
     */
    fun passages(query: String, limit: Int, window: Int = PASSAGE_CHARS): List<Passage> {
        val wanted = fold(query.trim())
        if (wanted.isEmpty()) return emptyList()
        val found = ArrayList<Passage>()
        for ((index, chapter) in chapters.withIndex()) {
            if (found.size >= limit) break
            val text = text(index)
            val folded = Folded(text)
            var at = folded.value.indexOf(wanted)
            while (at >= 0 && found.size < limit) {
                val start = folded.sourceIndex(at)
                val end = folded.sourceIndex(at + wanted.length)
                found.add(Passage(index, chapter.title, excerpt(text, start, end, window)))
                at = folded.value.indexOf(wanted, at + wanted.length)
            }
        }
        return found
    }

    override fun close() {
        runCatching { zip.close() }
    }

    companion object {

        /**
         * Opens [file] as an EPUB, or returns null when it is not one — a PDF,
         * a truncated download, a file the app is not allowed to read.
         *
         * Null rather than an exception because "this book cannot be opened"
         * is an ordinary answer here, not a failure: half the reader's shelf is
         * PDFs.
         */
        fun open(file: File): Epub? {
            val zip = runCatching { ZipFile(file) }.getOrNull() ?: return null
            return runCatching {
                val entries = zip.entries().asSequence().map { it.name }.toList()
                val opf = opfPath(zip.text(entries, "META-INF/container.xml")) ?: error("no rootfile")
                val base = opf.substringBeforeLast('/', "")
                val pkg = zip.text(entries, opf)
                val documents = spine(pkg).map { resolve(base, it) }
                val titles = tableOfContents(zip, entries, pkg, base)
                Epub(
                    zip = zip,
                    entries = entries,
                    chapters = documents.mapIndexed { i, href ->
                        Chapter(
                            title = titles[href]
                                ?: heading(zip.text(entries, href, HEADING_BYTES))
                                ?: "Phần ${i + 1}",
                            href = href,
                        )
                    },
                )
            }.getOrElse {
                zip.close()
                null
            }
        }
    }
}

// ---- the two indexes an EPUB carries ----

/** Where `META-INF/container.xml` says the package document is. */
internal fun opfPath(containerXml: String): String? =
    Regex("""full-path\s*=\s*["']([^"']+)["']""").find(containerXml)?.groupValues?.get(1)

/**
 * The hrefs of the documents that are the book, in reading order.
 *
 * The spine names manifest ids, not files, so the manifest is read first and
 * the ids resolved through it. Anything the spine marks `linear="no"` — cover
 * art, ad pages — is not part of the reading and is left out.
 */
internal fun spine(packageXml: String): List<String> {
    val manifest = manifestItems(packageXml).associate { it.id to it.href }
    return Regex("""<itemref\b[^>]*>""").findAll(packageXml)
        .filter { attribute(it.value, "linear") != "no" }
        .mapNotNull { attribute(it.value, "idref") }
        .mapNotNull { manifest[it] }
        .toList()
}

/** One `<item>` of a package document's manifest. */
private class Item(val id: String, val href: String, val mediaType: String, val properties: String)

private fun manifestItems(packageXml: String): List<Item> =
    Regex("""<item\b[^>]*>""").findAll(packageXml)
        .mapNotNull { tag ->
            val id = attribute(tag.value, "id") ?: return@mapNotNull null
            val href = attribute(tag.value, "href") ?: return@mapNotNull null
            Item(id, href, attribute(tag.value, "media-type").orEmpty(), attribute(tag.value, "properties").orEmpty())
        }
        .toList()

/**
 * Chapter titles by href, as the reader's own table of contents gives them.
 *
 * EPUB 2 keeps it in an NCX file and EPUB 3 in an XHTML `<nav>`; both are read,
 * because a book in a real library may be either and many EPUB 3 files still
 * ship an NCX. Only the manifest items that declare themselves to be an index
 * are opened — a book's ordinary chapters are not searched for one, which on a
 * four-hundred-document book is the difference between a listing and a wait.
 *
 * A book with neither index falls back to the first heading inside each
 * document, which is what the writer would call the chapter anyway.
 */
private fun tableOfContents(
    zip: ZipFile,
    entries: List<String>,
    packageXml: String,
    base: String,
): Map<String, String> {
    val titles = LinkedHashMap<String, String>()
    val indexes = manifestItems(packageXml)
        .filter { it.mediaType == "application/x-dtbncx+xml" || it.properties.split(' ').contains("nav") }
        .map { resolve(base, it.href) }
    for (path in indexes) {
        val body = zip.text(entries, path)
        val here = path.substringBeforeLast('/', "")
        for ((title, href) in navPoints(body) + navLinks(body)) {
            titles.putIfAbsent(resolve(here, href), title)
        }
    }
    return titles
}

/** `<navLabel><text>…</text></navLabel><content src="…"/>` — the EPUB 2 index. */
private fun navPoints(ncx: String): List<Pair<String, String>> =
    Regex("""<navLabel>\s*<text>(.*?)</text>\s*</navLabel>\s*<content\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(ncx)
        .mapNotNull { match ->
            val href = attribute(match.value, "src") ?: return@mapNotNull null
            htmlToText(match.groupValues[1]).trim().ifBlank { null }?.let { it to href }
        }
        .toList()

/** `<nav …><ol><li><a href="…">…</a>` — the EPUB 3 index. */
private fun navLinks(xhtml: String): List<Pair<String, String>> {
    val navs = Regex("""<nav\b[^>]*>.*?</nav\s*>""", RegexOption.DOT_MATCHES_ALL).findAll(xhtml).map { it.value }.toList()
    // A nav document also lists landmarks and page numbers; the contents is the
    // one that says so, and the first one otherwise.
    val toc = navs.firstOrNull { it.substringBefore('>').contains("toc") } ?: navs.firstOrNull() ?: return emptyList()
    return Regex("""<a\b([^>]*)>(.*?)</a\s*>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(toc)
        .mapNotNull { match ->
            val href = attribute("<a${match.groupValues[1]}>", "href") ?: return@mapNotNull null
            htmlToText(match.groupValues[2]).trim().ifBlank { null }?.let { it to href }
        }
        .toList()
}

/** The first heading in a document, which is what an untitled chapter is called. */
private fun heading(html: String): String? {
    val h = Regex("""<h[1-6]\b[^>]*>(.*?)</h[1-6]\s*>""", RegexOption.DOT_MATCHES_ALL).find(html)
    val title = Regex("""<title\b[^>]*>(.*?)</title\s*>""", RegexOption.DOT_MATCHES_ALL).find(html)
    return listOfNotNull(h, title)
        .map { htmlToText(it.groupValues[1]).trim() }
        .firstOrNull { it.isNotEmpty() }
}

// ---- paths ----

/**
 * [href], as written inside the book relative to [base], as a zip entry name.
 *
 * Percent escapes are undone because the indexes are URLs and the zip's names
 * are not: a chapter filed as `Chapter%201.xhtml` is stored as `Chapter 1.xhtml`.
 */
internal fun resolve(base: String, href: String): String {
    val plain = href.substringBefore('#')
    val decoded = if (plain.contains('%')) {
        runCatching { java.net.URLDecoder.decode(plain.replace("+", "%2B"), "UTF-8") }.getOrDefault(plain)
    } else {
        plain
    }
    val joined = if (base.isEmpty()) decoded else "$base/$decoded"
    return normalizeSegments(joined).joinToString("/")
}

/** [path]'s segments with `.`, `..` and empty segments collapsed, shell-`cd`-style. */
private fun normalizeSegments(path: String): List<String> {
    val parts = ArrayList<String>()
    for (segment in path.split('/')) {
        when {
            segment.isEmpty() || segment == "." -> continue
            segment == ".." && parts.isEmpty() -> continue
            segment == ".." -> parts.removeAt(parts.size - 1)
            else -> parts.add(segment)
        }
    }
    return parts
}

/**
 * The entry at [path] as text, at most [limit] bytes of it, or "" when the zip
 * has no such entry.
 *
 * Entry names are matched case-insensitively as a fallback: an EPUB's indexes
 * are written by hand often enough that `Text/ch1.xhtml` and `text/ch1.xhtml`
 * both turn up, and the reader on the device forgives it too.
 */
private fun ZipFile.text(entries: List<String>, path: String, limit: Int = Int.MAX_VALUE): String {
    val name = if (entries.contains(path)) path else entries.firstOrNull { it.equals(path, ignoreCase = true) }
    val entry = name?.let { getEntry(it) } ?: return ""
    return runCatching {
        getInputStream(entry).use { stream ->
            if (limit == Int.MAX_VALUE) {
                stream.readBytes()
            } else {
                val buffer = ByteArray(limit)
                var read = 0
                while (read < limit) {
                    val n = stream.read(buffer, read, limit - read)
                    if (n <= 0) break
                    read += n
                }
                buffer.copyOf(read)
            }.decodeToString()
        }
    }.getOrDefault("")
}

/** The value of [name] in an already-matched start tag, or null. */
internal fun attribute(tag: String, name: String): String? =
    Regex("""\b$name\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)

// ---- markup to words ----

private val INVISIBLE = Regex("""(?is)<(script|style|head)\b.*?</\1\s*>""")
private val PARAGRAPH = Regex("""(?i)<\s*(br|/p|/div|/h[1-6]|/li|/tr|/blockquote|/section)\b[^>]*>""")
private val ANY_TAG = Regex("""<[^>]*>""")
private val BLANK_RUN = Regex("""\n{2,}""")
private val INNER_SPACES = Regex("""\s{2,}""")
private val ENTITY = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")

private val ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
    "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
)

/**
 * Markup reduced to the words in it.
 *
 * Tags that end a block become line breaks first, so paragraphs survive as
 * paragraphs; everything else simply goes. Then every run of newlines becomes
 * one, which is not cosmetic: an EPUB writes `</p>` and a newline of its own
 * indentation between every pair of paragraphs, so left alone a chapter comes
 * back double-spaced and the model pays for the blank half by the token.
 */
internal fun htmlToText(html: String): String {
    val stripped = html
        .replace(INVISIBLE, " ")
        .replace(PARAGRAPH, "\n")
        .replace(ANY_TAG, "")
    return decodeEntities(stripped)
        .replace('\u00A0', ' ')
        .lineSequence()
        .joinToString("\n") { it.trim().replace(INNER_SPACES, " ") }
        .replace(BLANK_RUN, "\n")
        .trim()
}

private fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
    val body = match.groupValues[1]
    when {
        body.startsWith("#x") || body.startsWith("#X") ->
            body.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
        body.startsWith("#") ->
            body.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
        else -> ENTITIES[body.lowercase()] ?: match.value
    }
}

/**
 * The text around [start]..[end], padded out to about [window] characters on
 * each side and cut at word boundaries, with an ellipsis where it was cut.
 */
internal fun excerpt(text: String, start: Int, end: Int, window: Int): String {
    var from = (start - window).coerceAtLeast(0)
    var to = (end + window).coerceAtMost(text.length)
    while (from > 0 && !text[from].isWhitespace()) from--
    while (to < text.length && !text[to].isWhitespace()) to++
    val body = text.substring(from, to).trim().replace('\n', ' ')
    return (if (from > 0) "…" else "") + body + (if (to < text.length) "…" else "")
}
