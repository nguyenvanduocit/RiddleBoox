package com.riddleboox.app.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Turns the written page into a PNG for the diary to read: crop to the ink, shrink it,
 * encode black ink on white. Image pixels dominate vision-token cost and latency,
 * so the page is cropped to what was actually written and capped at 800px on the
 * long edge — the model reads handwriting fine at that size.
 */
object PageRasterizer {

    /**
     * Rasterize [strokes] to PNG bytes, or null when the page is blank.
     *
     * The ink is painted with the same geometry [com.riddleboox.app.ui.RegionView]
     * shows on screen, so the diary sees the page the writer sees.
     */
    fun rasterize(
        strokes: List<InkStroke>,
        maxLongEdgePx: Int = 800,
        marginPx: Int = 20,
    ): ByteArray? {
        val plan = PageRasterizerMath.plan(strokes, maxLongEdgePx, marginPx) ?: return null

        val bitmap = Bitmap.createBitmap(plan.outWidthPx, plan.outHeightPx, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            // Draw in page coordinates: the canvas shrinks and crops for us, and
            // painting vectors straight at the target size antialiases better than
            // rendering full size and box-filtering afterwards.
            canvas.scale(plan.scale, plan.scale)
            canvas.translate(-plan.cropLeft, -plan.cropTop)
            paintInk(canvas, strokes)

            // Every channel carries the same value, so the encoder emits a grayscale
            // -valued opaque PNG; dropping alpha keeps it at 3 bytes per pixel.
            bitmap.setHasAlpha(false)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            val bytes = out.toByteArray()
            // Base64 (what actually goes over the wire in the JSON body) runs
            // ~4/3 the raw byte count — logged here, not at the call site, so
            // it's right next to the numbers (outWidthPx/outHeightPx) it's
            // computed from.
            Log.d(
                TAG,
                "rasterized page: ${plan.outWidthPx}x${plan.outHeightPx}px, " +
                    "${bytes.size} bytes PNG (~${bytes.size * 4 / 3} bytes base64)",
            )
            return bytes
        } finally {
            bitmap.recycle()
        }
    }

    private const val TAG = "PageRasterizer"

    private fun paintInk(canvas: Canvas, strokes: List<InkStroke>) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (stroke in strokes) {
            val points = stroke.points
            when {
                points.isEmpty() -> continue
                points.size == 1 -> {
                    val p = points[0]
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(p.x, p.y, inkRadiusPx(p.pressure), paint)
                }
                else -> {
                    paint.style = Paint.Style.STROKE
                    for (i in 1 until points.size) {
                        val a = points[i - 1]
                        val b = points[i]
                        // Mean radius of the two ends, as a diameter: the segment
                        // tapers with pen pressure the way the nib does.
                        paint.strokeWidth = inkRadiusPx(a.pressure) + inkRadiusPx(b.pressure)
                        canvas.drawLine(a.x, a.y, b.x, b.y, paint)
                    }
                }
            }
        }
    }
}
