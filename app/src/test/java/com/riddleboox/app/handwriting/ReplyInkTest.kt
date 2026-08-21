package com.riddleboox.app.handwriting

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyInkTest {
    @Test
    fun `matches the old fixed 4px at the default 64px glyph height`() {
        assertEquals(4f, replyStrokeWidthPx(64f), 0f)
    }

    @Test
    fun `scales down with the glyph but never below the panel's 4px floor`() {
        assertEquals(4f, replyStrokeWidthPx(48f), 0f)
    }

    @Test
    fun `scales up with a larger glyph so the loops don't fill in`() {
        assertEquals(6f, replyStrokeWidthPx(96f), 0f)
    }
}
