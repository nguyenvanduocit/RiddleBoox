package com.riddleboox.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

class MemoryTextTest {

    private fun entry(content: String, ms: Long = 1_700_000_000_000L, id: String = "abc12345") =
        MemoryEntry(id = id, ms = ms, conversation = "evening-1", content = content)

    private val dayAndTime = SimpleDateFormat("d/M/yyyy · HH:mm", Locale.getDefault())

    @Test
    fun `an empty list is empty text`() {
        assertEquals("", emptyList<MemoryEntry>().toPlainText())
    }

    @Test
    fun `a single memory prints its day and its content`() {
        val ms = 1_700_000_000_000L
        val text = listOf(entry("Thích trà xanh, không thích cà phê.", ms = ms)).toPlainText()

        assertEquals(dayAndTime.format(Date(ms)) + "\n\nThích trà xanh, không thích cà phê.", text)
    }

    @Test
    fun `several memories are separated and keep the order they were given in`() {
        // ms is deliberately out of order with the list position: this
        // function must not re-sort — it prints whatever order it is handed.
        val newer = entry("Nho hai", ms = 2_000L, id = "second")
        val older = entry("Nho mot", ms = 1_000L, id = "first")

        val text = listOf(newer, older).toPlainText()

        assertTrue(text.contains(dayAndTime.format(Date(2_000L))))
        assertTrue(text.contains(dayAndTime.format(Date(1_000L))))
        val separator = "=".repeat(40)
        assertTrue(text.contains(separator))
        val newerIndex = text.indexOf("Nho hai")
        val olderIndex = text.indexOf("Nho mot")
        assertTrue(newerIndex >= 0 && olderIndex >= 0 && newerIndex < olderIndex)
    }

    // ---- the memorize note ----

    /**
     * Every memory rides along in full, in the same `#id · day — fact` shape
     * `recall_memories` prints, so `forget_memory` can be called straight off
     * the note without a recall round first.
     */
    @Test
    fun `the memorize note carries every memory with its id and day`() {
        val note = memorizeInstruction(
            listOf(
                entry("Nho mot", ms = 1_700_000_000_000L, id = "first111"),
                entry("Nho hai", ms = 1_700_000_000_000L, id = "second22"),
            ),
            zone = ZoneId.of("UTC"),
        )

        assertTrue(note.contains("#first111 · 2023-11-14 — Nho mot"))
        assertTrue(note.contains("#second22 · 2023-11-14 — Nho hai"))
        assertTrue("the note must name both tools", note.contains("`remember`") && note.contains("`forget_memory`"))
        assertTrue("correcting is spelled out as forget-then-remember", note.contains("forget it and"))
    }

    @Test
    fun `the memorize note says when nothing is held`() {
        val note = memorizeInstruction(emptyList())

        assertTrue(note.contains("Nothing is held yet."))
        assertTrue("the ask itself does not change", note.contains("put your kept memories in order"))
    }
}
