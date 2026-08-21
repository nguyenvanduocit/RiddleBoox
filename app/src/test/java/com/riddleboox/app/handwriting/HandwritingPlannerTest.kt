package com.riddleboox.app.handwriting

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The planner is the one-shot face of [WriteCursor] — where a reply lands is
 * that class's business and tested there; what matters here is that going
 * through the planner puts it in the same place.
 */
class HandwritingPlannerTest {

    private fun planner() = HandwritingPlanner(FakeRaster(), Random(7))

    @Test
    fun `planning text at once matches writing it with a cursor`() {
        val text = "aaaa bbbb cccc"
        val plan = planner().plan(
            text = text,
            pageWidthPx = 300,
            lineHeightPx = 120f,
            jitterPx = 0f,
            marginXPx = 120f,
        )
        val written = planner().cursor(
            pageWidthPx = 300,
            lineHeightPx = 120f,
            jitterPx = 0f,
            marginXPx = 120f,
        ).apply { append(text) }.plan()

        assertEquals(written.strokes, plan.strokes)
    }

    @Test
    fun `blank text plans nothing`() {
        assertEquals(emptyList<WriteStroke>(), planner().plan("   ", pageWidthPx = 300).strokes)
    }

    @Test
    fun `startY offsets the whole plan`() {
        val plan = planner().plan("aaaa", pageWidthPx = 300, jitterPx = 0f, startYPx = 500f)
        assertEquals(1, plan.strokes.size)
        assertEquals(502f, plan.strokes[0].points.first().y, 0f)
    }
}
