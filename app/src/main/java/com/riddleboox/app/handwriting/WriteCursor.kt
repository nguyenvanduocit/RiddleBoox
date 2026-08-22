package com.riddleboox.app.handwriting

import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A pen resting on the page, laying words down left to right as they are given
 * to it.
 *
 * Left-aligned on purpose, and that alignment is what makes streaming possible:
 * the next word starts where the last one ended, and whether it fits is decided
 * from its own width alone. Nothing here needs to see the rest of the sentence,
 * so a reply can go onto the page while the model is still writing it — and a
 * hand writing in a diary does not centre its lines anyway.
 *
 * The pen stops at [bottomLimitPx] rather than writing off the bottom of the
 * page. Words it has no room for wait in hand ([pageFull]) until the caller has
 * shown what is written and calls [nextPage] to start a clean one. The waiting
 * matters: laying out is far ahead of drawing, so a pen that turned the page
 * itself would blank lines the writer has not been shown yet.
 *
 * Layout is the part of `plan_reply` (references/Riddle/src/main.rs:861-892)
 * that had to become incremental; the rasterize -> thin -> trace pipeline it
 * drives is unchanged.
 */
class WriteCursor(
    private val raster: TextRaster,
    private val pageWidthPx: Int,
    private val fontSizePx: Float = HandwritingPlanner.DEFAULT_FONT_SIZE_PX,
    private val lineHeightPx: Float = HandwritingPlanner.DEFAULT_LINE_HEIGHT_PX,
    private val jitterPx: Float = HandwritingPlanner.DEFAULT_JITTER_PX,
    private val marginXPx: Float = HandwritingPlanner.DEFAULT_MARGIN_X_PX,
    private val paragraphGapPx: Float = HandwritingPlanner.DEFAULT_PARAGRAPH_GAP_PX,
    private val startYPx: Float = 0f,
    /** Where the page runs out; the default is a page that never does. */
    private val bottomLimitPx: Float = Float.MAX_VALUE,
    private val random: Random = Random.Default,
) {

    private val accumulated = ArrayList<WriteStroke>()

    /** Everything written on the current page, in the order it was written. */
    val strokes: List<WriteStroke> get() = accumulated

    /** Words and line breaks that had no room, oldest first. */
    private val held = ArrayDeque<String>()

    private val spaceWidthPx = raster.measure(" ", fontSizePx)
    private val rightLimitPx = pageWidthPx - marginXPx

    /** Left edge of where the next word's mask goes, before any word gap. */
    private var penXPx = marginXPx
    private var lineTopPx = startYPx
    private var wobblePx = nextWobble()

    /** Whether this line already holds a word, i.e. whether a gap is owed. */
    private var lineHasWord = false

    /** Whether the empty line under the pen has already been given its air. */
    private var paragraphOpened = false

    /** True when words are waiting for a page with room on it. */
    val pageFull: Boolean get() = held.isNotEmpty()

    /**
     * Where the nib is waiting — just past the last word, halfway down the
     * line. What the diary marks while it has run out of words to write.
     */
    val penPoint: WritePoint
        get() = WritePoint(penXPx + gapPx(), lineTopPx + wobblePx + fontSizePx * NIB_HEIGHT_FRACTION)

    /**
     * Takes [text] and returns ONLY the strokes it added, so a caller animating
     * the reply can paint the new ink without rewalking the old. Returns
     * nothing once the page is full; the text is kept for [nextPage].
     *
     * Whitespace separates words and newlines break lines, exactly as they do
     * in the text; a word wider than the line gets a line to itself rather than
     * being cut.
     *
     * Successive calls must break at word boundaries. A word handed over in two
     * calls is two words to the pen, and lands as `he llo` — which is what
     * [com.riddleboox.app.reply.writableCut] exists to prevent upstream.
     */
    fun append(text: String): List<WriteStroke> {
        for (token in tokenize(text)) held.addLast(token)
        return writeWhatFits()
    }

    /**
     * Wipes the pen's idea of the page and starts again at the top, writing
     * whatever [append] had no room for. The caller is responsible for clearing
     * the ink already shown; from here [strokes] is the new page alone.
     */
    fun nextPage(): List<WriteStroke> {
        accumulated.clear()
        penXPx = marginXPx
        lineTopPx = startYPx
        lineHasWord = false
        paragraphOpened = false
        wobblePx = nextWobble()
        return writeWhatFits()
    }

    /** Everything on the current page, as the finished plan it leaves standing. */
    fun plan(): WritePlan = WritePlan(accumulated.toList())

    /**
     * Lays [figure] on the page as a block of its own: below the line being
     * written, centred, scaled to fit the room the page still has, with the pen
     * moved to a fresh line underneath so the words carry on below it. Returns
     * the strokes it added — already part of [strokes] and [plan] — or null
     * when the page has no room left for a figure a reader could make out.
     *
     * A line of writing plus its gap is always kept clear beneath the figure.
     * That reservation is what preserves the invariant the rest of this class
     * rests on: the pen only ever stands on a line [hasRoomForAnotherLine]
     * approved, so the first word after the figure cannot land below the page.
     */
    fun placeFigure(figure: SvgFigure): List<WriteStroke>? {
        if (pageFull) return null
        val contentWidthPx = rightLimitPx - marginXPx
        if (contentWidthPx <= 0f) return null
        val topPx = when {
            accumulated.isEmpty() && !lineHasWord -> lineTopPx
            lineHasWord -> lineTopPx + lineHeightPx + FIGURE_GAP_PX
            else -> lineTopPx + FIGURE_GAP_PX
        }
        val availablePx = bottomLimitPx - topPx - (lineHeightPx + FIGURE_GAP_PX)
        val maxHeightPx = minOf(MAX_FIGURE_HEIGHT_PX, availablePx)
        if (maxHeightPx < MIN_FIGURE_HEIGHT_PX) return null
        val scale = minOf(
            if (figure.width > 0f) contentWidthPx / figure.width else Float.MAX_VALUE,
            if (figure.height > 0f) maxHeightPx / figure.height else Float.MAX_VALUE,
        )
        val leftPx = marginXPx + (contentWidthPx - figure.width * scale) / 2f
        val placed = figure.strokes.map { stroke ->
            WriteStroke(stroke.points.map { WritePoint(leftPx + it.x * scale, topPx + it.y * scale) })
        }
        // Thinned to the pen's pace now that the on-page scale is known; a
        // figure still denser than the point budget is thinned proportionally
        // harder rather than refused — it draws coarser, not longer.
        var inked = decimated(placed, FIGURE_POINT_SPACING_PX)
        val pointCount = inked.sumOf { it.points.size }
        if (pointCount > MAX_FIGURE_POINTS) {
            inked = decimated(placed, FIGURE_POINT_SPACING_PX * pointCount / MAX_FIGURE_POINTS)
        }
        accumulated += inked
        lineTopPx = topPx + figure.height * scale + FIGURE_GAP_PX
        penXPx = marginXPx
        lineHasWord = false
        // The figure's own gap is the paragraph air; a break right after it
        // should not buy more.
        paragraphOpened = true
        wobblePx = nextWobble()
        return inked
    }

    private fun writeWhatFits(): List<WriteStroke> {
        val added = ArrayList<WriteStroke>()
        while (held.isNotEmpty()) {
            val token = held.first()
            if (token == LINE_BREAK) {
                if (lineHasWord) {
                    if (!hasRoomForAnotherLine()) break
                    held.removeFirst()
                    newLine()
                } else {
                    held.removeFirst()
                    openParagraph()
                }
                continue
            }
            val mask = raster.rasterize(token, fontSizePx)
            Skeletonizer.thin(mask)
            if (lineHasWord && penXPx + spaceWidthPx + mask.width > rightLimitPx) {
                if (!hasRoomForAnotherLine()) break
                newLine()
            }
            held.removeFirst()
            added += writeWord(mask)
        }
        // A line break with nothing after it has nothing to put on the next
        // page; dropping it keeps `pageFull` meaning "words are waiting".
        if (held.all { it == LINE_BREAK }) held.clear()
        accumulated += added
        return added
    }

    /** The mask's ink placed at the nib, and the nib moved past it. */
    private fun writeWord(mask: GlyphMask): List<WriteStroke> {
        val left = penXPx + gapPx()
        val top = lineTopPx + wobblePx
        val strokes = SkeletonTracer.trace(mask).map { stroke ->
            WriteStroke(stroke.map { WritePoint(left + it.x, top + it.y) })
        }
        // The mask's own width is the advance, not `measure`: the word is drawn
        // from that mask, so stepping by anything else would drift the line by
        // the difference on every word.
        penXPx = left + mask.width
        lineHasWord = true
        return strokes
    }

    /** Whether a line below this one would still land on the page. */
    private fun hasRoomForAnotherLine(): Boolean =
        lineTopPx + lineHeightPx * 2 <= bottomLimitPx

    private fun newLine() {
        lineTopPx += lineHeightPx
        penXPx = marginXPx
        lineHasWord = false
        paragraphOpened = false
        // A fresh wobble per line, so a line shifts as one and neighbouring
        // lines sit at slightly different heights (main.rs:870-874).
        wobblePx = nextWobble()
    }

    /**
     * A break landing on a line that holds no words asks for a paragraph, not
     * for a second line: it buys [paragraphGapPx] of air, once, however many
     * blank lines the text actually carried. A whole empty line would cost the
     * page a full line of writing every time the reply changes subject.
     */
    private fun openParagraph() {
        if (paragraphOpened) return
        lineTopPx += paragraphGapPx
        paragraphOpened = true
    }

    private fun gapPx(): Float = if (lineHasWord) spaceWidthPx else 0f

    private fun nextWobble(): Float {
        val range = jitterPx.roundToInt().absoluteValue
        return if (range == 0) 0f else random.nextInt(-range, range + 1).toFloat()
    }

    /** Words and explicit line breaks, in order; runs of spaces mean nothing. */
    private fun tokenize(text: String): List<String> {
        val tokens = ArrayList<String>()
        for ((index, paragraph) in text.split('\n').withIndex()) {
            if (index > 0) tokens.add(LINE_BREAK)
            for (word in paragraph.split(WHITESPACE)) {
                if (word.isNotEmpty()) tokens.add(word)
            }
        }
        return tokens
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** Stands in the queue where the text had a newline. */
        const val LINE_BREAK = "\n"

        /** How far down the line the nib sits — roughly the middle of the ink. */
        const val NIB_HEIGHT_FRACTION = 0.55f

        /** Air kept above and below a placed figure. */
        const val FIGURE_GAP_PX = 28f

        /** A figure never takes more than about a third of a BOOX page. */
        const val MAX_FIGURE_HEIGHT_PX = 560f

        /** Smaller than this and a figure is a smudge; the page refuses instead. */
        const val MIN_FIGURE_HEIGHT_PX = 96f

        /** Target gap between a figure's revealed points, matching handwriting's density. */
        const val FIGURE_POINT_SPACING_PX = 3.5f

        /** Ceiling on a figure's points — about two seconds of the reveal pen. */
        const val MAX_FIGURE_POINTS = 4000
    }
}
