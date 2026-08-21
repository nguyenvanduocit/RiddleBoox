package com.riddleboox.app.onboarding

/**
 * Where the onboarding sequence is: writing one segment out, holding on a
 * finished one so it can be read, or done. See
 * `docs/superpowers/specs/2026-08-21-onboarding-design.md` §6.1.
 */
sealed class OnboardingState {

    /** Đang viết đoạn [segmentIndex] (0-based); reveal cursor còn strokes chưa lộ. */
    data class Writing(val segmentIndex: Int) : OnboardingState()

    /** Đoạn [segmentIndex] đã viết xong, đứng yên tới [holdUntilMs] rồi mới sang đoạn kế. */
    data class Holding(val segmentIndex: Int, val holdUntilMs: Long) : OnboardingState()

    /** Đã chạy hết toàn bộ segments. */
    object Done : OnboardingState()
}
