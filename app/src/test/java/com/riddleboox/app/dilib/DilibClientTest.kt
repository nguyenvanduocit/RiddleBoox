package com.riddleboox.app.dilib

import org.junit.Assert.assertEquals
import org.junit.Test

class DilibClientTest {

    private val client = DilibClient()

    @Test
    fun `uniqueName returns the requested name when nothing has it`() {
        assertEquals("book.epub", client.uniqueName({ false }, "book.epub"))
    }

    @Test
    fun `uniqueName appends a counter when the name is taken`() {
        val taken = setOf("book.epub", "book (2).epub")
        assertEquals("book (3).epub", client.uniqueName({ it in taken }, "book.epub"))
    }

    @Test
    fun `uniqueName keeps the extension when appending a counter`() {
        assertEquals("notes (2)", client.uniqueName({ it == "notes" }, "notes"))
    }
}
