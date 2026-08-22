package com.riddleboox.app.reply

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyCutTest {

    /** What the pen would be handed now, for readability in the assertions. */
    private fun writable(pending: String) = pending.take(writableCut(pending))

    @Test
    fun `a word without whitespace after it is still growing`() {
        assertEquals("", writable("Ch"))
        assertEquals("", writable("Chào"))
    }

    @Test
    fun `whitespace settles everything before it`() {
        assertEquals("Chào ", writable("Chào "))
        assertEquals("Chào bạn ", writable("Chào bạn hi"))
    }

    @Test
    fun `a newline settles a word as surely as a space`() {
        assertEquals("Chào\n", writable("Chào\nbạn"))
    }

    @Test
    fun `an unclosed emphasis mark holds back everything from itself onward`() {
        // "**Chúa " could still become bold; written now it is two asterisks.
        assertEquals("Ta là ", writable("Ta là **Chúa tể "))
    }

    @Test
    fun `a closed emphasis mark lets the words through`() {
        assertEquals("Ta là **Chúa tể** ", writable("Ta là **Chúa tể** bóng"))
    }

    @Test
    fun `the earliest unclosed mark wins, closed ones before it do not`() {
        assertEquals("a _b_ ", writable("a _b_ `c d "))
    }

    /**
     * `*` is checked before `_` (MARK_CHARS' order), so this only passes if an
     * unclosed `_` found second can still overtake an unclosed `*` found first —
     * the earliest position across mark kinds wins, not the first kind checked.
     */
    @Test
    fun `an earlier unclosed mark of a different kind overtakes one already found`() {
        assertEquals("x ", writable("x _y *z "))
    }

    /** A mark can open mid-word, and half a word must never reach the page. */
    @Test
    fun `an unclosed mark inside a word holds back the whole word`() {
        assertEquals("Ta ", writable("Ta là**Chúa tể "))
    }

    @Test
    fun `emphasis inside a settled run is counted by runs, not characters`() {
        // Four asterisks, two runs, both closed.
        assertEquals("**a** ", writable("**a** b"))
    }

    @Test
    fun `nothing settled yet cuts nothing`() {
        assertEquals(0, writableCut(""))
        assertEquals(0, writableCut("word"))
    }

    @Test
    fun `leading whitespace alone is writable`() {
        assertEquals(" ", writable(" x"))
    }

    // ---- inline figures ----

    @Test
    fun `an open svg block settles the words before it and holds itself`() {
        assertEquals("Đây là ", writable("""Đây là <svg viewBox="0 0"""))
    }

    @Test
    fun `an svg block butting against a word holds the whole word`() {
        assertEquals(0, writableCut("hình:<svg x"))
    }

    @Test
    fun `a completed block is found with its exact bounds, whatever the case`() {
        val text = "a <SVG>b</Svg> c"
        val block = completedSvgBlock(text)!!
        assertEquals("<SVG>b</Svg>", text.substring(block.first, block.last + 1))
    }

    @Test
    fun `an unclosed block is not yet a block`() {
        assertEquals(null, completedSvgBlock("a <svg>b</sv"))
    }

    @Test
    fun `stripping removes every completed block and keeps the words`() {
        assertEquals("a  b  c", stripSvgBlocks("a <svg>1</svg> b <svg>2</svg> c"))
        assertEquals("chưa xong <svg", stripSvgBlocks("chưa xong <svg"))
    }
}
