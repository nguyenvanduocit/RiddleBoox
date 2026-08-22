package com.riddleboox.app.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySearchTest {

    private fun turn(transcript: String, reply: String, timestampMs: Long = 1_700_000_000_000L) =
        StoredTurn(timestampMs = timestampMs, transcript = transcript, reply = reply)

    private fun conversation(id: String, vararg turns: StoredTurn) =
        StoredConversation(id = id, startedAtMs = 1_700_000_000_000L, turns = turns.toList())

    @Test
    fun `a blank query hands back the whole list unchanged`() {
        val all = listOf(
            conversation("a", turn("Cau chuyen", "Tra loi")),
            conversation("b", turn("Khac", "Cung khac")),
        )

        assertEquals(all, all.matching(""))
        assertEquals(all, all.matching("   "))
    }

    @Test
    fun `a query matching the title finds the conversation, ignoring case`() {
        val target = conversation("a", turn("Hom nay troi mua", "Uh nhi"))
        val other = conversation("b", turn("Khac han", "Vang"))

        val result = listOf(target, other).matching("HOM NAY")

        assertEquals(listOf(target), result)
    }

    @Test
    fun `a query matching a turn's transcript or reply finds the conversation even off-title`() {
        val target = conversation("a", turn("mo dau", "co mot bi mat o day"))
        val other = conversation("b", turn("khong lien quan", "gi ca"))

        val result = listOf(target, other).matching("bi mat")

        assertEquals(listOf(target), result)
    }

    @Test
    fun `an unaccented query still finds Vietnamese diacritics via fold`() {
        val target = conversation("a", turn("Câu chuyện", "Kể tiếp đi"))

        val result = listOf(target).matching("cau chuyen")

        assertEquals(listOf(target), result)
    }

    @Test
    fun `a query matching nothing returns an empty list`() {
        val all = listOf(
            conversation("a", turn("Cau chuyen", "Tra loi")),
            conversation("b", turn("Khac", "Cung khac")),
        )

        assertTrue(all.matching("khong ton tai o dau ca").isEmpty())
    }

    @Test
    fun `several matches keep the order they were given in`() {
        val newer = conversation("newer", turn("dieu gi do", "chua tim thay"))
        val middle = conversation("middle", turn("khong lien quan", "gi ca"))
        val older = conversation("older", turn("dieu gi do khac", "cung chua tim thay"))

        val result = listOf(newer, middle, older).matching("dieu gi do")

        assertEquals(listOf(newer, older), result)
    }
}
