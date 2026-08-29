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
