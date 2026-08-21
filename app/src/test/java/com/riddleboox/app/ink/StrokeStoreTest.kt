package com.riddleboox.app.ink

import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeStoreTest {

    private fun point(x: Float) = InkPoint(x, 0f, 0.5f)

    @Test
    fun `a finished stroke lands in the page`() {
        val store = StrokeStore()
        store.beginStroke(point(0f))
        store.addPoint(point(1f))
        store.finishCurrent()

        assertEquals(1, store.count)
        assertEquals(2, store.strokes[0].points.size)
    }

    /**
     * The page is cleared at commit and at reset, both of which can land while
     * the pen is still down — a half-drawn stroke that survives would be
     * finished into the *next* turn's page, sending the diary a line the writer
     * had already watched dissolve.
     */
    @Test
    fun `clearing drops the stroke still being drawn`() {
        val store = StrokeStore()
        store.beginStroke(point(0f))
        store.addPoint(point(1f))

        store.clear()
        store.finishCurrent()

        assertEquals(0, store.count)
    }
}
