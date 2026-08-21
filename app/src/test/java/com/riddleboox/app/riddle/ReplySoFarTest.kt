package com.riddleboox.app.riddle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySoFarTest {

    @Test
    fun `nothing written is nothing seen`() {
        val reply = ReplySoFar()
        assertTrue(reply.isEmpty)
        assertEquals("", reply.whole)
        assertEquals("", reply.seen(revealedStrokes = 50))
    }

    /**
     * The whole point of the class: the model is chapters ahead of the pen, and
     * only what the pen has drawn counts as something the writer was told.
     */
    @Test
    fun `text laid out beyond the pen has not been seen`() {
        val reply = ReplySoFar()
        reply.append("Ta nhớ ", strokesLaidOut = 8)
        reply.append("căn phòng ấy. ", strokesLaidOut = 20)
        reply.append("Nó vẫn ở đó.", strokesLaidOut = 33)

        assertEquals("Ta nhớ căn phòng ấy. Nó vẫn ở đó.", reply.whole)
        assertEquals("", reply.seen(revealedStrokes = 3))
        assertEquals("Ta nhớ ", reply.seen(revealedStrokes = 8))
        assertEquals("Ta nhớ ", reply.seen(revealedStrokes = 19))
        assertEquals("Ta nhớ căn phòng ấy. ", reply.seen(revealedStrokes = 20))
        assertEquals(reply.whole, reply.seen(revealedStrokes = 33))
    }

    /** A pen past the end has still only ever seen everything, not more. */
    @Test
    fun `a pen past the last stroke sees all of it`() {
        val reply = ReplySoFar()
        reply.append("xong.", strokesLaidOut = 5)
        assertEquals("xong.", reply.seen(revealedStrokes = 4_000))
    }

    @Test
    fun `a reset leaves nothing of the previous turn`() {
        val reply = ReplySoFar()
        reply.append("lượt cũ", strokesLaidOut = 9)
        reply.reset()

        assertTrue(reply.isEmpty)
        assertEquals("", reply.seen(revealedStrokes = 9))

        reply.append("lượt mới", strokesLaidOut = 4)
        assertEquals("lượt mới", reply.seen(revealedStrokes = 4))
    }
}
