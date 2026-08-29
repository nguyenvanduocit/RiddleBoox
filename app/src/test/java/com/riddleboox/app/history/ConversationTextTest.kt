package com.riddleboox.app.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationTextTest {

    private fun turn(transcript: String, reply: String, timestampMs: Long = 1_700_000_000_000L) =
        StoredTurn(timestampMs = timestampMs, transcript = transcript, reply = reply)

    private fun conversation(vararg turns: StoredTurn) =
        StoredConversation(id = "evening-1", startedAtMs = 1_700_000_000_000L, turns = turns.toList())

    @Test
    fun `a single turn prints the writer's line then the diary's`() {
        val text = conversation(turn("Ten toi la Duoc", "Ta se nho.")).toPlainText()

        assertEquals("You: Ten toi la Duoc\nDiary: Ta se nho.", text)
    }

    @Test
    fun `several turns keep their order and are separated by a blank line`() {
        val text = conversation(
            turn("Cau hoi mot", "Tra loi mot"),
            turn("Cau hoi hai", "Tra loi hai"),
            turn("Cau hoi ba", "Tra loi ba"),
        ).toPlainText()

        assertEquals(
            "You: Cau hoi mot\nDiary: Tra loi mot\n\n" +
                "You: Cau hoi hai\nDiary: Tra loi hai\n\n" +
                "You: Cau hoi ba\nDiary: Tra loi ba",
            text,
        )
    }

    @Test
    fun `a blank reply is left out instead of printed as an empty line`() {
        val text = conversation(turn("Cau hoi", "")).toPlainText()

        assertEquals("You: Cau hoi", text)
    }

    @Test
    fun `a blank transcript is left out instead of printed as an empty line`() {
        val text = conversation(turn("", "Tra loi")).toPlainText()

        assertEquals("Diary: Tra loi", text)
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

        assertTrue(text.contains("You: mot"))
        assertTrue(text.contains("Diary: hai"))
        assertTrue(text.contains("You: ba"))
        assertTrue(text.contains("Diary: bon"))
        // Turn boundaries stay distinguishable: the second turn's writer line
        // is not glued onto the first turn's diary line.
        assertFalse(text.contains("hai\nYou: ba"))
    }

    private val dayAndTime = SimpleDateFormat("d/M/yyyy · HH:mm", Locale.getDefault())

    @Test
    fun `all-history export of an empty list is empty text`() {
        assertEquals("", emptyList<StoredConversation>().toPlainText())
    }

    @Test
    fun `a single conversation is headed by its updatedAtMs, not its startedAtMs`() {
        // Deliberately far apart and on different days, so a code path that
        // reads startedAtMs instead of updatedAtMs prints a date this test can
        // actually catch, not one that happens to format the same.
        val startedAtMs = 1_600_000_000_000L
        val updatedAtMs = 1_700_000_000_000L
        val convo = StoredConversation(
            id = "evening-1",
            startedAtMs = startedAtMs,
            turns = listOf(StoredTurn(timestampMs = updatedAtMs, transcript = "Cau hoi", reply = "Tra loi")),
        )

        val text = listOf(convo).toPlainText()

        val correctHeading = dayAndTime.format(Date(updatedAtMs))
        val wrongHeading = dayAndTime.format(Date(startedAtMs))
        assertTrue(text.contains(correctHeading))
        assertFalse(text.contains(wrongHeading))
        assertTrue(text.contains("You: Cau hoi"))
        assertTrue(text.contains("Diary: Tra loi"))
    }

    @Test
    fun `several conversations are separated and keep the order they were given in`() {
        // updatedAtMs is later than startedAtMs (the store's list() order), but
        // this function must not re-sort — it prints whatever order it is handed.
        val newer = StoredConversation(
            id = "newer",
            startedAtMs = 2_000L,
            turns = listOf(StoredTurn(timestampMs = 2_000L, transcript = "Cau hoi B", reply = "Tra loi B")),
        )
        val older = StoredConversation(
            id = "older",
            startedAtMs = 1_000L,
            turns = listOf(StoredTurn(timestampMs = 1_000L, transcript = "Cau hoi A", reply = "Tra loi A")),
        )

        val text = listOf(newer, older).toPlainText()

        assertTrue(text.contains(CONVERSATION_SEPARATOR))
        val newerIndex = text.indexOf("Cau hoi B")
        val olderIndex = text.indexOf("Cau hoi A")
        assertTrue(newerIndex >= 0 && olderIndex >= 0 && newerIndex < olderIndex)
    }
}
