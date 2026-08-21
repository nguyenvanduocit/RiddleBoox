package com.riddleboox.app.handwriting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil

/**
 * [TextRaster] backed by `android.graphics`, replacing the `ab_glyph`
 * rasterizer of references/Riddle/src/script.rs:15-65.
 */
class PaintTextRaster(typeface: Typeface) : TextRaster {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    override fun measure(text: String, fontSizePx: Float): Float {
        paint.textSize = fontSizePx
        return paint.measureText(text)
    }

    override fun rasterize(text: String, fontSizePx: Float): GlyphMask {
        paint.textSize = fontSizePx
        val metrics = paint.fontMetrics
        val width = (ceil(paint.measureText(text)).toInt() + PAD_PX).coerceAtLeast(1)
        val height = (ceil(metrics.descent - metrics.ascent).toInt() + PAD_PX).coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.TRANSPARENT)
            drawText(text, INSET_PX, INSET_PX - metrics.ascent, paint)
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        val mask = GlyphMask(width, height)
        for (i in pixels.indices) {
            mask.bits[i] = (pixels[i] ushr 24) > COVERAGE_THRESHOLD
        }
        return mask
    }

    companion object {
        const val DEFAULT_FONT_ASSET = "fonts/DancingScript.ttf"

        /** Padding around the ink, so thinning can reach every edge pixel (script.rs:31-32). */
        private const val PAD_PX = 4

        /** Half the padding, applied as a top-left inset to keep ink off the border. */
        private const val INSET_PX = PAD_PX / 2f

        /** Alpha cut-off matching `cov > 0.5` in script.rs:38. */
        private const val COVERAGE_THRESHOLD = 127

        fun fromAsset(context: Context, assetPath: String = DEFAULT_FONT_ASSET): PaintTextRaster =
            PaintTextRaster(Typeface.createFromAsset(context.assets, assetPath))
    }
}
