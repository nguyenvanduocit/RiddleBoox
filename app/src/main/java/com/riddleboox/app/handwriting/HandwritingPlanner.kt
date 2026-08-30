package com.riddleboox.app.handwriting

import kotlin.random.Random

/**
 * Turns reply text into an ordered [WritePlan] by handing it to a [WriteCursor]
 * in one go — the same pen, told everything at once instead of word by word.
 *
 * Port of `plan_reply` in references/Riddle/src/main.rs:861-892.
 */
class HandwritingPlanner(
    private val raster: TextRaster,
    private val random: Random = Random.Default,
) {

    /**
     * A pen at the top of the reply area, for text that arrives over time.
     *
     * @param pageWidthPx width of the target surface.
     * @param fontSizePx glyph height (`REPLY_PX`, main.rs:41).
     * @param lineHeightPx baseline-to-baseline advance (`REPLY_PX * 1.25`, main.rs:864).
     * @param jitterPx maximum per-line vertical wobble, in either direction (main.rs:870-874).
     * @param marginXPx horizontal margin kept free on both sides (`MARGIN_X`, main.rs:42).
     * @param paragraphGapPx air added where the text breaks a paragraph rather than a line.
     * @param startYPx top of the first line; the caller decides where the reply lands.
     * @param bottomLimitPx where the page runs out and the pen must be given a new one.
     */
    fun cursor(
        pageWidthPx: Int,
        fontSizePx: Float = DEFAULT_FONT_SIZE_PX,
        lineHeightPx: Float = DEFAULT_LINE_HEIGHT_PX,
        jitterPx: Float = DEFAULT_JITTER_PX,
        marginXPx: Float = DEFAULT_MARGIN_X_PX,
        paragraphGapPx: Float = DEFAULT_PARAGRAPH_GAP_PX,
        startYPx: Float = 0f,
        bottomLimitPx: Float = Float.MAX_VALUE,
    ): WriteCursor = WriteCursor(
        raster = raster,
        pageWidthPx = pageWidthPx,
        fontSizePx = fontSizePx,
        lineHeightPx = lineHeightPx,
        jitterPx = jitterPx,
        marginXPx = marginXPx,
        paragraphGapPx = paragraphGapPx,
        startYPx = startYPx,
        bottomLimitPx = bottomLimitPx,
        random = random,
    )

    /** The whole of [text], laid out at once — for a reply already complete. */
    fun plan(
        text: String,
        pageWidthPx: Int,
        fontSizePx: Float = DEFAULT_FONT_SIZE_PX,
        lineHeightPx: Float = DEFAULT_LINE_HEIGHT_PX,
        jitterPx: Float = DEFAULT_JITTER_PX,
        marginXPx: Float = DEFAULT_MARGIN_X_PX,
        paragraphGapPx: Float = DEFAULT_PARAGRAPH_GAP_PX,
        startYPx: Float = 0f,
    ): WritePlan = cursor(
        pageWidthPx = pageWidthPx,
        fontSizePx = fontSizePx,
        lineHeightPx = lineHeightPx,
        jitterPx = jitterPx,
        marginXPx = marginXPx,
        paragraphGapPx = paragraphGapPx,
        startYPx = startYPx,
    ).apply { append(text) }.plan()

    companion object {
        const val DEFAULT_FONT_SIZE_PX = 64f
        const val DEFAULT_LINE_HEIGHT_PX = 80f

        /** [DEFAULT_LINE_HEIGHT_PX] as a multiple of [DEFAULT_FONT_SIZE_PX] — the ratio a caller
         * deriving a line height from a non-default, user-chosen font size scales by. */
        const val LINE_HEIGHT_RATIO = DEFAULT_LINE_HEIGHT_PX / DEFAULT_FONT_SIZE_PX
        const val DEFAULT_JITTER_PX = 3f
        const val DEFAULT_MARGIN_X_PX = 40f

        /**
         * Air between paragraphs, a third of a line rather than a whole one.
         * The reply arrives with blank lines between its paragraphs, and on a
         * page that holds twenty-one lines each blank one is five percent of
         * the writing gone.
         */
        const val DEFAULT_PARAGRAPH_GAP_PX = 28f
    }
}
