package com.riddleboox.app.handwriting

/**
 * Reply-ink stroke width at [fontSizePx] — reply ink has no pressure
 * (main.rs:602-604), so unlike the writer's own hand it needs one fixed
 * width to draw with. Shared by the real page renderer
 * ([com.riddleboox.app.ui.RegionView]) and its settings preview so the two
 * never drift apart.
 *
 * At the 64px default this lands on 4px — a ~1:16 stroke-to-glyph ratio that
 * also lands where the writer's own pen sits at a normal lean (2-5.4px, see
 * `inkRadiusPx`), so the diary's hand reads as thick as the writer's on the
 * e-ink panel's coarser anti-aliasing. [MIN_REPLY_STROKE_WIDTH_PX] is a
 * hardware floor — thinner and the panel loses the stroke — and the ratio
 * keeps a much larger glyph from drawing with a stroke so thin it looks
 * unlike the writer's own hand, or a much smaller one from filling in the
 * script font's loops.
 */
fun replyStrokeWidthPx(fontSizePx: Float): Float =
    maxOf(MIN_REPLY_STROKE_WIDTH_PX, fontSizePx / REPLY_STROKE_TO_FONT_RATIO)

private const val MIN_REPLY_STROKE_WIDTH_PX = 4f
private const val REPLY_STROKE_TO_FONT_RATIO = 16f
