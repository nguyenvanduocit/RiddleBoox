package com.riddleboox.app.reply

/** Emphasis characters [plainText] strips once they close around something. */
private val MARK_CHARS = charArrayOf('*', '_', '`')

/**
 * How much of [pending] can go onto the page right now, as an index into it.
 *
 * Ink cannot be taken back, so a word is only writable once the stream has
 * proved what it is. Two things can still change under it:
 *
 *  - A word without whitespace after it may still be growing — `Ch` before
 *    `Chúa` — and its width decides where it sits and whether it wraps.
 *  - An emphasis mark is only known to be a mark once it closes. `**Chúa` is
 *    two asterisks of ink if written now and nothing at all if `**` arrives
 *    later, so anything from an unclosed mark onward waits.
 *
 * Returns 0 when nothing is settled yet. The remainder is not lost: it is
 * written when the next delta settles it, or flushed when the stream ends —
 * whatever was still held back was never a mark.
 */
fun writableCut(pending: String): Int {
    val wordEnd = lastWordBreak(pending)
    if (wordEnd == 0) return 0
    val mark = firstUnclosedMark(pending, wordEnd) ?: return wordEnd
    // A mark can open mid-word — `b**c` — so stopping at the mark itself would
    // hand the pen half a word and it would write the other half as a second.
    return lastWordBreak(pending.substring(0, mark))
}

/** Index just past the last whitespace run, i.e. the end of the last whole word. */
private fun lastWordBreak(text: String): Int {
    for (i in text.length - 1 downTo 0) {
        if (text[i].isWhitespace()) return i + 1
    }
    return 0
}

/**
 * Where the first still-open emphasis run starts within `text.take(limit)`, or
 * null when every run in it is paired.
 *
 * Runs rather than characters, so `**bold**` counts as two and not four.
 */
private fun firstUnclosedMark(text: String, limit: Int): Int? =
    MARK_CHARS.asIterable().mapNotNull { mark -> unclosedRunStart(text, limit, mark) }.minOrNull()

/**
 * Position where [mark]'s last run starts within `text.take(limit)`, or null
 * when [mark]'s runs pair off evenly there — i.e. the last one is closed.
 *
 * Runs rather than characters, so `**` counts as one run and not two.
 */
private fun unclosedRunStart(text: String, limit: Int, mark: Char): Int? {
    val runs = ArrayList<Int>()
    var i = 0
    while (i < limit) {
        if (text[i] != mark) {
            i++
            continue
        }
        runs.add(i)
        while (i < limit && text[i] == mark) i++
    }
    return if (runs.size % 2 == 1) runs.last() else null
}
