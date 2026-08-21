package com.riddleboox.app.handwriting

/** A skeleton sample in mask space (integer pixels). */
data class MaskPoint(val x: Int, val y: Int)

/**
 * Walk a thinned mask into ordered polyline strokes.
 *
 * Follows `trace` in references/Riddle/src/script.rs:128-196: endpoints
 * (degree 1) seed the walk first so strokes start where a pen would, leftover
 * pixels (closed loops) seed it afterwards, and strokes are finally ordered by
 * their leftmost pixel so the page is written left to right.
 *
 * Every run is kept, however short. A full stop at the reply's glyph height
 * thins to one or two pixels, so a minimum length cannot tell a mark from
 * noise — it only erases the diary's sentence endings. What the greedy walk
 * leaves behind on a closed loop comes back as a one-pixel run too, and that
 * costs nothing: it sits on the loop's own outline, under ink already drawn.
 */
object SkeletonTracer {

    fun trace(mask: GlyphMask): List<List<MaskPoint>> {
        val w = mask.width
        val h = mask.height
        val visited = BooleanArray(w * h)

        val starts = ArrayList<MaskPoint>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask.isInked(x, y) && neighbours(mask, x, y).size == 1) starts.add(MaskPoint(x, y))
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask.isInked(x, y)) starts.add(MaskPoint(x, y))
            }
        }

        val strokes = ArrayList<List<MaskPoint>>()
        for (start in starts) {
            if (visited[start.y * w + start.x]) continue
            val path = ArrayList<MaskPoint>()
            path.add(start)
            visited[start.y * w + start.x] = true
            var current = start
            while (true) {
                val next = neighbours(mask, current.x, current.y)
                    .firstOrNull { !visited[it.y * w + it.x] } ?: break
                visited[next.y * w + next.x] = true
                path.add(next)
                current = next
            }
            strokes.add(path)
        }
        return strokes.sortedBy { stroke -> stroke.minOf { it.x } }
    }

    /** 8-connected inked neighbours, scanned top-left to bottom-right (script.rs:133-143). */
    private fun neighbours(mask: GlyphMask, x: Int, y: Int): List<MaskPoint> {
        val out = ArrayList<MaskPoint>(8)
        for (dy in -1..1) {
            for (dx in -1..1) {
                if ((dx != 0 || dy != 0) && mask.isInked(x + dx, y + dy)) {
                    out.add(MaskPoint(x + dx, y + dy))
                }
            }
        }
        return out
    }
}
