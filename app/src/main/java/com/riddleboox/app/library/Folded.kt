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
            val plain = foldChar(source[i])
            repeat(plain.length) { from.add(i) }
            folded.append(plain)
        }
        value = folded.toString()
        origin = from.toIntArray()
    }

    internal companion object {

        /**
         * One fold per distinct character, ever. A book is millions of
         * characters drawn from a few hundred distinct ones, and
         * [Normalizer.normalize] is far too slow to call once per occurrence.
         * The memo is a flat array over the range every Latin and Vietnamese
         * character lives in — an array read per character, no boxing — with
         * a map behind it for anything beyond. Racing writes are idempotent,
         * so neither needs a lock.
         */
        private val TABLE = arrayOfNulls<String>(0x2000)
        private val BEYOND = java.util.concurrent.ConcurrentHashMap<Char, String>()

        fun foldChar(c: Char): String {
            if (c.code < TABLE.size) {
                return TABLE[c.code] ?: normalize(c).also { TABLE[c.code] = it }
            }
            return BEYOND.getOrPut(c) { normalize(c) }
        }

        private fun normalize(c: Char): String =
            Normalizer.normalize(c.lowercaseChar().toString(), Normalizer.Form.NFD)
                .replace(COMBINING, "")
                .replace('đ', 'd')
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

/**
 * [text] flattened for matching — see [Folded] — without the map back to the
 * original. This is the scanning form: a search folds every chapter just to
 * ask `contains`, and pays for the index map only in the chapters that hit.
 */
internal fun fold(text: String): String {
    val folded = StringBuilder(text.length)
    for (c in text) folded.append(Folded.foldChar(c))
    return folded.toString()
}
