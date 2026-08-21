package com.riddleboox.app.onboarding

import android.util.Log
import com.riddleboox.app.handwriting.HandwritingPlanner
import com.riddleboox.app.handwriting.ReplyRevealCursor
import com.riddleboox.app.handwriting.WriteCursor
import com.riddleboox.app.ink.EinkRefresher
import com.riddleboox.app.riddle.PageRect
import com.riddleboox.app.riddle.PageRenderState
import com.riddleboox.app.riddle.REPLY_BOTTOM_PX
import com.riddleboox.app.riddle.REPLY_POINTS_PER_TICK
import com.riddleboox.app.riddle.REPLY_REFRESH_INTERVAL_MS
import com.riddleboox.app.riddle.REPLY_TOP_PX
import com.riddleboox.app.riddle.Ticker
import com.riddleboox.app.riddle.union
import com.riddleboox.app.riddle.writeBounds
import com.riddleboox.app.ui.RegionView

/**
 * Runs the first-run introduction: writes [segments] out one at a time, in
 * the diary's own hand, with no pen input and no network — see
 * `docs/superpowers/specs/2026-08-21-onboarding-design.md` §6.3.
 *
 * Deliberately separate from [com.riddleboox.app.riddle.RiddleStateMachine]:
 * that class is scoped to the real Listening/Drinking/Thinking/Replying
 * conversation loop, and this needs none of it — no conversation, no ink
 * capture, no network.
 */
class OnboardingController(
    private val segments: List<String>,
    private val regionView: RegionView,
    private val refresher: EinkRefresher,
    private val ticker: Ticker,
    private val handwritingPlanner: HandwritingPlanner,
    private val replyFontSizePx: Float,
    private val pageWidthPx: () -> Int,
    private val onDone: () -> Unit,
) {
    init {
        require(segments.isNotEmpty()) { "Onboarding cần ít nhất một đoạn." }
    }

    private var state: OnboardingState = OnboardingState.Writing(0)
    private var replyCursor: ReplyRevealCursor? = null
    private var lastRefreshAtMs: Long = 0L
    private var started = false

    /**
     * Bounds newly revealed since the last flush, not yet handed to the
     * refresher — mirrors [com.riddleboox.app.riddle.RiddleState.Replying.pendingDirtyRect].
     * A throttled (non-flushing) tick still reveals points; without carrying
     * their bounds forward here, the next flush would only cover that tick's
     * own newBounds and the earlier points would never get refreshed onto the
     * panel, making the ink lag/pop in late.
     */
    private var pendingDirtyRect: PageRect? = null

    fun start() {
        ticker.start(TICK_MS, ::tick)
    }

    fun stop() {
        ticker.stop()
    }

    private fun tick() {
        val now = ticker.nowMs()
        if (!started) {
            // Layout may not have measured yet right after onCreate/onResume —
            // same guard RiddleStateMachine.tickListening uses for greet().
            if (pageWidthPx() <= 0) return
            started = true
            beginSegment(0)
        }
        when (val s = state) {
            is OnboardingState.Writing -> tickWriting(s, now)
            is OnboardingState.Holding -> tickHolding(s, now)
            OnboardingState.Done -> Unit
        }
    }

    private fun beginSegment(index: Int) {
        val cursor: WriteCursor = handwritingPlanner.cursor(
            pageWidthPx = pageWidthPx(),
            fontSizePx = replyFontSizePx,
            lineHeightPx = replyFontSizePx * HandwritingPlanner.LINE_HEIGHT_RATIO,
            startYPx = REPLY_TOP_PX,
            bottomLimitPx = regionView.drawingRect().height - REPLY_BOTTOM_PX,
        )
        val reveal = ReplyRevealCursor()
        replyCursor = reveal
        regionView.beginReply()
        reveal.add(cursor.append(segments[index]))
        if (cursor.pageFull) {
            Log.w(TAG, "onboarding segment $index tràn quá một trang — rút ngắn nội dung")
        }
        lastRefreshAtMs = 0L
        pendingDirtyRect = null
        state = OnboardingState.Writing(index)
    }

    private fun tickWriting(s: OnboardingState.Writing, now: Long) {
        val cursor = replyCursor ?: return
        val newlyRevealed = cursor.revealMore(REPLY_POINTS_PER_TICK)
        val newBounds = writeBounds(newlyRevealed)
        if (newlyRevealed.isNotEmpty()) {
            regionView.appendReplyStrokes(newlyRevealed, newBounds ?: regionView.drawingRect())
        }
        // Only this tick's new ink is painted, same accumulate-then-flush
        // shape as RiddleStateMachine.tickReplying's `dirty`/`pendingDirtyRect`
        // — a throttled tick still has to carry its bounds to the flush that
        // actually happens next, or that ink never gets refreshed onto the
        // panel.
        pendingDirtyRect = union(pendingDirtyRect, newBounds)
        if (now - lastRefreshAtMs >= REPLY_REFRESH_INTERVAL_MS || (cursor.caughtUp && newlyRevealed.isNotEmpty())) {
            refresher.requestHandwritingRefresh(regionView, pendingDirtyRect ?: regionView.drawingRect())
            pendingDirtyRect = null
            lastRefreshAtMs = now
        }
        state = decideOnboarding(s, now, caughtUp = cursor.caughtUp, totalSegments = segments.size).state
    }

    private fun tickHolding(s: OnboardingState.Holding, now: Long) {
        val decision = decideOnboarding(s, now, caughtUp = true, totalSegments = segments.size)
        state = decision.state
        if (decision.finished) {
            finish()
            return
        }
        if (decision.advance) {
            regionView.clearReplyLayer()
            regionView.render(PageRenderState.EMPTY)
            // Not requestFullRefresh: this fires once per segment (5 times
            // across 6 segments), and the GC16 flash that clears ghosting is
            // jarring at that cadence. Quality mode redraws the whole region
            // clean, without the flash.
            refresher.requestQualityPartialRefresh(regionView, regionView.drawingRect())
            beginSegment((state as OnboardingState.Writing).segmentIndex)
        }
    }

    private fun finish() {
        ticker.stop()
        regionView.clearReplyLayer()
        regionView.render(PageRenderState.EMPTY)
        refresher.requestFullRefresh(regionView)
        onDone()
    }

    private companion object {
        const val TAG = "OnboardingController"

        /**
         * No [com.riddleboox.app.riddle.RiddleStateMachine.tickReplying]-style
         * `nextTickAtMs` pacing gate here: this ticker already runs at
         * [TICK_MS] = 16L, which is >= `REPLY_TICK_MS` (14L, from
         * `com.riddleboox.app.riddle`) — that gate would almost never actually
         * block a tick in practice, so leaving it out doesn't change the
         * observed reveal speed. It just drops a field this controller has no
         * use for over static, non-streamed content.
         */
        const val TICK_MS = 16L
    }
}
