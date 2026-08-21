package com.riddleboox.app.ink

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Brush radius in page pixels for a stroke point: a nib 2px wide at a resting
 * hand and 5.4px when leaned on.
 *
 * Same curve [com.riddleboox.app.ui.RegionView] paints with, so the raster the
 * diary reads matches the page the writer sees. Pressure is clamped to 0..1,
 * which also keeps raw device-scale pressures (BOOX reports 0..4096) at the
 * fattest radius instead of producing nonsense.
 *
 * The pen the Onyx driver draws with while the nib is still moving is set to
 * this curve's widest point (`InkCaptureController.STROKE_WIDTH_MM`, 0.6mm =
 * 5.4px on this panel's 227dpi), so a stroke does not change weight at the
 * moment it stops being live ink and starts being a repaint. Move one and the
 * other has to move with it.
 */
fun inkRadiusPx(pressure: Float): Float = 1.0f + pressure.coerceIn(0f, 1f) * 1.7f

/**
 * Crop rect (page coordinates) plus the shrink factor and output size for one
 * rasterized page. Pure geometry: no Android types, so it unit-tests on the JVM.
 */
data class RasterPlan(
    val cropLeft: Float,
    val cropTop: Float,
    val cropWidth: Float,
    val cropHeight: Float,
    val scale: Float,
    val outWidthPx: Int,
    val outHeightPx: Int,
)

/** Geometry half of [PageRasterizer]: where to crop and how far to shrink. */
object PageRasterizerMath {

    /**
     * Bounding box of every stroke point (widened by that point's brush radius),
     * grown by [marginPx], clamped to the page origin, then shrunk so the long
     * edge lands at [maxLongEdgePx]. Pages already shorter than that are left at
     * 1:1 — the diary gains nothing from upscaled ink.
     *
     * The crop stays the shape the writing is. Padding a one-line note out to a
     * square was tried on the theory that vision APIs rescale the short edge up
     * before tiling, making thin strips expensive; measured against the model
     * this diary actually talks to, it costs the opposite way — one real page of
     * handwriting billed 145 prompt tokens at 734x138 and 641 at 734x734. Blank
     * canvas is charged for like anything else.
     *
     * Returns null when there is no ink at all, so a blank page never reaches the
     * diary.
     */
    fun plan(
        strokes: List<InkStroke>,
        maxLongEdgePx: Int = 800,
        marginPx: Int = 20,
    ): RasterPlan? {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var hasInk = false

        for (stroke in strokes) {
            for (p in stroke.points) {
                val r = inkRadiusPx(p.pressure)
                if (p.x - r < minX) minX = p.x - r
                if (p.y - r < minY) minY = p.y - r
                if (p.x + r > maxX) maxX = p.x + r
                if (p.y + r > maxY) maxY = p.y + r
                hasInk = true
            }
        }
        if (!hasInk) return null

        val margin = max(0, marginPx).toFloat()
        val left = max(0f, minX - margin)
        val top = max(0f, minY - margin)
        // A one-point stroke still spans 2*radius, so these stay positive; the
        // floor keeps a degenerate page from dividing by zero regardless.
        val cropWidth = max(1f, maxX + margin - left)
        val cropHeight = max(1f, maxY + margin - top)

        val longEdge = max(cropWidth, cropHeight)
        val scale = if (maxLongEdgePx > 0 && longEdge > maxLongEdgePx) maxLongEdgePx / longEdge else 1f
        val cap = if (maxLongEdgePx > 0) maxLongEdgePx else Int.MAX_VALUE
        val outWidthPx = (cropWidth * scale).roundToInt().coerceIn(1, cap)
        val outHeightPx = (cropHeight * scale).roundToInt().coerceIn(1, cap)

        return RasterPlan(
            cropLeft = left,
            cropTop = top,
            cropWidth = cropWidth,
            cropHeight = cropHeight,
            scale = scale,
            outWidthPx = outWidthPx,
            outHeightPx = outHeightPx,
        )
    }
}
