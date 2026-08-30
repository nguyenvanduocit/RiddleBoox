package com.riddleboox.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PenStrokeWidthTest {
    @Test
    fun `no saved choice falls back to medium`() {
        assertEquals(PenStrokeWidth.Medium, PenStrokeWidth.fromStored(null))
    }

    @Test
    fun `an unrecognized saved value falls back to medium`() {
        assertEquals(PenStrokeWidth.Medium, PenStrokeWidth.fromStored("garbage"))
    }

    @Test
    fun `a saved choice is read back as itself`() {
        assertEquals(PenStrokeWidth.Thick, PenStrokeWidth.fromStored(PenStrokeWidth.Thick.name))
    }
}
