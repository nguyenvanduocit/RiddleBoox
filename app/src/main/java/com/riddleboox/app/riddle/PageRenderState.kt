package com.riddleboox.app.riddle

import com.riddleboox.app.handwriting.WritePoint
import com.riddleboox.app.handwriting.WriteStroke
import com.riddleboox.app.ink.InkStroke

/**
 * Everything [com.riddleboox.app.ui.RegionView] needs to paint one frame.
 * Computed fresh by [RiddleStateMachine] on every tick — the view itself holds
 * no animation state, it only draws what it is told (functional core / view is
 * the imperative shell's last, dumbest link).
 */
data class PageRenderState(
    /** User ink, still whole (Listening) or fading out ([dissolve] set). */
    val userStrokes: List<InkStroke> = emptyList(),

    /**
     * The standing reply, whole while the writer answers it or dissolving
     * with their page ([dissolve] set). While Replying the reply is painted
     * onto [com.riddleboox.app.ui.RegionView]'s own layer instead, point by
     * point.
     */
    val replyStrokes: List<WriteStroke> = emptyList(),

    /**
     * How far the ink-drinking dissolve has gotten, in step across
     * [userStrokes] and [replyStrokes] alike; null the rest of the time (draw
     * both whole).
     */
    val dissolve: DissolveProgress? = null,

    /**
     * Where the "thinking" blot sits on this frame, or null for no blot.
     *
     * It marks the same thing wherever it appears — the diary having no word
     * ready yet. Waiting on the first one it sits in the middle of the empty
     * page; waiting mid-reply it sits at the nib, where the next word will go.
     */
    val blotAt: WritePoint? = null,
) {
    companion object {
        val EMPTY = PageRenderState()
    }
}

/**
 * How far the ink-drinking dissolve has gotten, for one frame — [stage] of
 * [stages] total. One shared value for both hands of ink rather than two
 * separate nullable stages on [PageRenderState], since [userStrokes] and
 * [replyStrokes] always move together (see [RiddleState.Drinking]).
 */
data class DissolveProgress(
    val stage: Int,
    val stages: Int,
)
