package com.riddleboox.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.riddleboox.app.handwriting.HandwritingPlanner
import com.riddleboox.app.handwriting.WritePoint
import com.riddleboox.app.handwriting.WriteStroke
import com.riddleboox.app.handwriting.replyStrokeWidthPx
import com.riddleboox.app.ink.inkRadiusPx
import com.riddleboox.app.riddle.PageRect
import com.riddleboox.app.riddle.PageRenderState
import com.riddleboox.app.riddle.isDissolvedAt

/**
 * The page canvas: white page + visible border around the pen capture region,
 * plus whatever [RiddleStateMachine][com.riddleboox.app.riddle.RiddleStateMachine]
 * hands it to paint this frame. Sits ABOVE the SurfaceView (which is only the
 * Onyx raw pen host) — the raw drawing layer renders live ink on top of this
 * view while listening; this view takes over once a page is committed.
 *
 * Two ways to paint, both driven from outside. [render] replaces the whole
 * picture from a [PageRenderState] — every state but Replying works that way,
 * and holds no animation state here. Replying instead bakes its ink into a
 * layer via [beginReply] / [appendReplyStrokes] / [clearReplyLayer], because a
 * reply grows point by point and repainting the whole of it every 14ms is what
 * made long replies crawl.
 */
class RegionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var current: PageRenderState = PageRenderState.EMPTY

    /** The glyph height the diary's hand is configured to write at — see [replyStrokeWidthPx]. */
    var replyFontSizePx: Float = HandwritingPlanner.DEFAULT_FONT_SIZE_PX
        set(value) {
            field = value
            replyPaint.strokeWidth = replyStrokeWidthPx(value)
        }

    /**
     * Reply ink already written this turn, baked into a bitmap the way the
     * e-ink panel itself keeps what it has drawn: while Replying, a tick only
     * paints the handful of points it just revealed onto this layer and the
     * frame composites it in one blit, instead of replaying every stroke
     * revealed so far (which grew a reply's cost to O(N^2)).
     */
    private var replyLayer: Bitmap? = null
    private var replyLayerCanvas: Canvas? = null
    private var replyLayerActive: Boolean = false

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val replyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = replyStrokeWidthPx(HandwritingPlanner.DEFAULT_FONT_SIZE_PX)
    }
    private val blotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    /** Replace what's on the page and schedule a redraw. */
    fun render(state: PageRenderState) {
        current = state
        invalidate()
    }

    /**
     * Start a fresh reply turn: allocate (first time) or wipe the layer
     * [appendReplyStrokes] paints onto, so a turn never inherits the previous
     * turn's ink.
     */
    fun beginReply() {
        ensureReplyLayer()
        replyLayerCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        replyLayerActive = true
        invalidate()
    }

    /**
     * Paint [strokes] — only the points just revealed — onto the reply layer,
     * costing O(strokes) rather than O(everything revealed so far), then ask
     * for a redraw. [dirtyRect] is only a hint about what moved; the platform
     * has been free to widen it since API 21, and the area that actually
     * matters is the one the state machine hands the e-ink refresher.
     */
    fun appendReplyStrokes(strokes: List<WriteStroke>, dirtyRect: PageRect) {
        val canvas = replyLayerCanvas ?: return
        if (!replyLayerActive) return
        for (stroke in strokes) paintReplyStroke(canvas, stroke)
        invalidate(Rect(dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom))
    }

    /**
     * End the reply turn — go back to drawing [PageRenderState.replyStrokes]
     * in full, which the standing reply needs: it dissolves point by point on
     * the next commit, where a baked layer could only leave as one block.
     */
    fun clearReplyLayer() {
        replyLayerCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        replyLayerActive = false
    }

    private fun ensureReplyLayer() {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val existing = replyLayer
        if (existing != null && existing.width == w && existing.height == h) return
        existing?.recycle()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        replyLayer = bitmap
        replyLayerCanvas = Canvas(bitmap)
    }

    /**
     * One stroke onto the reply layer — same shape as [drawReplyStrokes]'s
     * inner loop (lone point -> dot, otherwise a run of segments) without the
     * dissolve mask, which only ever applies once the layer is already retired.
     */
    private fun paintReplyStroke(canvas: Canvas, stroke: WriteStroke) {
        val points = stroke.points
        if (points.isEmpty()) return
        if (points.size == 1) {
            replyPaint.style = Paint.Style.FILL
            canvas.drawCircle(points[0].x, points[0].y, replyPaint.strokeWidth / 2f, replyPaint)
            return
        }
        replyPaint.style = Paint.Style.STROKE
        for (i in 1 until points.size) {
            canvas.drawLine(points[i - 1].x, points[i - 1].y, points[i].x, points[i].y, replyPaint)
        }
    }

    /** The writing area is the whole view — no border held back from the edge. */
    fun drawingRect(): PageRect = PageRect(0, 0, width, height)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        if (width <= 0 || height <= 0) return
        drawUserStrokes(canvas)
        if (replyLayerActive) {
            replyLayer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        } else {
            drawReplyStrokes(canvas)
        }
        current.blotAt?.let { drawBlot(canvas, it) }
    }

    private fun drawUserStrokes(canvas: Canvas) {
        val dissolve = current.dissolve
        for (stroke in current.userStrokes) {
            val points = stroke.points
            if (points.isEmpty()) continue
            if (points.size == 1) {
                val p = points[0]
                if (dissolve != null && isDissolvedAt(p.x.toInt(), p.y.toInt(), dissolve.stage, dissolve.stages)) continue
                strokePaint.style = Paint.Style.FILL
                canvas.drawCircle(p.x, p.y, inkRadiusPx(p.pressure), strokePaint)
                continue
            }
            strokePaint.style = Paint.Style.STROKE
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                if (dissolve != null &&
                    (isDissolvedAt(a.x.toInt(), a.y.toInt(), dissolve.stage, dissolve.stages) ||
                        isDissolvedAt(b.x.toInt(), b.y.toInt(), dissolve.stage, dissolve.stages))
                ) {
                    continue
                }
                strokePaint.strokeWidth = inkRadiusPx(a.pressure) + inkRadiusPx(b.pressure)
                canvas.drawLine(a.x, a.y, b.x, b.y, strokePaint)
            }
        }
    }

    private fun drawReplyStrokes(canvas: Canvas) {
        val dissolve = current.dissolve
        for (stroke in current.replyStrokes) {
            val points = stroke.points
            if (points.isEmpty()) continue
            if (points.size == 1) {
                val p = points[0]
                if (dissolve != null && isDissolvedAt(p.x.toInt(), p.y.toInt(), dissolve.stage, dissolve.stages)) continue
                replyPaint.style = Paint.Style.FILL
                canvas.drawCircle(p.x, p.y, replyPaint.strokeWidth / 2f, replyPaint)
                continue
            }
            replyPaint.style = Paint.Style.STROKE
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                if (dissolve != null &&
                    (isDissolvedAt(a.x.toInt(), a.y.toInt(), dissolve.stage, dissolve.stages) ||
                        isDissolvedAt(b.x.toInt(), b.y.toInt(), dissolve.stage, dissolve.stages))
                ) {
                    continue
                }
                canvas.drawLine(a.x, a.y, b.x, b.y, replyPaint)
            }
        }
    }

    /**
     * The "thinking" mark at [at] (main.rs:536-541 used a plain 28x28 square —
     * a few overlapping circles read as a drop of ink where a hard square
     * reads as a loading spinner).
     */
    private fun drawBlot(canvas: Canvas, at: WritePoint) {
        canvas.drawCircle(at.x, at.y, 9f, blotPaint)
        canvas.drawCircle(at.x - 7f, at.y + 5f, 5f, blotPaint)
        canvas.drawCircle(at.x + 8f, at.y + 3f, 4f, blotPaint)
        canvas.drawCircle(at.x - 2f, at.y - 8f, 3.5f, blotPaint)
    }
}
