package com.riddleboox.app.riddle

import com.riddleboox.app.handwriting.HandwritingPlanner
import com.riddleboox.app.handwriting.WritePoint
import com.riddleboox.app.handwriting.WriteStroke
import com.riddleboox.app.ink.InkPoint
import com.riddleboox.app.ink.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The arithmetic behind every refresh the panel is asked for, and behind where
 * a reply lands on the page.
 *
 * It lived inside [RiddleStateMachine] until it was moved here, which is to say
 * it could not be checked at all: that class builds a `Handler` in its
 * constructor and paints through a `View`, so a JVM test cannot make one. The
 * bug in `replyStartBelow` this file's last three cases pin down survived
 * exactly that long.
 */
class PageGeometryTest {

    /** The Note Air 2's drawing area, which is the page all of this is measured against. */
    private val pageHeightPx = 1872

    private fun ink(vararg xy: Pair<Float, Float>) =
        InkStroke(xy.map { InkPoint(it.first, it.second, 0.5f) }.toMutableList())

    private fun written(vararg xy: Pair<Float, Float>) =
        WriteStroke(xy.map { WritePoint(it.first, it.second) })

    @Test
    fun `a page with no ink on it has no bounds to redraw`() {
        assertNull(inkBounds(emptyList()))
        assertNull(inkBounds(listOf(InkStroke())))
        assertNull(writeBounds(emptyList()))
    }

    @Test
    fun `ink bounds cover every stroke, padded so antialiased edges do not clip`() {
        val bounds = inkBounds(listOf(ink(100f to 200f, 150f to 260f), ink(80f to 240f)))
        assertEquals(PageRect(80 - 24, 200 - 24, 150 + 24, 260 + 24), bounds)
    }

    @Test
    fun `reply bounds are measured the same way as the writer's own`() {
        val bounds = writeBounds(listOf(written(10f to 20f), written(90f to 5f)))
        assertEquals(PageRect(10 - 24, 5 - 24, 90 + 24, 20 + 24), bounds)
    }

    @Test
    fun `a blot always asks for the same small square, wherever it sits`() {
        assertEquals(PageRect(480, 680, 520, 720), blotRect(WritePoint(500f, 700f)))
        assertEquals(PageRect(-20, -20, 20, 20), blotRect(WritePoint(0f, 0f)))
    }

    @Test
    fun `two dirty areas redraw as the one that covers both`() {
        val a = PageRect(10, 10, 20, 20)
        val b = PageRect(15, 5, 40, 12)
        assertEquals(PageRect(10, 5, 40, 20), union(a, b))
        assertEquals(a, union(a, null))
        assertEquals(b, union(null, b))
        assertNull(union(null, null))
    }

    @Test
    fun `the foot of the ink is the lowest point any stroke reaches`() {
        assertEquals(0f, bottomOf(emptyList()), 0f)
        assertEquals(260f, bottomOf(listOf(ink(0f to 100f, 0f to 260f), ink(0f to 40f))), 0f)
    }

    @Test
    fun `a reply goes underneath the line it answers`() {
        val start = replyStartBelow(listOf(ink(0f to 500f)), pageHeightPx)
        assertEquals(500f + REPLY_GAP_PX, start, 0f)
    }

    @Test
    fun `a reply to writing at the very top still starts where a reply starts`() {
        val start = replyStartBelow(listOf(ink(0f to 10f)), pageHeightPx)
        assertEquals(REPLY_TOP_PX, start, 0f)
    }

    /**
     * The bug: an unclamped start meant a writer who filled the page to the
     * foot got an excuse written below the bottom edge, one full-screen flash
     * per line, because the pen was full from its very first line.
     */
    @Test
    fun `a reply under writing that fills the page still has a line to be written on`() {
        val start = replyStartBelow(listOf(ink(0f to 1800f)), pageHeightPx)
        val lowestThePenAccepts =
            pageHeightPx - REPLY_BOTTOM_PX - HandwritingPlanner.DEFAULT_LINE_HEIGHT_PX * 2
        assertEquals(lowestThePenAccepts, start, 0f)
    }

    @Test
    fun `a page that has not been measured yet is written on from the top`() {
        assertEquals(REPLY_TOP_PX, replyStartBelow(listOf(ink(0f to 1800f)), pageHeightPx = 0), 0f)
    }

    /**
     * A bigger reply glyph means a taller [HandwritingPlanner.DEFAULT_LINE_HEIGHT_PX]-equivalent
     * gap, so the clamp has to give up more of the foot of the page than the default does.
     */
    @Test
    fun `a larger reply glyph tightens how close to the foot a reply may still start`() {
        val biggerLineHeight = HandwritingPlanner.DEFAULT_LINE_HEIGHT_PX * 1.5f
        val start = replyStartBelow(listOf(ink(0f to 1800f)), pageHeightPx, lineHeightPx = biggerLineHeight)
        val lowestThePenAccepts = pageHeightPx - REPLY_BOTTOM_PX - biggerLineHeight * 2
        assertEquals(lowestThePenAccepts, start, 0f)
    }
}
