package com.riddleboox.app.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer

class FoldedTest {

    @Test
    fun `diacritics and case fall away together`() {
        assertEquals("cau chuyen", fold("Câu chuyện"))
    }

    /**
     * Đ has no NFD decomposition of its own — it is a distinct base letter, not
     * 'D' plus a stroke mark — so this only passes because of the explicit
     * `replace('đ', 'd')` in [Folded]'s init, not because NFD stripping alone
     * would ever turn 'Đ' into 'd'.
     */
    @Test
    fun `the letter Đ folds to d, not to whatever NFD alone would leave behind`() {
        assertEquals("dem", fold("Đêm"))
    }

    @Test
    fun `an empty string folds to an empty string`() {
        assertEquals("", fold(""))
    }

    @Test
    fun `text with no diacritics is only lower-cased`() {
        assertEquals("plain text", fold("Plain Text"))
    }

    @Test
    fun `the first character of value maps back to the start of source`() {
        assertEquals(0, Folded("Câu chuyện").sourceIndex(0))
    }

    /**
     * Combining marks strip to nothing, so a source string that already
     * carries its diacritics decomposed (rather than the precomposed form a
     * Vietnamese IME normally produces) folds to a *shorter* value than
     * source: "Việt" spelled as 'e' + combining dot below (U+0323) + combining
     * circumflex (U+0302) is 6 characters in source but folds to "viet", 4
     * characters in value, so origin.size (4) is less than source.length (6).
     * An index at the end of value still has to resolve to the true end of
     * source, not to origin.size and not to value.length.
     */
    @Test
    fun `an index at the end of value maps to the end of source, even when their lengths differ`() {
        val decomposedViet = "Vi" + "ệ" + "t"
        val folded = Folded(decomposedViet)

        assertEquals("viet", folded.value)
        assertEquals(decomposedViet.length, folded.sourceIndex(folded.value.length))
    }

    @Test
    fun `a negative index maps to the start of source`() {
        assertEquals(0, Folded("Câu chuyện").sourceIndex(-5))
    }

    /**
     * Rather than hand-computing which index each character of "Việt" should
     * map back to — easy to get wrong given how NFD decomposition behaves —
     * this checks the invariant directly: every character folded out of
     * source character `i` must report `sourceIndex == i`, using a reference
     * mapping computed independently of [Folded].
     */
    @Test
    fun `every character of value maps back to the source character it came from`() {
        val source = "Việt"
        val folded = Folded(source)
        val expectedOrigins = originsOf(source)

        assertEquals(expectedOrigins.size, folded.value.length)
        expectedOrigins.forEachIndexed { index, expectedOrigin ->
            assertEquals(
                "value[$index] = '${folded.value[index]}' should map back to source[$expectedOrigin]",
                expectedOrigin,
                folded.sourceIndex(index),
            )
        }
    }

    @Test
    fun `a full sentence folds correctly and stays traceable at its start, middle and end`() {
        val source = "Nghệ thuật kể chuyện"

        assertEquals("nghe thuat ke chuyen", fold(source))

        val folded = Folded(source)
        val expectedOrigins = originsOf(source)
        val milestones = listOf(0, expectedOrigins.size / 2, expectedOrigins.size - 1)

        for (index in milestones) {
            assertEquals(expectedOrigins[index], folded.sourceIndex(index))
        }
        assertEquals(source.length, folded.sourceIndex(folded.value.length))
    }

    /**
     * Reference implementation of [Folded]'s per-character index mapping,
     * written independently rather than by calling into [Folded], so these
     * tests check the real mapping against something other than itself.
     */
    private fun originsOf(source: String): List<Int> {
        val combining = Regex("\\p{Mn}")
        val origins = ArrayList<Int>()
        for (i in source.indices) {
            val plainLength = Normalizer.normalize(source[i].lowercaseChar().toString(), Normalizer.Form.NFD)
                .replace(combining, "")
                .replace('đ', 'd')
                .length
            repeat(plainLength) { origins.add(i) }
        }
        return origins
    }
}
