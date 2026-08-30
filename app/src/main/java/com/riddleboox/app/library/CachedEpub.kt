package com.riddleboox.app.library

import java.io.Closeable
import java.io.File

/**
 * An [Epub] whose extracted chapter text is kept on disk between openings.
 *
 * Reading a chapter out of an EPUB is the expensive half of every book tool:
 * inflate the document from the zip, strip its markup, decode its entities —
 * and a search does it for *every* chapter, which on a thousand-chapter novel
 * is minutes of CPU. None of that work changes while the file on disk doesn't,
 * so the first extraction of each chapter is written under [dir] and every
 * later reading — in this opening or any following one — is a plain file read.
 * (The other per-search cost, folding for the diacritic-blind match, is cheap
 * since [Folded] memoised its per-character work.)
 *
 * [fingerprint] is whatever identifies the source file's current contents
 * (size and mtime, in practice). A cache written under a different fingerprint
 * is a cache of a different file and is wiped, not reused. [dir] may be null,
 * which disables caching entirely and leaves this a plain pass-through — the
 * seam tests use, and the behaviour when no cache directory was configured.
 *
 * A cache that cannot be written (disk full, directory swept mid-use) must
 * never break reading: every cache write failure is swallowed and the text is
 * simply handed back uncached.
 */
class CachedEpub(
    private val epub: Epub,
    private val dir: File?,
    fingerprint: String = "",
) : Closeable {

    val chapters: List<Chapter> get() = epub.chapters

    init {
        if (dir != null) {
            val meta = File(dir, "meta")
            val stamp = "$fingerprint/${epub.chapters.size}"
            if (runCatching { meta.readText() }.getOrNull() != stamp) {
                dir.deleteRecursively()
                dir.mkdirs()
                runCatching { meta.writeText(stamp) }
            }
        }
    }

    /** The words of chapter [index] — off disk when they have been extracted before. */
    fun text(index: Int): String {
        val cached = dir?.let { File(it, "$index.txt") }
        if (cached != null && cached.isFile) {
            val body = runCatching { cached.readText() }.getOrNull()
            if (body != null) return body
        }
        val body = epub.text(index)
        if (cached != null) runCatching { cached.writeText(body) }
        return body
    }

    /** Same contract as [Epub.passages], reading chapters through the cache. */
    fun passages(query: String, limit: Int, window: Int): List<Passage> =
        searchPassages(chapters, ::text, query, limit, window)

    override fun close() = epub.close()
}
