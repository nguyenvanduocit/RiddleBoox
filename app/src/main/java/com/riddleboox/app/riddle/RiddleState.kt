package com.riddleboox.app.riddle

import com.riddleboox.app.handwriting.WritePlan
import com.riddleboox.app.handwriting.WritePoint
import com.riddleboox.app.ink.InkStroke

/**
 * The diary's state machine: Listening -> Drinking -> Thinking -> Replying ->
 * Listening. Adapted from `State` in references/Riddle/src/main.rs
 * (Help/Conjuring/MemoryShown are out of scope: an old conversation is turned
 * back to from the history screen rather than summoned onto the page by the
 * reply itself).
 *
 * A finished reply stays on the page as the *standing reply* and the pen opens
 * again immediately: the writer answers underneath what the diary just wrote,
 * the way a conversation on paper actually reads. Both hands of ink — the
 * writer's new page and the diary's standing reply — dissolve together when the
 * next turn is committed, so the page is clear for the answer to it.
 *
 * [Memorizing] is the one side path, with no main.rs ancestor: Listening ->
 * Memorizing -> Replying -> Listening, entered from the memorize label rather
 * than from ink, skipping Drinking because no page was handed over.
 */
sealed class RiddleState {

    /**
     * Waiting for the writer to pause. [standingReply] is the diary's last
     * answer, still on the page and readable while the next line is written —
     * null before the first turn.
     *
     * When the pen was last lifted is deliberately *not* here: it is something
     * the outside world reports, not something the diary decided, and it
     * arrives on the Onyx SDK's own thread. Keeping it in this class made every
     * pen event a read-modify-write of the whole state from that thread, racing
     * the tick that reads it — see [RiddleStateMachine.lastPenUpAtMs].
     */
    data class Listening(
        val standingReply: WritePlan? = null,
    ) : RiddleState()

    /**
     * Erasing the page, [DRINK_STAGES] stages at [DRINK_STAGE_MS] each
     * (main.rs:470-485) — the writer's committed strokes and the
     * [standingReply] they answered, dissolving on the same stages so the
     * page empties as one gesture. [dirtyRect] covers both and is computed
     * once at commit: the erased region doesn't move, so there's nothing to
     * recompute (main.rs's `region` field is likewise fixed for the whole
     * state).
     */
    data class Drinking(
        val committedStrokes: List<InkStroke>,
        val standingReply: WritePlan?,
        val dirtyRect: PageRect,
        val stage: Int,
        val nextStageAtMs: Long,
    ) : RiddleState()

    /**
     * Waiting on the diary. Nothing is drawn while it waits: the page stays
     * the blank one the ink dissolved into, and what the diary is doing is said
     * on the status line instead — see [com.riddleboox.app.riddle.decideThinking].
     *
     * [retryCount] and [retryAtMs] track a transient-failure retry (e.g. a
     * Wi-Fi blip) that hasn't yet exhausted its budget.
     */
    data class Thinking(
        val startedAtMs: Long,
        val retryCount: Int = 0,
        val retryAtMs: Long? = null,
    ) : RiddleState()

    /**
     * Waiting while the diary puts its kept memories in order against the
     * conversation so far — the writer tapped memorize
     * ([RiddleStateMachine.memorize]), asking for housekeeping rather than an
     * answer to a page. Nothing was handed over, so the page stays exactly as
     * it stood; [standingReply] rides here so a pass that fails can hand it
     * back to [Listening] untouched. No retry fields: memory tools have
     * already run by the time a failure comes back, and running the pass
     * again on a guess would keep the same facts twice — see
     * [decideMemorizing].
     */
    data class Memorizing(
        val startedAtMs: Long,
        val standingReply: WritePlan? = null,
    ) : RiddleState()

    /**
     * Writing the reply, 26 points per 14ms tick (main.rs:589-609), possibly
     * while the model is still producing it.
     *
     * The words themselves live in [RiddleStateMachine]'s two cursors — one
     * laying text out, one revealing it — because both are mutable and grow;
     * what stays here is the timing.
     *
     * Points reveal fast, but the e-ink panel cannot physically refresh nearly
     * that often — [pendingDirtyRect] accumulates newly-revealed bounds between
     * refreshes so [RiddleStateMachine] can flush them on its own, slower
     * cadence ([lastRefreshAtMs]) instead of once per tick, which used to queue
     * refresh requests faster than the panel could drain them and made the back
     * half of every reply crawl.
     */
    data class Replying(
        val nextTickAtMs: Long,
        val pendingDirtyRect: PageRect? = null,
        val lastRefreshAtMs: Long = 0L,
        /**
         * Whether every word is in hand. False means the pen may run out of
         * text mid-reply and have to wait, which is what [blotAt] marks.
         */
        val streamEnded: Boolean = false,
        /** Where the waiting blot is showing, or null while the pen is writing. */
        val blotAt: WritePoint? = null,
        val nextBlotAtMs: Long = 0L,
    ) : RiddleState()

}
