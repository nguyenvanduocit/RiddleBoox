package com.riddleboox.app.onboarding

/** How long a finished segment stands before the next one begins. */
internal const val ONBOARDING_HOLD_MS = 4_000L

/**
 * What one tick of onboarding came to: where it is now ([state]), whether
 * the shell should clear the page and start the next segment ([advance]),
 * and whether the whole sequence is over ([finished]).
 */
data class OnboardingDecision(val state: OnboardingState, val advance: Boolean, val finished: Boolean)

/**
 * @param caughtUp whether the current segment's [com.riddleboox.app.handwriting.ReplyRevealCursor]
 * has revealed every stroke.
 */
fun decideOnboarding(
    state: OnboardingState,
    now: Long,
    caughtUp: Boolean,
    totalSegments: Int,
): OnboardingDecision {
    require(totalSegments > 0) { "Onboarding needs at least one segment." }
    return when (state) {
        is OnboardingState.Writing -> {
            if (!caughtUp) return OnboardingDecision(state, advance = false, finished = false)
            val holding = OnboardingState.Holding(state.segmentIndex, holdUntilMs = now + ONBOARDING_HOLD_MS)
            OnboardingDecision(holding, advance = false, finished = false)
        }
        is OnboardingState.Holding -> {
            if (now < state.holdUntilMs) return OnboardingDecision(state, advance = false, finished = false)
            val next = state.segmentIndex + 1
            if (next >= totalSegments) {
                OnboardingDecision(OnboardingState.Done, advance = false, finished = true)
            } else {
                OnboardingDecision(OnboardingState.Writing(next), advance = true, finished = false)
            }
        }
        OnboardingState.Done -> OnboardingDecision(state, advance = false, finished = true)
    }
}

/**
 * The one line of chrome the intro has: which page this is, and — while a
 * finished page stands — how long until what comes next. It exists because a
 * page that stands still for [ONBOARDING_HOLD_MS] with nothing else on screen
 * reads as "is it over? do I do something?"; the count says another is coming
 * and the seconds say when. What is promised is what actually arrives: the
 * next page, a question when the hold ends at the permission ask
 * ([checkpointAfter], the segment index it follows), or the writer's own turn
 * after the last page — so the end of the intro is announced and not merely
 * happens.
 *
 * Seconds round up and never reach zero: the tick that reaches the deadline is
 * the tick that turns the page, so a "next in 0" would stand for a frame with
 * nothing following it.
 */
fun onboardingCaption(state: OnboardingState, now: Long, totalSegments: Int, checkpointAfter: Int? = null): String =
    when (state) {
        is OnboardingState.Writing -> "page ${state.segmentIndex + 1} of $totalSegments"
        is OnboardingState.Holding -> {
            val secondsLeft = ((state.holdUntilMs - now + 999) / 1_000).coerceAtLeast(1)
            val page = "page ${state.segmentIndex + 1} of $totalSegments"
            val coming = when {
                state.segmentIndex == checkpointAfter -> "a question in"
                state.segmentIndex + 1 >= totalSegments -> "your turn in"
                else -> "next in"
            }
            "$page · $coming $secondsLeft"
        }
        OnboardingState.Done -> ""
    }
