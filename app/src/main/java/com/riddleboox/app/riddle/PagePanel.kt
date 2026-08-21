package com.riddleboox.app.riddle

import com.riddleboox.app.handwriting.WriteStroke

/**
 * Everything [RiddleStateMachine] needs from the page surface: painting it,
 * and asking the e-ink panel to show what changed.
 *
 * One interface over [com.riddleboox.app.ui.RegionView] plus
 * [com.riddleboox.app.ink.EinkRefresher] combined, rather than each kept
 * separate the way `MainActivity` wires them — every effect this class
 * performs pairs one call from each (paint, then ask the panel to show it),
 * so a fake standing in for the pair together is what a test of the state
 * machine actually needs. See [com.riddleboox.app.ui.RegionViewPanel] for the
 * real one and [com.riddleboox.app.riddle.Ticker] for the same seam already
 * cut this way for time.
 */
interface PagePanel {
    fun render(state: PageRenderState)
    fun beginReply()
    fun appendReplyStrokes(strokes: List<WriteStroke>, dirtyRect: PageRect)
    fun clearReplyLayer()
    fun drawingRect(): PageRect
    fun requestFastPartialRefresh(area: PageRect)
    fun requestHandwritingRefresh(area: PageRect)
    fun requestQualityPartialRefresh(area: PageRect)
    fun requestFullRefresh()
}

/**
 * What [RiddleStateMachine] needs to gate the pen — [com.riddleboox.app.ink.InkCaptureController]'s
 * own surface already matches this exactly, so it implements it directly
 * rather than going through an adapter the way [PagePanel] does.
 */
interface PenGate {
    fun setInputEnabled(enabled: Boolean)
    fun clearRawInkLayer()
}
