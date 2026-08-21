package com.riddleboox.app.handwriting

/** Builds a mask from ASCII art rows: '#' is inked, anything else is blank. */
fun maskOf(vararg rows: String): GlyphMask {
    val height = rows.size
    val width = rows.firstOrNull()?.length ?: 0
    require(rows.all { it.length == width }) { "all rows must be $width wide" }
    val mask = GlyphMask(width, height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            mask[x, y] = rows[y][x] == '#'
        }
    }
    return mask
}

/** Renders a mask back to ASCII art, so failures print a readable diff. */
fun GlyphMask.render(): List<String> = (0 until height).map { y ->
    (0 until width).map { x -> if (this[x, y]) '#' else '.' }.joinToString("")
}

/** True when any 2x2 square is fully inked, i.e. the mask is not 1px thin. */
fun GlyphMask.hasSolidBlock(): Boolean {
    for (y in 0 until height - 1) {
        for (x in 0 until width - 1) {
            if (this[x, y] && this[x + 1, y] && this[x, y + 1] && this[x + 1, y + 1]) return true
        }
    }
    return false
}

/**
 * Stands in for [PaintTextRaster]: 10px per character, and every word
 * rasterizes to one 1px vertical bar at x=5, y=2..8 (7 points).
 */
class FakeRaster : TextRaster {
    override fun measure(text: String, fontSizePx: Float): Float = text.length * 10f

    override fun rasterize(text: String, fontSizePx: Float): GlyphMask {
        val mask = GlyphMask((text.length * 10).coerceAtLeast(1), 20)
        for (y in 2..8) mask[5, y] = true
        return mask
    }
}
