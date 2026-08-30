package com.riddleboox.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CachedEpubTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun epub(fillerChars: Int = 0): Epub =
        Epub.open(writeSampleEpub(folder.newFile(), fillerChars)) ?: error("sample epub would not open")

    /**
     * The proof the cache is really read back: the second opening wraps an
     * epub whose chapter one is thousands of characters longer, but under the
     * same fingerprint it hands back the short text the first opening wrote.
     */
    @Test
    fun `a chapter extracted once is read from disk on the next opening`() {
        val dir = folder.newFolder()

        val short = CachedEpub(epub(fillerChars = 0), dir, fingerprint = "same").use { it.text(0) }
        val second = CachedEpub(epub(fillerChars = 20_000), dir, fingerprint = "same").use { it.text(0) }

        assertEquals("the cached words come back, not the new zip's", short, second)
    }

    /** A changed source file must not serve the old book's words. */
    @Test
    fun `a new fingerprint wipes the cache and re-extracts`() {
        val dir = folder.newFolder()

        val short = CachedEpub(epub(fillerChars = 0), dir, fingerprint = "v1").use { it.text(0) }
        val long = CachedEpub(epub(fillerChars = 20_000), dir, fingerprint = "v2").use { it.text(0) }

        assertTrue("the re-extracted chapter carries the new filler", long.length > short.length)
    }

    @Test
    fun `search through the cache finds the same passages as the zip`() {
        val dir = folder.newFolder()
        val fromZip = epub().use { it.passages("con meo", limit = 5, window = 220) }

        // Twice: the first search extracts and writes, the second reads back.
        CachedEpub(epub(), dir, "same").use { it.passages("con meo", 5, 220) }
        val fromCache = CachedEpub(epub(), dir, "same").use { it.passages("con meo", 5, 220) }

        assertEquals(fromZip, fromCache)
    }

    /** No directory, no caching — a plain pass-through, the tests' default. */
    @Test
    fun `a null directory reads straight from the zip`() {
        val text = CachedEpub(epub(), dir = null).use { it.text(0) }
        assertTrue(text.contains("Mèo con ngồi trên mái nhà"))
    }
}
