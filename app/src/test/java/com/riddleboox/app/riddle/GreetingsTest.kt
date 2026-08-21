package com.riddleboox.app.riddle

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingsTest {

    @Test
    fun `there are several, all distinct, none empty`() {
        assertTrue(GREETINGS.size >= 5)
        assertEquals(GREETINGS.size, GREETINGS.toSet().size)
        assertTrue(GREETINGS.all { it.isNotBlank() && it == it.trim() })
    }

    /** It shares the page with whatever gets written under it. */
    @Test
    fun `each one is short enough to leave the page usable`() {
        assertTrue(GREETINGS.all { it.length <= 60 })
    }

    @Test
    fun `a greeting always comes from the list`() {
        repeat(50) { assertTrue(greeting() in GREETINGS) }
    }

    @Test
    fun `pressing reset twice does not draw the same line again`() {
        var previous: String? = null
        repeat(50) {
            val next = greeting(previous)
            assertNotEquals(previous, next)
            previous = next
        }
    }

    @Test
    fun `the same seed gives the same greeting`() {
        assertEquals(greeting(random = Random(7)), greeting(random = Random(7)))
    }

    /** Over enough draws it should reach for more than one or two of them. */
    @Test
    fun `the choice actually varies`() {
        val seen = (1..200).map { greeting(random = Random(it)) }.toSet()

        assertTrue("chỉ thấy ${seen.size} câu", seen.size >= GREETINGS.size / 2)
    }

    @Test
    fun `a previous line that is not one of ours excludes nothing`() {
        assertTrue(greeting("một câu lạ") in GREETINGS)
    }

    @Test
    fun `a selected agent supplies the greeting pool`() {
        val agentGreetings = listOf("Câu riêng một.", "Câu riêng hai.")

        repeat(50) {
            assertTrue(greeting(agentGreetings) in agentGreetings)
        }
        assertTrue(greeting(agentGreetings, previous = agentGreetings.first()) == agentGreetings.last())
    }
}
