package com.riddleboox.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PenStyleTest {
    @Test
    fun `no saved choice falls back to ballpoint`() {
        assertEquals(PenStyle.Ballpoint, PenStyle.fromStored(null))
    }

    @Test
    fun `an unrecognized saved value falls back to ballpoint`() {
        assertEquals(PenStyle.Ballpoint, PenStyle.fromStored("garbage"))
    }

    @Test
    fun `a saved choice is read back as itself`() {
        assertEquals(PenStyle.Brush, PenStyle.fromStored(PenStyle.Brush.name))
    }
}
