package com.riddleboox.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyFontSizeTest {
    @Test
    fun `no saved choice falls back to medium`() {
        assertEquals(ReplyFontSize.Medium, ReplyFontSize.fromStored(null))
    }

    @Test
    fun `an unrecognized saved value falls back to medium`() {
        assertEquals(ReplyFontSize.Medium, ReplyFontSize.fromStored("garbage"))
    }

    @Test
    fun `a saved choice is read back as itself`() {
        assertEquals(ReplyFontSize.ExtraLarge, ReplyFontSize.fromStored(ReplyFontSize.ExtraLarge.name))
    }
}
