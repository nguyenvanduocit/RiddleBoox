package com.riddleboox.app.library

import java.text.Normalizer

/** Combining marks — what is left of a Vietnamese vowel once NFD pulls it apart. */
private val COMBINING = Regex("\\p{Mn}")

/**
 * [source] flattened for matching, with a way back to where each character
 * came from.
 *
 * Matching has to be forgiving in both directions. The writer's tones reach
 * the model through handwriting and come back out however the model read them,
 * so "Cau chuyen nghe thuat" must find *Câu Chuyện Nghệ Thuật*; and a passage
 * found in a flattened book has to be quoted back with its tones intact, which
 * is what [sourceIndex] is for.
 *
 * Folding one character at a time rather than normalising the whole string is
 * what makes that possible: NFD turns "ế" into two characters and stripping
 * the mark turns it back into one, so an offset into a wholesale-folded string
 * means nothing in the original.
 */
internal class Folded(private val source: String) {

    /** [source] in lower case, without diacritics, with `đ` flattened to `d`. */
    val value: String

    /** For each character of [value], its index in [source]. */
    private val origin: IntArray

    init {
        val folded = StringBuilder(source.length)
        val from = ArrayList<Int>(source.length)
        for (i in source.indices) {
            val plain = Normalizer.normalize(source[i].lowercaseChar().toString(), Normalizer.Form.NFD)
                .replace(COMBINING, "")
                .replace('đ', 'd')
            repeat(plain.length) { from.add(i) }
            folded.append(plain)
        }
        value = folded.toString()
        origin = from.toIntArray()
    }

    /**
     * Where [index] into [value] falls in [source]. An index at or past the end
     * maps to the end, so a match's closing offset comes back usable.
     */
    fun sourceIndex(index: Int): Int = when {
        index <= 0 -> 0
        index >= origin.size -> source.length
        else -> origin[index]
    }
}

/** [text] flattened for matching — see [Folded]. */
internal fun fold(text: String): String = Folded(text).value
