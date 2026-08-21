package com.riddleboox.app.riddle

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rectangle the diary reasons about, kept honest against the framework one
 * it replaces: the panel is driven by these four numbers, and a test that
 * cannot run off the device is what let them go unchecked until now.
 */
class PageRectTest {

    @Test
    fun `a rect knows how much of the page it covers`() {
        val rect = PageRect(10, 20, 110, 220)
        assertEquals(100, rect.width)
        assertEquals(200, rect.height)
    }

    /**
     * Halves floor rather than round, which is what `android.graphics.Rect`
     * does with its own `shr 1` — the blot centred by this has been landing on
     * those pixels since the port.
     */
    @Test
    fun `the centre of an odd-width page falls on the pixel below the middle`() {
        val page = PageRect(0, 0, 1403, 1871)
        assertEquals(701, page.centreX)
        assertEquals(935, page.centreY)
    }

    @Test
    fun `a rect covering another swallows it whole`() {
        val outer = PageRect(0, 0, 100, 100)
        val inner = PageRect(20, 20, 40, 40)
        assertEquals(outer, outer.union(inner))
        assertEquals(outer, inner.union(outer))
    }

    @Test
    fun `two rects that miss each other still redraw as one area between them`() {
        val left = PageRect(0, 0, 10, 10)
        val right = PageRect(90, 90, 100, 100)
        assertEquals(PageRect(0, 0, 100, 100), left.union(right))
    }
}
