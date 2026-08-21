package com.riddleboox.app.riddle

/**
 * A rectangle on the page, in whole pixels, as the diary's own decisions see it.
 *
 * Its own type rather than [android.graphics.Rect] for the same reason
 * [com.riddleboox.app.ink.InkPoint] is not the Onyx SDK's `TouchPoint` and
 * [com.riddleboox.app.handwriting.TextRaster] is an interface: deciding *what*
 * to redraw is arithmetic, and arithmetic that imports a framework class cannot
 * be run off the device. Every method on the framework's `Rect` is a stub in a
 * JVM unit test — it throws rather than computes — so one import here would put
 * every dirty-rect decision in this package beyond the reach of a test.
 *
 * It meets the framework at the two places that actually paint: [PageRect] is
 * handed straight to `EpdController.invalidate`'s four coordinates by
 * [com.riddleboox.app.ink.EinkRefresher], and converted for `View.invalidate`
 * inside [com.riddleboox.app.ui.RegionView].
 */
data class PageRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left

    val height: Int get() = bottom - top

    /**
     * Integer halves, matching `android.graphics.Rect.centerX`'s own `shr 1`
     * rather than rounding — the blot this centres has been landing on those
     * exact pixels since the port, and half a pixel of drift is not worth
     * spending a difference nobody asked for.
     */
    val centreX: Int get() = (left + right) shr 1

    val centreY: Int get() = (top + bottom) shr 1

    /**
     * The smallest rect covering both.
     *
     * Plain min/max, where the framework's `union` additionally treats an empty
     * rect as absent. Nothing here is ever empty — every rect in this package
     * comes from [paddedRect] or [blotRect], both of which are wider than they
     * are asked for — so the two agree, and this one says what it does.
     */
    fun union(other: PageRect): PageRect = PageRect(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )
}

/** The smallest rect covering both, when either or both may be absent. */
fun union(a: PageRect?, b: PageRect?): PageRect? = when {
    a == null -> b
    b == null -> a
    else -> a.union(b)
}
