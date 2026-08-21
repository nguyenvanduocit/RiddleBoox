package com.riddleboox.app.riddle

/**
 * The reply as it has reached the page — in words, and in how much of it the
 * writer has actually watched appear.
 *
 * The two are not the same thing and the gap is wide. A model streams a whole
 * answer in a second or two; the pen draws it at the speed of a hand, so at any
 * moment there is text that has been laid out and is still waiting its turn to
 * be revealed. That gap is invisible until a turn is cut short, and then it
 * decides what the diary is recorded as having said: everything it had in hand,
 * or only what the writer read.
 *
 * [seen] answers the second, which is the one a conversation can be resumed on
 * — a diary that recorded a sentence nobody ever saw would answer the next page
 * as though that sentence were common ground.
 *
 * Resolution is one settled chunk, not one character: a chunk is what arrives
 * from the stream, is laid out, and becomes strokes all at once, so there is no
 * finer boundary to be had. Cutting mid-word therefore keeps that word.
 */
class ReplySoFar {

    private val text = StringBuilder()

    /** One per settled chunk: strokes standing once it was laid out, and text up to there. */
    private val marks = ArrayList<Mark>()

    /** Every word laid out, whether or not it has been drawn yet. */
    val whole: String get() = text.toString()

    val isEmpty: Boolean get() = text.isEmpty()

    /**
     * Records that [ink] was laid out, after which [strokesLaidOut] strokes
     * stood in the reply's plan.
     */
    fun append(ink: String, strokesLaidOut: Int) {
        if (ink.isEmpty()) return
        text.append(ink)
        marks.add(Mark(strokes = strokesLaidOut, chars = text.length))
    }

    /**
     * The words the writer had in front of them once [revealedStrokes] strokes
     * had been drawn.
     *
     * A chunk counts as seen only when the pen has begun the last stroke it
     * laid out; anything past that is still text the page has never shown.
     */
    fun seen(revealedStrokes: Int): String {
        val chars = marks.lastOrNull { it.strokes <= revealedStrokes }?.chars ?: 0
        return text.substring(0, chars.coerceAtMost(text.length))
    }

    fun reset() {
        text.setLength(0)
        marks.clear()
    }

    private data class Mark(val strokes: Int, val chars: Int)
}
