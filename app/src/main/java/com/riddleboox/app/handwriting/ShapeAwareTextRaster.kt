package com.riddleboox.app.handwriting

/**
 * A [TextRaster] that draws a [Shape] instead of a glyph for the handful of
 * tokens [Shape.fromToken] recognises, and delegates every ordinary word to
 * [delegate] unchanged.
 *
 * Sits behind the same interface [WriteCursor] already calls once per word,
 * so a reply that writes `[[circle]]` gets a drawn circle laid out exactly
 * where that word would have gone, through the very same
 * rasterize -> thin -> trace pipeline a glyph goes through. Nothing in
 * [WriteCursor], [HandwritingPlanner], [Skeletonizer] or [SkeletonTracer]
 * needs to know the ink it is tracing came from a shape and not a font —
 * this is the only file that does.
 */
class ShapeAwareTextRaster(
    private val delegate: TextRaster,
    private val shapes: ShapeRaster = ShapeRaster(),
) : TextRaster {

    override fun measure(text: String, fontSizePx: Float): Float =
        Shape.fromToken(text)?.let { shapes.rasterize(it, fontSizePx).width.toFloat() }
            ?: delegate.measure(text, fontSizePx)

    override fun rasterize(text: String, fontSizePx: Float): GlyphMask =
        Shape.fromToken(text)?.let { shapes.rasterize(it, fontSizePx) }
            ?: delegate.rasterize(text, fontSizePx)
}
