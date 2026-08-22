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
    // Everything from an open figure onward is markup, not ink: it settles the
    // words before it and holds itself until the block closes and is drawn
    // (the state machine drains completed blocks before cutting, so an open
    // tag seen here is always still growing).
    val svg = svgOpenAt(pending)
    val limit = if (svg != null && svg < wordEnd) lastWordBreak(pending.substring(0, svg)) else wordEnd
    if (limit == 0) return 0
    val mark = firstUnclosedMark(pending, limit) ?: return limit
    // A mark can open mid-word — `b**c` — so stopping at the mark itself would
    // hand the pen half a word and it would write the other half as a second.
    return lastWordBreak(pending.substring(0, mark))
}

private const val SVG_OPEN = "<svg"
private const val SVG_CLOSE = "</svg>"

/** Where figure markup begins in [text], or null when none has opened. */
fun svgOpenAt(text: String): Int? =
    text.indexOf(SVG_OPEN, ignoreCase = true).takeIf { it >= 0 }

/**
 * The first complete `<svg>…</svg>` block in [text], as start until one past
 * its end — null while the block is still streaming in. The reply protocol
 * lets the model put a figure's markup straight into its answer; a block is
 * only a figure once it has closed, the same way an emphasis mark is only a
 * mark once it pairs.
 */
fun completedSvgBlock(text: String): IntRange? {
    val start = svgOpenAt(text) ?: return null
    val close = text.indexOf(SVG_CLOSE, startIndex = start, ignoreCase = true)
    if (close < 0) return null
    return start until close + SVG_CLOSE.length
}

/**
 * [text] with every complete figure block removed — what a reply looks like
 * everywhere words are kept: the recorded turn, the conversation the model
 * carries forward. The figure lives as ink on the page alone; markup carried
 * into history would cost hundreds of tokens on every later turn.
 */
fun stripSvgBlocks(text: String): String {
    var out = text
    while (true) {
        val block = completedSvgBlock(out) ?: return out
        out = out.substring(0, block.first) + out.substring(block.last + 1)
    }
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
