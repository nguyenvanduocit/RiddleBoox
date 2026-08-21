package com.riddleboox.app.ink

import android.view.View
import com.riddleboox.app.riddle.PageRect
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

/**
 * Isolates all E-Ink refresh control.
 *
 * Two Onyx entry points matter here and they differ in scope, not just cost:
 * [EpdController.invalidate] repaints the rect it is given, while
 * [EpdController.refreshScreen] repaints the whole panel and takes a [View]
 * only as a handle. Everything routine goes through the rect-scoped calls;
 * [requestFullRefresh] is the one deliberate flash, spent on a schedule by
 * [RiddleStateMachine][com.riddleboox.app.riddle.RiddleStateMachine].
 *
 * The mode ladder, cheapest first: `DU` (2-level, fastest, leaves ghosting) ->
 * `HAND_WRITING_REPAINT_MODE` (tuned for fresh strokes) -> `GU` (16 grey
 * levels with no polarity inversion, so a large area can settle without the
 * panel flashing) -> `GC` (GC16, inverts the whole area — this is the flash).
 */
class EinkRefresher {
    /**
     * Let a new surface inherit the app's own update mode. The SDK's default
     * is to force a full-screen GC for every surface it sees created, which
     * cost two flashes just to attach the pen (measured: `SurfaceFlinger:
     * refresh screen (0, 0 - 1872, 1404) waveform_mode 2`, twice, at
     * `InkCaptureController.attach`). The page schedules its own refreshes.
     */
    fun configureNewSurfaces() {
        runCatching { EpdController.useGCForNewSurface(false) }
    }

    /** The flash: GC16 over the whole panel, which is what clears ghosting. */
    fun requestFullRefresh(view: View) {
        val refreshed = runCatching {
            EpdController.refreshScreen(view, UpdateMode.GC)
            true
        }.getOrDefault(false)
        if (!refreshed) view.invalidate()
    }

    fun requestFastPartialRefresh(view: View, area: PageRect) {
        runCatching {
            EpdController.invalidate(view, area.left, area.top, area.right, area.bottom, UpdateMode.DU)
        }
    }

    /** Same rect-scoped invalidate as [requestFastPartialRefresh], but the mode
     * tuned for freshly-written strokes rather than a dissolve/pulse toggle. */
    fun requestHandwritingRefresh(view: View, area: PageRect) {
        runCatching {
            EpdController.invalidate(view, area.left, area.top, area.right, area.bottom, UpdateMode.HAND_WRITING_REPAINT_MODE)
        }
    }

    /**
     * Settle [area] properly — full grey scale, no flash. For the moments a
     * whole region changes at once (a page of ink finishing its dissolve),
     * where [requestFastPartialRefresh]'s two-level `DU` would leave the area
     * visibly dirty but a `GC` would blink the entire panel.
     */
    fun requestQualityPartialRefresh(view: View, area: PageRect) {
        runCatching {
            EpdController.invalidate(view, area.left, area.top, area.right, area.bottom, UpdateMode.GU)
        }
    }
}
