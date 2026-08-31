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
        val toClear = ArrayList<Int>()
        while (true) {
            val changedInFirstPhase = erodePhase(mask, Phase.FIRST, toClear)
            val changedInSecondPhase = erodePhase(mask, Phase.SECOND, toClear)
            if (!changedInFirstPhase && !changedInSecondPhase) return
        }
    }

    private fun erodePhase(mask: GlyphMask, phase: Phase, toClear: MutableList<Int>): Boolean {
        toClear.clear()
        for (y in 1 until mask.height - 1) {
            for (x in 1 until mask.width - 1) {
                if (!mask[x, y]) continue
                if (!canRemove(mask, x, y, phase)) continue
                toClear.add(y * mask.width + x)
            }
        }
        for (index in toClear) mask.bits[index] = false
        return toClear.isNotEmpty()
    }

    private fun canRemove(mask: GlyphMask, x: Int, y: Int, phase: Phase): Boolean {
        val north = mask[x, y - 1]
        val northEast = mask[x + 1, y - 1]
        val east = mask[x + 1, y]
        val southEast = mask[x + 1, y + 1]
        val south = mask[x, y + 1]
        val southWest = mask[x - 1, y + 1]
        val west = mask[x - 1, y]
        val northWest = mask[x - 1, y - 1]

        val crossings = backgroundToInkTransition(north, northEast, east) +
            backgroundToInkTransition(east, southEast, south) +
            backgroundToInkTransition(south, southWest, west) +
            backgroundToInkTransition(west, northWest, north)
        // One surrounding ink run means this pixel is not a bridge between arms.
        if (crossings != 1) return false

        val paired = minOf(
            pairOccupancy(northWest, north) + pairOccupancy(northEast, east) +
                pairOccupancy(southEast, south) + pairOccupancy(southWest, west),
            pairOccupancy(north, northEast) + pairOccupancy(east, southEast) +
                pairOccupancy(south, southWest) + pairOccupancy(west, northWest),
        )
        // Values below 2 are endpoints; values above 3 are interior ink.
        if (paired !in 2..3) return false

        // Alternating the held side keeps erosion centered instead of drifting.
        val heldInPhase = when (phase) {
            Phase.FIRST -> heldInFirstPhase(south, southWest, northWest, west)
            Phase.SECOND -> heldInSecondPhase(north, northEast, southEast, east)
        }
        return !heldInPhase
    }

    private fun backgroundToInkTransition(
        previous: Boolean,
        next: Boolean,
        afterNext: Boolean,
    ): Int = count(!previous && (next || afterNext))

    private fun pairOccupancy(first: Boolean, second: Boolean): Int = count(first || second)

    private fun heldInFirstPhase(
        south: Boolean,
        southWest: Boolean,
        northWest: Boolean,
        west: Boolean,
    ): Boolean = (south || southWest || !northWest) && west

    private fun heldInSecondPhase(
        north: Boolean,
        northEast: Boolean,
        southEast: Boolean,
        east: Boolean,
    ): Boolean = (north || northEast || !southEast) && east

    private fun count(condition: Boolean): Int = if (condition) 1 else 0

    private enum class Phase { FIRST, SECOND }
}
