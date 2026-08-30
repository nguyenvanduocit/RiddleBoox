package com.riddleboox.app.riddle

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MissingKeyTest {

    @Test
    fun `every line sends the writer to settings for a key`() {
        MISSING_KEY_LINES.forEach { line ->
            assertTrue("\"$line\" names Settings", line.contains("Settings"))
            assertTrue("\"$line\" names the key", line.contains("key", ignoreCase = true))
        }
    }

    @Test
    fun `two unanswerable pages in a row do not get the same line`() {
        var previous: String? = null
        val random = Random(7)
        repeat(50) {
            val line = missingKeyLine(previous, random)
            assertNotEquals("the line repeats itself back to back", previous, line)
            previous = line
        }
    }

    @Test
    fun `the whole set is reachable`() {
        val random = Random(1)
        val seen = mutableSetOf<String>()
        var previous: String? = null
        repeat(200) {
            val line = missingKeyLine(previous, random)
            seen.add(line)
            previous = line
        }
        assertTrue("every line can come up", seen.containsAll(MISSING_KEY_LINES))
    }
}
