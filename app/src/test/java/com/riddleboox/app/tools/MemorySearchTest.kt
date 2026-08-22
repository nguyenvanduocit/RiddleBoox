package com.riddleboox.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySearchTest {

    private fun entry(id: String, content: String, ms: Long = 1_700_000_000_000L) =
        MemoryEntry(id = id, ms = ms, conversation = "evening-1", content = content)

    @Test
    fun `a blank query hands back the whole list unchanged`() {
        val all = listOf(
            entry("a", "Thich tra xanh"),
            entry("b", "Khac han"),
        )

        assertEquals(all, all.matching(""))
        assertEquals(all, all.matching("   "))
    }

    @Test
    fun `an unaccented query still finds Vietnamese diacritics via fold`() {
        val target = entry("a", "Thích trà xanh, không thích cà phê.")

        val result = listOf(target).matching("tra xanh")

        assertEquals(listOf(target), result)
    }

    @Test
    fun `a query matching nothing returns an empty list`() {
        val all = listOf(
            entry("a", "Thich tra xanh"),
            entry("b", "Khac han"),
        )

        assertTrue(all.matching("khong ton tai o dau ca").isEmpty())
    }

    @Test
    fun `several matches keep the order they were given in`() {
        val first = entry("a", "dieu gi do")
        val middle = entry("b", "khong lien quan")
        val last = entry("c", "dieu gi do khac")

        val result = listOf(first, middle, last).matching("dieu gi do")

        assertEquals(listOf(first, last), result)
    }
}
