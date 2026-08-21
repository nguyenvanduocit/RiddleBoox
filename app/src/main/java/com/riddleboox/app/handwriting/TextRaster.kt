package com.riddleboox.app.handwriting

/**
 * The only seam that needs a real font engine: measuring a string and turning
 * it into an ink mask. Everything downstream ([Skeletonizer], [SkeletonTracer],
 * [WriteCursor], [HandwritingPlanner]) is pure Kotlin and unit-testable.
 *
 * Covers `measure` (script.rs:52-65) and `rasterize_line` (script.rs:15-49).
 */
interface TextRaster {

    /** Advance width of [text] at [fontSizePx], in pixels. */
    fun measure(text: String, fontSizePx: Float): Float

    /** Renders [text] at [fontSizePx] into a padded, filled ink mask. */
    fun rasterize(text: String, fontSizePx: Float): GlyphMask
}
