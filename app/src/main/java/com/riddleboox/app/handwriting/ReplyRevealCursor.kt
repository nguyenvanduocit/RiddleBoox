package com.riddleboox.app.handwriting

/**
 * Reveals reply strokes at the pace of a hand, over a queue that can still be
 * growing at the far end — the model may still be writing the sentence this
 * cursor is halfway through drawing.
 *
 * Advancing from position P to P+k costs O(k), not O(P+k), so revealing an
 * N-point reply top to bottom costs O(N) total and not O(N^2) (an earlier
 * version rebuilt the whole revealed prefix from stroke 0 on every tick).
 * [totalPoints] is likewise kept as a running sum: recounting a growing queue
 * every tick would put the same O(N^2) back a different way.
 */
class ReplyRevealCursor {

    private val strokes = ArrayList<WriteStroke>()

    private var totalPoints: Int = 0

    var revealedPoints: Int = 0
        private set

    private var strokeIndex = 0

    /** Points already emitted within `strokes[strokeIndex]`. */
    private var pointIndex = 0

    /** True when the pen has drawn everything handed to it and is waiting. */
    val caughtUp: Boolean get() = revealedPoints >= totalPoints

    /**
     * How many strokes the pen has begun — the one it is halfway through
     * counts, because part of a letter is still a letter on the page.
     */
    val revealedStrokes: Int get() = strokeIndex + if (pointIndex > 0) 1 else 0

    /**
     * Everything drawn so far, whole strokes and the part-drawn one, as the
     * standing reply it becomes when a turn is stopped mid-word.
     *
     * Only for that: the tick draws from [revealMore], which hands back the
     * delta. Rebuilding the whole prefix every tick is the O(N^2) this class
     * exists to avoid.
     */
    fun revealed(): List<WriteStroke> {
        val out = ArrayList<WriteStroke>(revealedStrokes)
        for (i in 0 until minOf(strokeIndex, strokes.size)) out.add(strokes[i])
        if (strokeIndex < strokes.size && pointIndex > 0) {
            out.add(WriteStroke(strokes[strokeIndex].points.subList(0, pointIndex)))
        }
        return out
    }

    /** Hand the pen more to write, at the end of what it already has. */
    fun add(newStrokes: List<WriteStroke>) {
        if (newStrokes.isEmpty()) return
        strokes.addAll(newStrokes)
        for (stroke in newStrokes) totalPoints += stroke.points.size
    }

    /**
     * Advance by up to [maxPoints] (clamped to what's left). Returns ONLY the
     * newly-drawable segment(s) this call adds — a fully-spanned stroke is
     * returned whole; a stroke that straddles the boundary is returned as a
     * continuation fragment that includes ONE overlap point with what was
     * already revealed (so line-drawing code — connecting consecutive points
     * with drawLine — has no gap between this call's segment and the
     * previous one), except when starting a stroke fresh from point 0, where
     * there is nothing yet to overlap with. A single call may return more
     * than one WriteStroke if the delta spans a stroke boundary (tail of the
     * old stroke + head of the next).
     */
    fun revealMore(maxPoints: Int): List<WriteStroke> {
        var budget = minOf(maxPoints, totalPoints - revealedPoints)
        if (budget <= 0) return emptyList()

        val segments = ArrayList<WriteStroke>(2)
        while (budget > 0 && strokeIndex < strokes.size) {
            val points = strokes[strokeIndex].points
            if (pointIndex >= points.size) {
                strokeIndex++
                pointIndex = 0
                continue
            }
            val take = minOf(budget, points.size - pointIndex)
            val from = if (pointIndex == 0) 0 else pointIndex - 1
            segments.add(WriteStroke(points.subList(from, pointIndex + take)))
            pointIndex += take
            revealedPoints += take
            budget -= take
        }
        return segments
    }
}
