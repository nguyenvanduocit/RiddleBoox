package com.riddleboox.app.ink

import com.riddleboox.app.settings.PenStyle
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Brush radius in page pixels for one stroke point at [pressure], before any
 * [PenStyle.hasTexture]/[PenStyle.hasTapering] roughening — [style]'s own
 * min/max radius and pressure curve, nothing else. Pressure is clamped to
 * 0..1 first, which also keeps raw device-scale pressures (BOOX reports
 * 0..4096) at the style's fattest radius instead of producing nonsense.
 *
 * [PenStyle.Ballpoint] reproduces the diary's original fixed curve exactly
 * (1.0px resting, 2.7px leaned on) — a writer who never opens the pen style
 * setting keeps writing with exactly the pen they always had.
 */
fun baseRadiusPx(pressure: Float, style: PenStyle): Float {
    val p = pressure.coerceIn(0f, 1f).pow(style.pressureCurveExponent)
    return style.minRadiusPx + p * (style.maxRadiusPx - style.minRadiusPx)
}

/**
 * Deterministic per-point roughening for [PenStyle.hasTexture] (the pencil's
 * grain): a multiplier in 0.5..1.5 keyed only on [pointIndex] within its
 * stroke, so a page rasterized twice from the same strokes — once for the
 * screen, once for [PageRasterizer] to send the diary — always lands on the
 * same pixels. A runtime `Random()` would make the two disagree. The swing
 * is wide enough to read as grain rather than antialiasing noise.
 */
fun textureJitter(pointIndex: Int): Float {
    // Knuth's multiplicative hash: cheap, no allocation, good bit spread from
    // a plain sequential index.
    val hashed = pointIndex * -1640531527
    val unit = ((hashed ushr 24) and 0xFF) / 255f
    return 0.5f + unit
}

/**
 * Thins a stroke toward both its ends for [PenStyle.hasTapering] (the brush's
 * and fountain pen's pointed tips) — 1f (untouched) everywhere except the
 * first/last [taperPoints] points, where it eases down to a sliver at the
 * very tip. A one-point stroke has no "toward the end" to taper, so it
 * stays 1f.
 */
fun taperMultiplier(pointIndex: Int, pointCount: Int, taperPoints: Int = 8): Float {
    if (pointCount <= 1) return 1f
    val distanceFromNearestEnd = minOf(pointIndex, pointCount - 1 - pointIndex)
    if (distanceFromNearestEnd >= taperPoints) return 1f
    return 0.15f + 0.85f * (distanceFromNearestEnd.toFloat() / taperPoints)
}

/**
 * Full brush radius in page pixels for the point at [pointIndex] of a stroke
 * [pointCount] points long: [baseRadiusPx] for [style], roughened by
 * [textureJitter]/[taperMultiplier] when [style] calls for it, scaled by
 * [widthScale] (the writer's separate stroke-width setting) last.
 *
 * Called with only [pressure] this reproduces the diary's original fixed
 * curve exactly — see [baseRadiusPx].
 */
fun inkRadiusPx(
    pressure: Float,
    pointIndex: Int = 0,
    pointCount: Int = 1,
    style: PenStyle = PenStyle.Default,
    widthScale: Float = 1f,
): Float {
    var r = baseRadiusPx(pressure, style)
    if (style.hasTexture) r *= textureJitter(pointIndex)
    if (style.hasTapering) r *= taperMultiplier(pointIndex, pointCount)
    return r * widthScale
}

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
        style: PenStyle = PenStyle.Default,
        widthScale: Float = 1f,
    ): RasterPlan? {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var hasInk = false

        for (stroke in strokes) {
            val pointCount = stroke.points.size
            stroke.points.forEachIndexed { i, p ->
                val r = inkRadiusPx(p.pressure, i, pointCount, style, widthScale)
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
