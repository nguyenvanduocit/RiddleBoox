package com.riddleboox.app.ink

import java.io.File

/**
 * Keeps the last few pages exactly as they were sent, so the writer can see
 * what the diary was actually given.
 *
 * When the diary answers that the ink blurred, there are two very different
 * reasons it might say so: the writing really was unreadable, or the page it
 * received was not the page that was written. Only one of those is worth
 * rewriting your handwriting over, and nothing on screen tells them apart —
 * the page has already dissolved by the time the reply arrives.
 *
 * Files land somewhere the tablet's own file manager can open, named by the
 * moment they were sent, newest last.
 */
class PageArchive(private val dir: File, private val keep: Int = KEEP) {

    /**
     * Writes [png] as the page sent at [timestampMs] and returns the file, or
     * null if it could not be written — an archive that fails is not a reason
     * to lose the turn, so callers are expected to shrug this off.
     */
    fun save(png: ByteArray, timestampMs: Long): File? = runCatching {
        dir.mkdirs()
        val file = File(dir, "page-$timestampMs.png")
        file.writeBytes(png)
        prune()
        file
    }.getOrNull()

    /** Newest first — what the writer wants to look at is the last thing sent. */
    fun pages(): List<File> = dir.listFiles { f -> f.name.startsWith("page-") }
        .orEmpty()
        .sortedByDescending { it.name }

    private fun prune() {
        pages().drop(keep).forEach { it.delete() }
    }

    private companion object {
        /**
         * Enough to look back over an exchange or two that went wrong, few
         * enough that a full-page PNG each never adds up to anything the
         * tablet would notice.
         */
        const val KEEP = 12
    }
}
