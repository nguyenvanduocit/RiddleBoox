package com.riddleboox.app.reply

/**
 * The line that ends the reply and begins the transcript.
 *
 * A separator rather than a JSON envelope, because the reply has to be usable
 * while it is still arriving: JSON cannot be read until its closing brace, so
 * the page would sit blank until the model finished. Here everything before
 * the marker is the reply and can go on the page delta by delta; everything
 * after it is the transcript, which nothing is waiting for.
 */
const val TURN_SEPARATOR = "---"

/** One answered turn: what the diary read, and what it wrote back. */
data class DiaryTurn(
    /** The page's writing as text — what makes every later turn affordable. */
    val transcript: String,
    /** The diary's answer, to be written out in ink. */
    val reply: String,
    /** Whether the model emitted the required reply/separator/transcript contract. */
    val contractSatisfied: Boolean = true,
)

/**
 * Splits a streamed turn as it arrives: reply first, then [TURN_SEPARATOR] on
 * its own line, then the transcript.
 *
 * Feed it deltas with [accept]; it hands back only the reply text, and only
 * text it is sure of. Sure means: not part of a separator that may still be
 * forming. A chunk ending in `"\n-"` could be the start of `"\n---"` or the
 * middle of `"\n-nothing"`, so the tail is held until the next delta settles
 * it — otherwise a stray `-` would be written onto the page in ink.
 */
class TurnStream {

    private val reply = StringBuilder()
    private val transcript = StringBuilder()

    /** Text received but not yet safe to emit — a possible partial separator. */
    private var held = ""

    private var pastSeparator = false

    /**
     * Takes [delta] and returns the reply text that is now certain, which may
     * be empty. Everything after the separator is kept for [finish].
     */
    fun accept(delta: String): String {
        if (pastSeparator) {
            transcript.append(delta)
            return ""
        }

        val pending = held + delta
        val marker = pending.indexOf(SEPARATOR_LINE)
        if (marker >= 0) {
            pastSeparator = true
            held = ""
            val text = pending.substring(0, marker)
            transcript.append(pending.substring(marker + SEPARATOR_LINE.length))
            reply.append(text)
            return text
        }

        // Hold back anything that could still grow into the separator.
        val keep = partialSeparatorAtEnd(pending)
        held = pending.takeLast(keep)
        val text = pending.dropLast(keep)
        reply.append(text)
        return text
    }

    /**
     * The finished turn. Whatever was still held back was never a separator,
     * so it belongs to the reply.
     */
    fun finish(): DiaryTurn {
        if (!pastSeparator && held.isNotEmpty()) {
            reply.append(held)
            held = ""
        }
        return DiaryTurn(
            transcript = transcript.toString().trim(),
            reply = reply.toString().trim(),
            contractSatisfied = pastSeparator,
        )
    }

    /** Length of the longest suffix of [text] that is a prefix of the separator. */
    private fun partialSeparatorAtEnd(text: String): Int {
        val most = minOf(SEPARATOR_LINE.length - 1, text.length)
        for (len in most downTo 1) {
            if (SEPARATOR_LINE.startsWith(text.takeLast(len))) return len
        }
        return 0
    }

    private companion object {
        /** The separator as it appears in the stream: its own line. */
        const val SEPARATOR_LINE = "\n$TURN_SEPARATOR"
    }
}
