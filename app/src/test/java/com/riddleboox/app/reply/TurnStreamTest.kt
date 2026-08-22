package com.riddleboox.app.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnStreamTest {

    /** Feeds [deltas] through a stream and returns what reached the page, in order. */
    private fun run(vararg deltas: String): Pair<String, DiaryTurn> {
        val stream = TurnStream()
        val onPage = StringBuilder()
        deltas.forEach { onPage.append(stream.accept(it)) }
        return onPage.toString() to stream.finish()
    }

    @Test
    fun `the reply reaches the page and the transcript does not`() {
        val (onPage, turn) = run("Ta sẽ nhớ.", "\n---\n", "Tên tôi là Dược")

        assertEquals("Ta sẽ nhớ.", onPage)
        assertEquals("Ta sẽ nhớ.", turn.reply)
        assertEquals("Tên tôi là Dược", turn.transcript)
    }

    @Test
    fun `the reply arrives piece by piece, in order`() {
        val (onPage, turn) = run("Ta ", "sẽ ", "nhớ", ".", "\n---\nTên tôi là Dược")

        assertEquals("Ta sẽ nhớ.", onPage)
        assertEquals("Tên tôi là Dược", turn.transcript)
    }

    /**
     * The separator can be split across deltas anywhere. A dash written to the
     * page cannot be taken back, so a suffix that might still become one is
     * held until the next delta decides.
     */
    @Test
    fun `a separator split across deltas never leaks a dash onto the page`() {
        val (onPage, turn) = run("Ta sẽ nhớ.", "\n-", "-", "-", "\n", "Tên tôi là Dược")

        assertEquals("Ta sẽ nhớ.", onPage)
        assertEquals("Tên tôi là Dược", turn.transcript)
    }

    @Test
    fun `the whole turn in one delta splits correctly`() {
        val (onPage, turn) = run("Ta sẽ nhớ.\n---\nTên tôi là Dược")

        assertEquals("Ta sẽ nhớ.", onPage)
        assertEquals("Ta sẽ nhớ.", turn.reply)
        assertEquals("Tên tôi là Dược", turn.transcript)
    }

    /** A dash that turned out to be prose is still owed to the page. */
    @Test
    fun `held text that was never a separator is released at the end`() {
        val (onPage, turn) = run("Một danh hiệu", "\n-", " đầy tham vọng.")

        assertEquals("Một danh hiệu\n- đầy tham vọng.", onPage)
        assertEquals("Một danh hiệu\n- đầy tham vọng.", turn.reply)
        assertEquals("", turn.transcript)
    }

    @Test
    fun `a turn that ends mid-separator keeps those characters as reply`() {
        val (onPage, turn) = run("Ta sẽ nhớ.", "\n-")

        assertEquals("Ta sẽ nhớ.", onPage)
        assertEquals("Ta sẽ nhớ.\n-", turn.reply)
        assertEquals("", turn.transcript)
    }

    @Test
    fun `a dash inside a sentence is not a separator`() {
        val (onPage, _) = run("Một danh hiệu — đầy tham vọng, ", "phải không?")

        assertEquals("Một danh hiệu — đầy tham vọng, phải không?", onPage)
    }

    @Test
    fun `a transcript spanning several deltas is joined`() {
        val (_, turn) = run("Xong.", "\n---\n", "Dòng một\n", "Dòng hai")

        assertEquals("Dòng một\nDòng hai", turn.transcript)
    }

    @Test
    fun `an empty transcript is empty, not missing`() {
        val (_, turn) = run("Mực đã nhoè.", "\n---\n")

        assertEquals("", turn.transcript)
        assertEquals("Mực đã nhoè.", turn.reply)
    }

    // ---- a turn whose whole visible answer is a drawing ----

    @Test
    fun `a separator opening the stream leaves the page clean and the contract satisfied`() {
        val (onPage, turn) = run("---\n", "Vẽ một ngôi sao")

        assertEquals("nothing reaches the page", "", onPage)
        assertEquals("", turn.reply)
        assertEquals("Vẽ một ngôi sao", turn.transcript)
        assertTrue("the contract was met, no repair pass is owed", turn.contractSatisfied)
    }

    @Test
    fun `a stream-opening separator split across deltas is still one separator`() {
        val (onPage, turn) = run("-", "-", "-", "\nVẽ đi")

        assertEquals("", onPage)
        assertEquals("Vẽ đi", turn.transcript)
        assertTrue(turn.contractSatisfied)
    }

    @Test
    fun `a reply that merely starts with a dash is not swallowed`() {
        val (onPage, turn) = run("- một", " gạch đầu dòng", "\n---\nTrang")

        assertEquals("- một gạch đầu dòng", onPage)
        assertEquals("- một gạch đầu dòng", turn.reply)
        assertEquals("Trang", turn.transcript)
    }

    @Test
    fun `dashes mid-reply without a newline stay ink`() {
        val (onPage, turn) = run("A --- B", "\n---\nTrang")

        assertEquals("A --- B", onPage)
        assertEquals("Trang", turn.transcript)
    }
}
