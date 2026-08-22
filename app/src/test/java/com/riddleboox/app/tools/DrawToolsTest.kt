package com.riddleboox.app.tools

import com.riddleboox.app.handwriting.SvgFigure
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawToolsTest {

    private fun args(svg: String) = JsonObject(mapOf("svg" to JsonPrimitive(svg)))

    @Test
    fun `a parsed figure reaches the open board and the model is told to move on`() = runBlocking {
        val board = DrawingBoard()
        var received: SvgFigure? = null
        board.open { received = it }

        val answer = DrawTools(board).call(
            "draw",
            args("""<svg viewBox="0 0 20 10"><line x1="0" y1="0" x2="20" y2="10"/></svg>"""),
        )

        assertTrue(answer.contains("being drawn"))
        assertEquals(20f, received!!.width)
        assertEquals(10f, received!!.height)
    }

    @Test
    fun `bad markup comes back as words the model can act on, not an exception`() = runBlocking {
        val board = DrawingBoard().apply { open { } }
        val answer = DrawTools(board).call("draw", args("<svg><text>chỉ có chữ</text></svg>"))

        assertTrue(answer.contains("could not be read"))
        assertTrue("names the missing geometry", answer.contains("no drawable geometry"))
        assertTrue("invites a retry", answer.contains("call draw again"))
    }

    @Test
    fun `a closed board refuses the figure in words`() = runBlocking {
        val board = DrawingBoard()
        var received: SvgFigure? = null
        board.open { received = it }
        board.close()

        val answer = DrawTools(board).call(
            "draw",
            args("""<svg><line x1="0" y1="0" x2="5" y2="5"/></svg>"""),
        )

        assertNull("nothing crossed a closed board", received)
        assertTrue(answer.contains("not taking drawings"))
    }

    @Test
    fun `the note reads as drawing, whatever the arguments hold`() {
        assertEquals("đang vẽ lên trang…", DrawTools(DrawingBoard()).note("draw", JsonObject(emptyMap())))
    }

    @Test
    fun `an unknown tool name is an answer, not a crash`() = runBlocking {
        val answer = DrawTools(DrawingBoard()).call("paint", JsonObject(emptyMap()))
        assertTrue(answer.contains("paint"))
    }
}
