package com.riddleboox.app.handwriting

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.ceil

/**
 * [Shape] rendered as a [GlyphMask] — the same seam [PaintTextRaster] is for
 * a glyph, and the counterpart it deliberately does not reuse: text is
 * rasterized filled, because a font's own letterforms are already stroke-width
 * shapes and filling just paints them in. A filled circle has no such
 * shape — it is a disk, and [Skeletonizer.thin] erodes a disk down to a
 * single point at its centre, not a ring. Stroked, a shape is already the
 * width a pen line would be, and thins down to its own centreline the same
 * way a letter's stroke does.
 */
class ShapeRaster {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
    }

    /** Sized off [fontSizePx] the same way a glyph is, so a shape sits on the line like a word would. */
    fun rasterize(shape: Shape, fontSizePx: Float): GlyphMask {
        val strokeWidth = (fontSizePx * STROKE_RATIO).coerceAtLeast(1f)
        val side = ceil(fontSizePx * SIZE_RATIO).toInt().coerceAtLeast(1)
        val padded = side + PAD_PX
        paint.strokeWidth = strokeWidth

        val bitmap = Bitmap.createBitmap(padded, padded, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.TRANSPARENT)
            // Half the stroke width kept clear of the edge, same as PAD_PX
            // does for a glyph — thinning needs every ink pixel's full
            // neighbourhood, which a stroke drawn flush against the border
            // would not have.
            val inset = strokeWidth / 2f + PAD_PX / 2f
            val bounds = RectF(inset, inset, padded - inset, padded - inset)
            when (shape) {
                Shape.Circle -> drawOval(bounds, paint)
                Shape.Box -> drawRect(bounds, paint)
            }
        }
        val pixels = IntArray(padded * padded)
        bitmap.getPixels(pixels, 0, padded, 0, 0, padded, padded)
        bitmap.recycle()

        val mask = GlyphMask(padded, padded)
        for (i in pixels.indices) {
            mask.bits[i] = (pixels[i] ushr 24) > COVERAGE_THRESHOLD
        }
        return mask
    }

    companion object {
        /** A shape reads as tall as a capital letter, not taller — roughly the glyph raster's own height. */
        private const val SIZE_RATIO = 0.95f

        /** Close to a glyph's own stroke weight at this font, so a shape reads as the same hand's ink. */
        private const val STROKE_RATIO = 0.09f

        /** Matches [PaintTextRaster]'s own padding — thinning needs the same border clearance. */
        private const val PAD_PX = 4

        /** Matches [PaintTextRaster]'s own alpha cut-off. */
        private const val COVERAGE_THRESHOLD = 127
    }
}
