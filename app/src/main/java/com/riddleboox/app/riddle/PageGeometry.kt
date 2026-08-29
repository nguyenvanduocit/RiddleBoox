package com.riddleboox.app.riddle

import com.riddleboox.app.handwriting.HandwritingPlanner
import com.riddleboox.app.handwriting.WritePoint
import com.riddleboox.app.handwriting.WriteStroke
import com.riddleboox.app.ink.InkStroke

/**
 * Where the diary's ink sits on the page, and which part of the panel has to be
 * redrawn for it.
 *
 * All of it is arithmetic over points: no view is measured here and no
 * framework class is touched, so every answer below can be checked in a test
 * rather than on a tablet. The page's own size arrives as a parameter, because
 * the one thing this file cannot compute is how big the paper is.
 */

/** Buffer around a dirty rect so antialiased edges don't clip. */
internal const val DIRTY_RECT_PADDING_PX = 24f

/** Half-width of a thinking blot's fixed refresh area. */
internal const val BLOT_RADIUS_PX = 20f

/** Where a reply starts when it starts at the top of a page of its own. */
internal const val REPLY_TOP_PX = 100f

/** Clear space kept at the foot of the page before the pen turns it. */
internal const val REPLY_BOTTOM_PX = 80f

/** Air between the writer's last line and the diary's answer to it. */
internal const val REPLY_GAP_PX = 60f

/** Bounding box of every user-ink point, padded — null when [strokes] is empty. */
fun inkBounds(strokes: List<InkStroke>): PageRect? {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    var any = false
    for (stroke in strokes) for (p in stroke.points) {
        any = true
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
    }
    if (!any) return null
    return paddedRect(minX, minY, maxX, maxY)
}

/** Bounding box of every reply-ink point, padded — null when [strokes] is empty. */
fun writeBounds(strokes: List<WriteStroke>): PageRect? {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    var any = false
    for (stroke in strokes) for (p in stroke.points) {
        any = true
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
    }
    if (!any) return null
    return paddedRect(minX, minY, maxX, maxY)
}

/** A bounding box grown by [DIRTY_RECT_PADDING_PX] on every side. */
fun paddedRect(minX: Float, minY: Float, maxX: Float, maxY: Float): PageRect = PageRect(
    (minX - DIRTY_RECT_PADDING_PX).toInt(),
    (minY - DIRTY_RECT_PADDING_PX).toInt(),
    (maxX + DIRTY_RECT_PADDING_PX).toInt(),
    (maxY + DIRTY_RECT_PADDING_PX).toInt(),
)

/** The fixed area a blot occupies, wherever on the page it is drawn. */
fun blotRect(at: WritePoint): PageRect = PageRect(
    (at.x - BLOT_RADIUS_PX).toInt(),
    (at.y - BLOT_RADIUS_PX).toInt(),
    (at.x + BLOT_RADIUS_PX).toInt(),
    (at.y + BLOT_RADIUS_PX).toInt(),
)

/** The lowest point any of [strokes] reaches — 0 when there is no ink at all. */
fun bottomOf(strokes: List<InkStroke>): Float =
    strokes.asSequence().flatMap { it.points.asSequence() }.maxOfOrNull { it.y } ?: 0f

/** The lowest point any of [strokes] reaches — 0 when there is no reply ink at all. */
fun bottomOfWrite(strokes: List<WriteStroke>): Float =
    strokes.asSequence().flatMap { it.points.asSequence() }.maxOfOrNull { it.y } ?: 0f

/**
 * Where a reply written underneath [strokes] starts — just below them, but
 * never so low that the page has no line left to give it.
 *
 * Writing on the last line of the page is what
 * [com.riddleboox.app.handwriting.WriteCursor] calls full, and a pen that
 * starts there turns the page after every single line: each turn is a
 * full-screen flash, and the words land below the bottom edge where nothing
 * shows them. The writer who fills the page to the foot and has no key
 * configured is exactly who most needs to read the excuse.
 *
 * @param pageHeightPx the drawing area's height; 0 before the view has
 * measured, which the clamp handles by falling back to [REPLY_TOP_PX].
 * @param lineHeightPx the reply pen's actual line height — must match what
 * [com.riddleboox.app.handwriting.WriteCursor.hasRoomForAnotherLine] is
 * measuring against, or this clamp and that check disagree about how much
 * foot of the page a line still fits in.
 */
fun replyStartBelow(
    strokes: List<InkStroke>,
    pageHeightPx: Int,
    lineHeightPx: Float = HandwritingPlanner.DEFAULT_LINE_HEIGHT_PX,
): Float {
    val below = bottomOf(strokes) + REPLY_GAP_PX
    // The lowest start WriteCursor.hasRoomForAnotherLine still accepts.
    val lowest = pageHeightPx - REPLY_BOTTOM_PX - lineHeightPx * 2
    return below.coerceIn(REPLY_TOP_PX, maxOf(REPLY_TOP_PX, lowest))
}

/**
 * Where a debug write lands underneath the diary's own standing reply — same
 * clamp as [replyStartBelow], measured against reply ink ([WriteStroke])
 * rather than the writer's ([InkStroke]). See
 * [RiddleStateMachine.debugWriteReplyInk], the one caller: a debug tool that
 * appends ink after whatever the diary last wrote, the way a second answer
 * would continue underneath the first.
 */
fun replyWriteStartBelow(
    strokes: List<WriteStroke>,
    pageHeightPx: Int,
    lineHeightPx: Float = HandwritingPlanner.DEFAULT_LINE_HEIGHT_PX,
): Float {
    val below = bottomOfWrite(strokes) + REPLY_GAP_PX
    val lowest = pageHeightPx - REPLY_BOTTOM_PX - lineHeightPx * 2
    return below.coerceIn(REPLY_TOP_PX, maxOf(REPLY_TOP_PX, lowest))
}
