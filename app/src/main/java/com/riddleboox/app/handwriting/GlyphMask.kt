package com.riddleboox.app.handwriting

/**
 * Row-major bit mask of inked pixels for one rasterized line of text.
 *
 * Port of `Line` in references/Riddle/src/script.rs:7-12.
 */
class GlyphMask(
    val width: Int,
    val height: Int,
    val bits: BooleanArray = BooleanArray(width * height),
) {
    init {
        require(width >= 0 && height >= 0) { "size must be non-negative, got ${width}x$height" }
        require(bits.size == width * height) { "bits must be ${width * height}, got ${bits.size}" }
    }

    operator fun get(x: Int, y: Int): Boolean = bits[y * width + x]

    operator fun set(x: Int, y: Int, inked: Boolean) {
        bits[y * width + x] = inked
    }

    /** Bounds-checked read, mirroring the `at` closure in script.rs:130-132. */
    fun isInked(x: Int, y: Int): Boolean =
        x >= 0 && y >= 0 && x < width && y < height && bits[y * width + x]

    val inkedCount: Int get() = bits.count { it }
}
