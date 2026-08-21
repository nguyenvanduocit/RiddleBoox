package com.riddleboox.app.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyRevealCursorTest {

    /** Globally distinct points, so a re-emitted point is recognisable as such. */
    private fun p(i: Int) = WritePoint(i.toFloat(), i * 2f)

    private fun stroke(range: IntRange) = WriteStroke(range.map { p(it) })

    private fun cursorOver(vararg strokes: WriteStroke) =
        ReplyRevealCursor().apply { add(strokes.toList()) }

    @Test
    fun revealedIsWhatTheCursorHasActuallyDrawn() {
        val cursor = cursorOver(stroke(0..3), stroke(10..13))

        assertEquals(emptyList<WriteStroke>(), cursor.revealed())
        assertEquals(0, cursor.revealedStrokes)

        // Halfway through the first stroke: the part-drawn one counts, and
        // comes back cut at the point the pen has got to.
        cursor.revealMore(2)
        assertEquals(1, cursor.revealedStrokes)
        assertEquals(listOf(WriteStroke(listOf(p(0), p(1)))), cursor.revealed())

        // Into the second: the first is whole, the second is a stub.
        cursor.revealMore(3)
        assertEquals(2, cursor.revealedStrokes)
        assertEquals(
            listOf(stroke(0..3), WriteStroke(listOf(p(10)))),
            cursor.revealed(),
        )

        cursor.revealMore(100)
        assertTrue(cursor.caughtUp)
        assertEquals(listOf(stroke(0..3), stroke(10..13)), cursor.revealed())
    }

    @Test
    fun anEmptyCursorHasNothingToRevealAndIsAlreadyCaughtUp() {
        val cursor = ReplyRevealCursor()
        assertTrue(cursor.caughtUp)
        assertEquals(emptyList<WriteStroke>(), cursor.revealMore(10))
        assertEquals(0, cursor.revealedPoints)
    }

    @Test
    fun singlePointStrokeComesOutWhole() {
        val dot = WriteStroke(listOf(p(7)))
        val cursor = cursorOver(dot)
        assertEquals(listOf(dot), cursor.revealMore(1))
        assertEquals(1, cursor.revealedPoints)
        assertTrue(cursor.caughtUp)
    }

    @Test
    fun continuationSegmentsCarryOneOverlapPointSoTheLineJoins() {
        val cursor = cursorOver(stroke(0..4))

        // Fresh start: nothing behind it to join onto.
        assertEquals(listOf(WriteStroke(listOf(p(0), p(1)))), cursor.revealMore(2))
        assertEquals(2, cursor.revealedPoints)

        // Continuations repeat the last already-drawn point.
        assertEquals(listOf(WriteStroke(listOf(p(1), p(2), p(3)))), cursor.revealMore(2))
        assertEquals(4, cursor.revealedPoints)

        assertEquals(listOf(WriteStroke(listOf(p(3), p(4)))), cursor.revealMore(1))
        assertEquals(5, cursor.revealedPoints)
    }

    @Test
    fun oneCallSpanningAStrokeBoundaryReturnsTailThenFreshHead() {
        val cursor = cursorOver(stroke(0..2), stroke(10..13))
        assertEquals(listOf(WriteStroke(listOf(p(0), p(1)))), cursor.revealMore(2))

        assertEquals(
            listOf(
                WriteStroke(listOf(p(1), p(2))),
                WriteStroke(listOf(p(10), p(11))),
            ),
            cursor.revealMore(3),
        )
        assertEquals(5, cursor.revealedPoints)
    }

    @Test
    fun revealingPastTheEndClampsThenStaysDone() {
        val cursor = cursorOver(stroke(0..2))
        assertEquals(listOf(WriteStroke(listOf(p(0), p(1), p(2)))), cursor.revealMore(99))
        assertEquals(3, cursor.revealedPoints)

        assertEquals(emptyList<WriteStroke>(), cursor.revealMore(99))
        assertEquals(3, cursor.revealedPoints)
    }

    @Test
    fun zeroOrNegativeIsANoOp() {
        val cursor = cursorOver(stroke(0..2))
        assertEquals(emptyList<WriteStroke>(), cursor.revealMore(0))
        assertEquals(emptyList<WriteStroke>(), cursor.revealMore(-5))
        assertEquals(0, cursor.revealedPoints)
    }

    /**
     * The streaming case: the pen catches up with a reply still arriving,
     * says so, and picks up exactly where it stopped when more comes in.
     */
    @Test
    fun catchingUpIsTemporaryWhenMoreStrokesArrive() {
        val cursor = cursorOver(stroke(0..1))
        assertFalse(cursor.caughtUp)

        assertEquals(listOf(WriteStroke(listOf(p(0), p(1)))), cursor.revealMore(26))
        assertTrue("nothing left to draw yet", cursor.caughtUp)
        assertEquals(emptyList<WriteStroke>(), cursor.revealMore(26))

        cursor.add(listOf(stroke(10..11)))
        assertFalse("a new word means work again", cursor.caughtUp)
        assertEquals(listOf(WriteStroke(listOf(p(10), p(11)))), cursor.revealMore(26))
        assertTrue(cursor.caughtUp)
        assertEquals(4, cursor.revealedPoints)
    }

    @Test
    fun addingNothingChangesNothing() {
        val cursor = cursorOver(stroke(0..1))
        cursor.revealMore(26)
        cursor.add(emptyList())
        assertTrue(cursor.caughtUp)
    }

    /**
     * Every point comes out exactly once as new ink, and no tick ever costs
     * more than its budget — which is what proves the cursor never restarts
     * its walk at stroke 0: a re-walk would re-emit the whole revealed prefix.
     */
    @Test
    fun everyPointIsEmittedAsNewInkExactlyOnceAndNeverInBulk() {
        val strokeCount = 60
        val pointsPerStroke = 37
        val strokes = (0 until strokeCount).map { s ->
            WriteStroke((0 until pointsPerStroke).map { p(s * pointsPerStroke + it) })
        }
        val cursor = ReplyRevealCursor()

        val emitted = mutableListOf<WritePoint>()
        // Handed over in batches, the way a stream delivers words.
        for (batch in strokes.chunked(7)) {
            cursor.add(batch)
            while (!cursor.caughtUp) {
                val segments = cursor.revealMore(26)
                assertTrue("a reveal before the end must produce ink", segments.isNotEmpty())
                val emittedNow = segments.sumOf { it.points.size }
                // The whole point of the cursor: a tick costs the budget plus at
                // most one overlap point per segment, never the revealed prefix.
                assertTrue(
                    "tick emitted $emittedNow points for a 26-point budget",
                    emittedNow <= 26 + segments.size,
                )
                for (segment in segments) emitted += segment.points
            }
        }
        assertEquals(emptyList<WriteStroke>(), cursor.revealMore(26))

        // Drop the repeated overlap points; what's left must be the reply, in order.
        val newInk = emitted.filterIndexed { i, point -> i == 0 || point != emitted[i - 1] }
        assertEquals(strokes.flatMap { it.points }, newInk)
    }
}
