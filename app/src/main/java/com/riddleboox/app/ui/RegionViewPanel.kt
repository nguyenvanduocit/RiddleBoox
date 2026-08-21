package com.riddleboox.app.ui

import com.riddleboox.app.handwriting.WriteStroke
import com.riddleboox.app.ink.EinkRefresher
import com.riddleboox.app.riddle.PageRect
import com.riddleboox.app.riddle.PagePanel
import com.riddleboox.app.riddle.PageRenderState

/** The real [PagePanel]: pure delegation to the [RegionView] it paints and the [EinkRefresher] that shows it. */
class RegionViewPanel(
    private val regionView: RegionView,
    private val refresher: EinkRefresher,
) : PagePanel {
    override fun render(state: PageRenderState) = regionView.render(state)
    override fun beginReply() = regionView.beginReply()
    override fun appendReplyStrokes(strokes: List<WriteStroke>, dirtyRect: PageRect) =
        regionView.appendReplyStrokes(strokes, dirtyRect)
    override fun clearReplyLayer() = regionView.clearReplyLayer()
    override fun drawingRect(): PageRect = regionView.drawingRect()
    override fun requestFastPartialRefresh(area: PageRect) = refresher.requestFastPartialRefresh(regionView, area)
    override fun requestHandwritingRefresh(area: PageRect) = refresher.requestHandwritingRefresh(regionView, area)
    override fun requestQualityPartialRefresh(area: PageRect) = refresher.requestQualityPartialRefresh(regionView, area)
    override fun requestFullRefresh() = refresher.requestFullRefresh(regionView)
}
