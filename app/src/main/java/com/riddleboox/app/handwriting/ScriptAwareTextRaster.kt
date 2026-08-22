package com.riddleboox.app.handwriting

import kotlin.math.ceil

/**
 * A [TextRaster] that draws a subscript or superscript run smaller and
 * offset from the baseline, instead of at the word's own size — the general
 * case [com.riddleboox.app.reply.mathChemNotation]'s own character-by-
 * character Unicode substitution can't reach: Unicode has a small form for
 * roughly half the Latin alphabet and almost none of Greek, so a run that
 * substitution left completely untouched (see its own doc, "all-or-nothing
 * on the braced form") still needs to read as smaller and raised or
 * lowered, not at full size with a stray brace showing.
 *
 * [ScriptRun]s already parsed [parseScriptRuns] the word into normal and
 * scripted pieces; this class only has to rasterize each at its own size
 * through [delegate] and place them side by side. Ordinary words — the
 * overwhelming majority — carry no `_`/`^` at all and fall straight through
 * to [delegate] unchanged, one [parseScriptRuns] call and no extra
 * rasterizing.
 */
class ScriptAwareTextRaster(private val delegate: TextRaster) : TextRaster {

    override fun measure(text: String, fontSizePx: Float): Float {
        val runs = parseScriptRuns(text)
        if (runs.none { it !is ScriptRun.Plain }) return delegate.measure(text, fontSizePx)
        return runs.sumOf { run -> delegate.measure(run.text, run.fontSizePx(fontSizePx)).toDouble() }.toFloat()
    }

    override fun rasterize(text: String, fontSizePx: Float): GlyphMask {
        val runs = parseScriptRuns(text)
        if (runs.none { it !is ScriptRun.Plain }) return delegate.rasterize(text, fontSizePx)
        return compose(runs, fontSizePx)
    }

    /** [fontSizePx] scaled down for a [ScriptRun.Sub]/[ScriptRun.Sup], unchanged for [ScriptRun.Plain]. */
    private fun ScriptRun.fontSizePx(base: Float): Float = when (this) {
        is ScriptRun.Plain -> base
        is ScriptRun.Sub, is ScriptRun.Sup -> base * SCRIPT_SCALE
    }

    /**
     * Every run rasterized at its own size, then placed left to right onto
     * one mask — a subscript's own baseline sitting [SUB_DROP_RATIO] of a
     * full-size line below a plain run's, a superscript's
     * [SUP_RAISE_RATIO] above it.
     *
     * [TextRaster] exposes no font metrics of its own — only a rasterized
     * mask — so there is no ascent to read directly. What holds instead:
     * [PaintTextRaster] draws every string of the same typeface with the
     * same ascent-to-height *ratio* regardless of font size (Android scales
     * a `Paint`'s metrics linearly with `textSize`), so multiplying each
     * run's own mask height by [BASELINE_RATIO] lands on that run's own
     * baseline whether it was rasterized at the word's full size or at
     * [SCRIPT_SCALE] of it.
     */
    private fun compose(runs: List<ScriptRun>, fontSizePx: Float): GlyphMask {
        val rasterized = runs.map { run -> run to delegate.rasterize(run.text, run.fontSizePx(fontSizePx)) }
        val subDropPx = fontSizePx * SUB_DROP_RATIO
        val supRaisePx = fontSizePx * SUP_RAISE_RATIO

        // Where each run's own baseline lands in the *composed* image, top
        // (0) being the highest any run's ink reaches.
        val baselineOf = { run: ScriptRun, mask: GlyphMask ->
            val ownBaseline = mask.height * BASELINE_RATIO
            when (run) {
                is ScriptRun.Plain -> ownBaseline
                is ScriptRun.Sub -> ownBaseline - subDropPx
                is ScriptRun.Sup -> ownBaseline + supRaisePx
            }
        }
        val highestBaseline = rasterized.maxOf { (run, mask) -> baselineOf(run, mask) }
        val width = rasterized.sumOf { (_, mask) -> mask.width }
        val height = ceil(
            rasterized.maxOf { (run, mask) -> highestBaseline - baselineOf(run, mask) + mask.height },
        ).toInt().coerceAtLeast(1)

        val combined = GlyphMask(width, height)
        var left = 0
        for ((run, mask) in rasterized) {
            // The run's own top edge in the composed image: its baseline
            // minus how far its own ink sits above that baseline.
            val top = (highestBaseline - baselineOf(run, mask)).toInt()
            for (y in 0 until mask.height) {
                val ty = top + y
                if (ty !in 0 until height) continue
                for (x in 0 until mask.width) {
                    if (mask.isInked(x, y)) combined[left + x, ty] = true
                }
            }
            left += mask.width
        }
        return combined
    }

    companion object {
        /** A subscript/superscript run reads at roughly three-fifths a plain run's size, the common typographic ratio. */
        private const val SCRIPT_SCALE = 0.62f

        /**
         * Where a font's own baseline sits, as a fraction of [PaintTextRaster]'s
         * padded bitmap height. Not measured — [TextRaster] has no metrics to
         * measure — chosen to match [PaintTextRaster.DEFAULT_FONT_ASSET]'s own
         * generous ascent (a cursive hand's loops and flourishes climb well
         * above a plain cap height) and adjusted once against an on-device
         * capture; a different typeface would need a different constant here.
         */
        private const val BASELINE_RATIO = 0.78f

        /** How far below the baseline a subscript's own baseline sits, a fraction of the word's full font size. */
        private const val SUB_DROP_RATIO = 0.14f

        /** How far above the baseline a superscript's own baseline sits, a fraction of the word's full font size. */
        private const val SUP_RAISE_RATIO = 0.38f
    }
}
