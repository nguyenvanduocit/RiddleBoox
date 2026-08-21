package com.riddleboox.app.handwriting

/**
 * Guo-Hall thinning: erode a filled glyph mask down to 1px-wide pen paths.
 *
 * This is the step the reference implementation spends in `thin`
 * (references/Riddle/src/script.rs:68-124), with Guo-Hall's deletion rule in
 * place of Zhang-Suen's. The rule matters for a diary that writes Vietnamese:
 * Zhang-Suen's two passes delete complementary pixels along a thin diagonal
 * and eat the whole run, and at the reply's glyph height every tone mark —
 * sắc, huyền, the stroke inside ắ — is exactly that, a short thin diagonal. A
 * `á` whose mark is 7x9 pixels of ink comes out of Zhang-Suen as a broken
 * 4-pixel stub and out of Guo-Hall as an unbroken 8-pixel line on the mark's
 * own axis.
 *
 * Border pixels are never examined (the neighbourhood would run off the mask),
 * which is why the rasterizer pads the mask before handing it over.
 */
object Skeletonizer {

    /** Thins [mask] in place until no further pixel can be removed. */
    fun thin(mask: GlyphMask) {
        val w = mask.width
        val h = mask.height
        val toClear = ArrayList<Int>()
        while (true) {
            var changed = false
            for (phase in 0..1) {
                toClear.clear()
                for (y in 1 until h - 1) {
                    for (x in 1 until w - 1) {
                        if (!mask[x, y]) continue
                        val n = mask[x, y - 1]      // p2
                        val ne = mask[x + 1, y - 1] // p3
                        val e = mask[x + 1, y]      // p4
                        val se = mask[x + 1, y + 1] // p5
                        val s = mask[x, y + 1]      // p6
                        val sw = mask[x - 1, y + 1] // p7
                        val we = mask[x - 1, y]     // p8
                        val nw = mask[x - 1, y - 1] // p9

                        // Connectivity: how many separate runs of ink surround
                        // the pixel. Deleting is only safe when there is one,
                        // i.e. the pixel is not a bridge between two arms.
                        val crossings = count(!n && (ne || e)) +
                            count(!e && (se || s)) +
                            count(!s && (sw || we)) +
                            count(!we && (nw || n))
                        if (crossings != 1) continue

                        // Ink around the pixel, counted in opposite pairings.
                        // Below 2 is an endpoint the pen still needs; above 3
                        // is interior ink with more eroding to do first.
                        val paired = minOf(
                            count(nw || n) + count(ne || e) + count(se || s) + count(sw || we),
                            count(n || ne) + count(e || se) + count(s || sw) + count(we || nw),
                        )
                        if (paired < 2 || paired > 3) continue

                        // Each phase erodes from one side, so the skeleton
                        // settles on the middle instead of drifting.
                        val holdsThisPhase = if (phase == 0) {
                            (s || sw || !nw) && we
                        } else {
                            (n || ne || !se) && e
                        }
                        if (!holdsThisPhase) toClear.add(y * w + x)
                    }
                }
                if (toClear.isNotEmpty()) {
                    changed = true
                    for (i in toClear) mask.bits[i] = false
                }
            }
            if (!changed) return
        }
    }

    private fun count(condition: Boolean): Int = if (condition) 1 else 0
}
