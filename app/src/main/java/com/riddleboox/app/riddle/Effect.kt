package com.riddleboox.app.riddle

import com.riddleboox.app.handwriting.SvgFigure
import com.riddleboox.app.handwriting.WriteStroke
import com.riddleboox.app.ink.InkStroke

/**
 * Something the diary has decided to do, described rather than done.
 *
 * [Diary] returns these; [RiddleStateMachine] carries them out against a real
 * panel, a real pen and a real network. It is the same split the page already
 * uses one layer down — [PageRenderState] is a description of a frame that
 * [com.riddleboox.app.ui.RegionView] paints without deciding anything — carried
 * up to cover the rest of what a turn does.
 *
 * What it buys: every decision in [Diary] can be checked by reading the list it
 * returns, on a JVM, in a millisecond. The alternative is what this replaced —
 * decisions expressed only as calls into a `View`, checkable only by watching a
 * tablet and believing your eyes.
 *
 * Order is meaningful. The list is applied front to back, and a refresh asked
 * for before the ink it is meant to show would show the page as it was.
 */
sealed class Effect {

    /** Paint this frame. */
    data class Render(val page: PageRenderState) : Effect()

    /**
     * Arm the reply: a pen at [startYPx] to lay words out, a cursor to reveal
     * what it lays down, and a fresh layer for the ink to accumulate on.
     */
    data class BeginReply(val startYPx: Float) : Effect()

    /** Lay [text] down at the nib — a reply that arrived whole, or the last of one. */
    data class WriteText(val text: String) : Effect()

    /**
     * Take streamed [delta] and write as much of it as has settled; the rest
     * waits for the delta that settles it (see
     * [com.riddleboox.app.reply.writableCut]).
     */
    data class FeedReply(val delta: String) : Effect()

    /** Lay [figure] on the page at the nib, sized and centred by the pen itself. */
    data class DrawFigure(val figure: SvgFigure) : Effect()

    /** Add just-revealed reply ink to that layer, and hint at what moved. */
    data class AppendReplyInk(val strokes: List<WriteStroke>, val area: PageRect) : Effect()

    /**
     * Retire the layer: the standing reply goes back to being drawn from
     * [PageRenderState.replyStrokes], which is what lets it dissolve point by
     * point instead of leaving as one baked block.
     */
    object ClearReplyLayer : Effect()

    /** Redraw [area] of the panel, in [mode]. */
    data class Refresh(val area: PageRect, val mode: RefreshMode) : Effect()

    /** The one deliberate flash: GC16 over the whole panel, which clears ghosting. */
    object FullRefresh : Effect()

    /** What the imprint line at the head of the page should read. */
    data class Status(val text: String) : Effect()

    /** Open or close the pen. Closed while the diary writes, so a resting hand leaves no ink. */
    data class PenInput(val enabled: Boolean) : Effect()

    /** Drop whatever the Onyx raw layer is still holding. */
    object ClearRawInk : Effect()

    /** Send this page to the diary and stream the answer back. */
    data class AskDiary(val page: List<InkStroke>) : Effect()

    /** Give up on the request in flight; nothing it produces will be read. */
    object AbandonRequest : Effect()

    /** Write one answered turn into the conversation on disk. */
    data class RecordTurn(val transcript: String, val reply: String) : Effect()

    /** Something worth finding in a log afterwards. */
    data class Note(val message: String, val warning: Boolean = false) : Effect()
}

/**
 * How much of the panel's ability a refresh is worth spending — the ladder is
 * described on [com.riddleboox.app.ink.EinkRefresher].
 */
enum class RefreshMode {
    /** Two-level and fastest, for a dissolve stage or a pulsing blot. */
    Fast,

    /** Tuned for strokes that were just written. */
    Handwriting,

    /** Full grey scale without the flash, for a whole region settling at once. */
    Quality,
}
