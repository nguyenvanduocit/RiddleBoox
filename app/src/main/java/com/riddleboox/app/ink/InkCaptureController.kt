package com.riddleboox.app.ink

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import com.riddleboox.app.riddle.PenGate
import com.riddleboox.app.settings.PenStyle

/**
 * Isolates all Onyx pen SDK usage. Falls back to plain MotionEvent input when
 * raw drawing is unavailable (emulator / non-BOOX), mirroring Inka's approach.
 *
 * Implements [PenGate] directly — [RiddleStateMachine][com.riddleboox.app.riddle.RiddleStateMachine]
 * only ever gates the pen through those two methods, never drives the SDK.
 */
class InkCaptureController(
    context: Context,
    private val surfaceView: View,
    private val strokeStore: StrokeStore,
    private val callbacks: Callbacks,
    private val pageRectProvider: () -> Rect,
    private val penStyle: PenStyle = PenStyle.Default,
    private val penWidthScale: Float = 1f,
) : PenGate {
    interface Callbacks {
        fun onPenDown(): Boolean
        fun onPenUp()
        fun onStrokeCaptured(strokes: List<InkStroke>)
    }

    private val appContext = context.applicationContext
    private var touchHelper: TouchHelper? = null
    private var rawDrawingActive = false
    private var inputEnabled = true
    private var strokeAccepted = false
    private var fallbackStrokeAccepted = false
    private var lastRawTouchPointList: TouchPointList? = null
    private var lastLimit: Rect? = null

    private val rawCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, touchPoint: TouchPoint) {
            if (!inputEnabled) return
            strokeAccepted = callbacks.onPenDown()
            if (!strokeAccepted) {
                Log.d(TAG, "pen rejected: the diary is not listening")
                clearRejectedRawStroke()
                return
            }
            lastRawTouchPointList = null
            strokeStore.beginStroke(touchPoint.toInkPoint())
            Log.d(TAG, "pen begin")
        }

        override fun onEndRawDrawing(b: Boolean, touchPoint: TouchPoint) {
            try {
                if (!inputEnabled || !strokeAccepted) return
                // The SDK's own point list is the whole stroke, so it replaces
                // what the move callbacks accumulated — all of it, not just its
                // ends. Keeping only first and last turned every letter into
                // one straight line, and the diary answered that the ink had
                // blurred, because that is genuinely all it was sent.
                val finalList = finalRawTouchPointList(touchPoint)
                if (finalList != null && !finalList.isEmpty()) {
                    val points = finalList.points
                    strokeStore.beginStroke(points.first().toInkPoint())
                    points.drop(1).forEach { strokeStore.addPoint(it.toInkPoint()) }
                } else {
                    strokeStore.addPoint(touchPoint.toInkPoint())
                }
                strokeStore.finishCurrent()
                callbacks.onPenUp()
                publishCapturedInk()
                Log.d(TAG, "pen end, page holds ${strokeStore.count} stroke(s)")
            } finally {
                lastRawTouchPointList = null
                strokeAccepted = false
            }
        }

        override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {
            if (!inputEnabled || !strokeAccepted) return
            strokeStore.addPoint(touchPoint.toInkPoint())
        }

        override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {
            if (!inputEnabled || !strokeAccepted) return
            // Guarded like the same list is in onEndRawDrawing: `first()` on an
            // empty one throws, and it throws on the SDK's own callback thread.
            if (touchPointList.isEmpty()) return
            lastRawTouchPointList = TouchPointList(touchPointList)
            strokeStore.beginStroke(touchPointList.points.first().toInkPoint())
            touchPointList.points.drop(1).forEach { strokeStore.addPoint(it.toInkPoint()) }
        }

        override fun onBeginRawErasing(b: Boolean, touchPoint: TouchPoint) = Unit
        override fun onEndRawErasing(b: Boolean, touchPoint: TouchPoint) = Unit
        override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) = Unit
        override fun onRawErasingTouchPointListReceived(touchPointList: TouchPointList) = Unit

        override fun onPenUpRefresh(refreshRect: RectF) {
            if (!inputEnabled || !strokeAccepted) return
            publishCapturedInk()
        }
    }

    fun attach() {
        installFallbackTouchHandler()
        rawDrawingActive = runCatching {
            val limit = pageRectProvider()
            val helper = TouchHelper.create(surfaceView, rawCallback)
                .setStrokeWidth(strokeWidthPx())
                .setLimitRect(limit, emptyList())
                .openRawDrawing()
            applyRawDrawingSettings(helper)
            applyInputGate(helper)
            touchHelper = helper
            Log.i(TAG, "Onyx raw drawing attached, limit=$limit")
            true
        }.getOrElse { error ->
            Log.i(TAG, "Onyx raw drawing unavailable; fallback: ${error::class.java.simpleName}")
            false
        }
    }

    fun isRawDrawingActive(): Boolean = rawDrawingActive

    fun refreshLimits() {
        runCatching {
            val helper = touchHelper ?: return@runCatching
            val limit = pageRectProvider()
            if (limit == lastLimit) return@runCatching
            helper.setLimitRect(limit, emptyList())
            lastLimit = limit
            Log.d(TAG, "pen limited to $limit")
        }.onFailure { error ->
            Log.i(TAG, "limit refresh failed: ${error::class.java.simpleName}")
        }
    }

    /**
     * Gate pen input on/off without tearing down the raw drawing session — used
     * while the diary is dissolving/thinking/replying, when strokes must not
     * land on the page. All raw and fallback callbacks already check
     * [inputEnabled] themselves; this also tells the SDK so it stops rendering
     * ink for a pen that touches down while disabled.
     */
    override fun setInputEnabled(enabled: Boolean) {
        inputEnabled = enabled
        runCatching { touchHelper?.let { applyInputGate(it) } }
    }

    /**
     * Rendering and input are one gate, always moved together. Ink the SDK
     * draws but the diary never receives is a lie on the page; a pen the SDK
     * reports but never draws is a page that silently swallows the writing.
     */
    private fun applyInputGate(helper: TouchHelper) {
        helper.setRawDrawingRenderEnabled(inputEnabled)
        helper.setRawDrawingEnabled(inputEnabled)
    }

    fun detach() {
        runCatching {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.closeRawDrawing()
        }
        touchHelper = null
        rawDrawingActive = false
    }

    override fun clearRawInkLayer() {
        runCatching {
            restartSession(touchHelper ?: return@runCatching)
        }.onFailure { error ->
            Log.i(TAG, "raw clear failed: ${error::class.java.simpleName}")
        }
    }

    /**
     * Wipe the drawn ink and stand the raw-drawing session back up.
     *
     * `restartRawDrawing` tears the session down to nothing, so everything
     * [attach] established has to be established again — the limit rect above
     * all. Restarting without it left the SDK with no region to accept the pen
     * in, so the very next turn saw no `onBeginRawDrawing` at all and the pen
     * looked dead while the diary sat waiting in Listening.
     */
    private fun restartSession(helper: TouchHelper) {
        val limit = pageRectProvider()
        helper.setRawDrawingRenderEnabled(false)
        helper.restartRawDrawing()
        helper.setLimitRect(limit, emptyList())
        applyRawDrawingSettings(helper)
        applyInputGate(helper)
        Log.d(TAG, "raw session restarted, limit=$limit, input=$inputEnabled")
    }

    private fun installFallbackTouchHandler() {
        surfaceView.setOnTouchListener { _, event ->
            if (rawDrawingActive || !inputEnabled) return@setOnTouchListener false
            handleFallbackInk(event)
            true
        }
    }

    private fun handleFallbackInk(event: MotionEvent) {
        val point = InkPoint(event.x, event.y, event.pressure)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fallbackStrokeAccepted = callbacks.onPenDown()
                if (!fallbackStrokeAccepted) return
                strokeStore.beginStroke(point)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!fallbackStrokeAccepted) return
                for (i in 0 until event.historySize) {
                    strokeStore.addPoint(InkPoint(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalPressure(i)))
                }
                strokeStore.addPoint(point)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!fallbackStrokeAccepted) return
                strokeStore.addPoint(point)
                strokeStore.finishCurrent()
                callbacks.onPenUp()
                publishCapturedInk()
                fallbackStrokeAccepted = false
            }
        }
    }

    private fun publishCapturedInk() {
        val strokes = strokeStore.strokes
        surfaceView.post { callbacks.onStrokeCaptured(strokes) }
    }

    private fun applyRawDrawingSettings(helper: TouchHelper) {
        helper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
        helper.setStrokeColor(Color.BLACK)
        helper.setStrokeWidth(strokeWidthPx())
        helper.setRawDrawingRenderEnabled(true)
        helper.setPenUpRefreshEnabled(true)
        helper.setFilterRepeatMovePoint(true)
        helper.enableFingerTouch(false)
        helper.setRawDrawingEnabled(true)
    }

    /**
     * Wipe the ink the SDK already drew for a stroke the diary refused —
     * touching the pen down while a turn is in flight.
     */
    private fun clearRejectedRawStroke() {
        runCatching {
            restartSession(touchHelper ?: return@runCatching)
        }
    }

    private fun finalRawTouchPointList(touchPoint: TouchPoint): TouchPointList? {
        val currentList = lastRawTouchPointList?.let { TouchPointList(it) } ?: return null
        if (currentList.isEmpty() || !currentList.last().sameSampleAs(touchPoint)) {
            currentList.add(TouchPoint(touchPoint))
        }
        return currentList
    }

    private fun TouchPoint.sameSampleAs(other: TouchPoint): Boolean =
        getTimestamp() == other.getTimestamp() &&
            getX() == other.getX() &&
            getY() == other.getY() &&
            getPressure() == other.getPressure()

    /**
     * The nib width scales with [penStyle]/[penWidthScale] the same way the
     * repaint's own [com.riddleboox.app.ink.inkRadiusPx] does at full
     * pressure — [STROKE_WIDTH_MM] is that relationship anchored to
     * [PenStyle.Ballpoint] at scale 1.0, so a writer who never opens the pen
     * settings gets the exact millimetre value this was tuned against.
     */
    private fun strokeWidthPx(): Float {
        val xdpi = appContext.resources.displayMetrics.xdpi
            .takeIf { it.isFinite() && it > 0f }
            ?: FALLBACK_XDPI
        val diameterRatio = (penStyle.maxRadiusPx * 2f * penWidthScale) / DEFAULT_NIB_DIAMETER_PX
        return xdpi / MILLIMETERS_PER_INCH * STROKE_WIDTH_MM * diameterRatio
    }

    /**
     * Onyx raw pen input reports pressure on the digitizer's native scale
     * (observed 462-3077 on this Note Air 2, i.e. well within a 0-4096-ish
     * range), not the ~0..1 MotionEvent normally reports — normalize through
     * the SDK's own device-reported ceiling so both input paths land in the
     * same 0..1 range [StrokeStore]/[RegionView]/[PageRasterizer] expect.
     */
    private fun TouchPoint.toInkPoint(): InkPoint {
        val ceiling = com.onyx.android.sdk.api.device.epd.EpdController.MAX_TOUCH_PRESSURE
            .takeIf { it.isFinite() && it > 0f } ?: FALLBACK_RAW_PRESSURE_MAX
        return InkPoint(
            x = getX(),
            y = getY(),
            pressure = (getPressure() / ceiling).coerceIn(0f, 1f),
        )
    }

    companion object {
        private const val TAG = "InkCaptureController"
        private const val MILLIMETERS_PER_INCH = 25.4f
        private const val FALLBACK_XDPI = 300f
        /**
         * Live nib width, in millimetres so it stays the same pen on a panel
         * of any density. 0.6mm is 5.4px at this device's 227dpi, which is
         * where [com.riddleboox.app.ink.inkRadiusPx] tops out — the two have
         * to agree or the stroke jumps when the repaint takes over.
         */
        private const val STROKE_WIDTH_MM = 0.6f
        /** [PenStyle.Ballpoint]'s full-pressure diameter — what [STROKE_WIDTH_MM] was tuned against. */
        private val DEFAULT_NIB_DIAMETER_PX = PenStyle.Default.maxRadiusPx * 2f
        private const val FALLBACK_RAW_PRESSURE_MAX = 4096f
    }
}