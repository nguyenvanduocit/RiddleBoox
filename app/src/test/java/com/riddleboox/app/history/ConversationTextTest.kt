package com.riddleboox.app.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTextTest {

    private fun turn(transcript: String, reply: String, timestampMs: Long = 1_700_000_000_000L) =
        StoredTurn(timestampMs = timestampMs, transcript = transcript, reply = reply)

    private fun conversation(vararg turns: StoredTurn) =
        StoredConversation(id = "evening-1", startedAtMs = 1_700_000_000_000L, turns = turns.toList())

    @Test
    fun `a single turn prints the writer's line then the diary's`() {
        val text = conversation(turn("Ten toi la Duoc", "Ta se nho.")).toPlainText()

        assertEquals("Bạn: Ten toi la Duoc\nNhật ký: Ta se nho.", text)
    }

    @Test
    fun `several turns keep their order and are separated by a blank line`() {
        val text = conversation(
            turn("Cau hoi mot", "Tra loi mot"),
            turn("Cau hoi hai", "Tra loi hai"),
            turn("Cau hoi ba", "Tra loi ba"),
        ).toPlainText()

        assertEquals(
            "Bạn: Cau hoi mot\nNhật ký: Tra loi mot\n\n" +
                "Bạn: Cau hoi hai\nNhật ký: Tra loi hai\n\n" +
                "Bạn: Cau hoi ba\nNhật ký: Tra loi ba",
            text,
        )
    }

    @Test
    fun `a blank reply is left out instead of printed as an empty line`() {
        val text = conversation(turn("Cau hoi", "")).toPlainText()

        assertEquals("Bạn: Cau hoi", text)
    }

    @Test
    fun `a blank transcript is left out instead of printed as an empty line`() {
        val text = conversation(turn("", "Tra loi")).toPlainText()

        assertEquals("Nhật ký: Tra loi", text)
    }

    @Test
    fun `a turn with only whitespace on both sides prints neither line`() {
        val text = conversation(turn("   ", "\n\t")).toPlainText()

        assertEquals("", text)
    }

    @Test
    fun `a conversation with no turns is empty text`() {
        val text = conversation().toPlainText()

        assertEquals("", text)
    }

    @Test
    fun `each turn keeps its own transcript and reply distinct from the next turn's`() {
        val text = conversation(
            turn("mot", "hai"),
            turn("ba", "bon"),
        ).toPlainText()

        assertTrue(text.contains("Bạn: mot"))
        assertTrue(text.contains("Nhật ký: hai"))
        assertTrue(text.contains("Bạn: ba"))
        assertTrue(text.contains("Nhật ký: bon"))
        // Turn boundaries stay distinguishable: the second turn's writer line
        // is not glued onto the first turn's diary line.
        assertFalse(text.contains("hai\nBạn: ba"))
    }
}
