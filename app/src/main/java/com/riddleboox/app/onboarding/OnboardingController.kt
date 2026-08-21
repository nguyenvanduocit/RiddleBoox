package com.riddleboox.app.onboarding

import android.util.Log
import com.riddleboox.app.handwriting.HandwritingPlanner
import com.riddleboox.app.handwriting.ReplyRevealCursor
import com.riddleboox.app.handwriting.WriteCursor
import com.riddleboox.app.ink.EinkRefresher
import com.riddleboox.app.riddle.PageRenderState
import com.riddleboox.app.riddle.REPLY_BOTTOM_PX
import com.riddleboox.app.riddle.REPLY_POINTS_PER_TICK
import com.riddleboox.app.riddle.REPLY_REFRESH_INTERVAL_MS
import com.riddleboox.app.riddle.REPLY_TOP_PX
import com.riddleboox.app.riddle.Ticker
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
        state = OnboardingState.Writing(index)
    }

    private fun tickWriting(s: OnboardingState.Writing, now: Long) {
        val cursor = replyCursor ?: return
        val newlyRevealed = cursor.revealMore(REPLY_POINTS_PER_TICK)
        if (newlyRevealed.isNotEmpty()) {
            regionView.appendReplyStrokes(newlyRevealed, writeBounds(newlyRevealed) ?: regionView.drawingRect())
        }
        if (now - lastRefreshAtMs >= REPLY_REFRESH_INTERVAL_MS || (cursor.caughtUp && newlyRevealed.isNotEmpty())) {
            refresher.requestHandwritingRefresh(regionView, regionView.drawingRect())
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
            refresher.requestFullRefresh(regionView)
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
        const val TICK_MS = 16L
    }
}
