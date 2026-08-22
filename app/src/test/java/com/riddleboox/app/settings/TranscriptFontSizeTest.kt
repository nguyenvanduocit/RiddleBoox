package com.riddleboox.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptFontSizeTest {
    @Test
    fun `no saved choice falls back to medium`() {
        assertEquals(TranscriptFontSize.Medium, TranscriptFontSize.fromStored(null))
    }

    @Test
    fun `an unrecognized saved value falls back to medium`() {
        assertEquals(TranscriptFontSize.Medium, TranscriptFontSize.fromStored("khong-ton-tai"))
    }

    @Test
    fun `a saved choice is read back as itself`() {
        assertEquals(TranscriptFontSize.Large, TranscriptFontSize.fromStored(TranscriptFontSize.Large.name))
    }
}
