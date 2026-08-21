package com.riddleboox.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DecideOnboardingTest {

    private val totalSegments = 3

    @Test
    fun `writing not caught up leaves state unchanged`() {
        val state = OnboardingState.Writing(0)
        val decision = decideOnboarding(state, now = 100, caughtUp = false, totalSegments = totalSegments)

        assertSame(state, decision.state)
        assertTrue(!decision.advance)
        assertTrue(!decision.finished)
    }

    @Test
    fun `writing caught up starts a hold`() {
        val decision = decideOnboarding(OnboardingState.Writing(0), now = 1_000, caughtUp = true, totalSegments = totalSegments)

        val holding = decision.state as OnboardingState.Holding
        assertEquals(0, holding.segmentIndex)
        assertEquals(1_000 + ONBOARDING_HOLD_MS, holding.holdUntilMs)
        assertTrue(!decision.advance)
        assertTrue(!decision.finished)
    }

    @Test
    fun `holding before its deadline leaves state unchanged`() {
        val state = OnboardingState.Holding(segmentIndex = 0, holdUntilMs = 5_000)
        val decision = decideOnboarding(state, now = 4_999, caughtUp = true, totalSegments = totalSegments)

        assertSame(state, decision.state)
        assertTrue(!decision.advance)
        assertTrue(!decision.finished)
    }

    @Test
    fun `holding past its deadline advances to the next segment`() {
        val state = OnboardingState.Holding(segmentIndex = 0, holdUntilMs = 5_000)
        val decision = decideOnboarding(state, now = 5_000, caughtUp = true, totalSegments = totalSegments)

        assertEquals(OnboardingState.Writing(1), decision.state)
        assertTrue(decision.advance)
        assertTrue(!decision.finished)
    }

    @Test
    fun `holding past its deadline on the last segment finishes instead of advancing`() {
        val state = OnboardingState.Holding(segmentIndex = totalSegments - 1, holdUntilMs = 5_000)
        val decision = decideOnboarding(state, now = 5_000, caughtUp = true, totalSegments = totalSegments)

        assertEquals(OnboardingState.Done, decision.state)
        assertTrue(!decision.advance)
        assertTrue(decision.finished)
    }

    @Test
    fun `done always reports finished`() {
        val decision = decideOnboarding(OnboardingState.Done, now = 9_999, caughtUp = true, totalSegments = totalSegments)

        assertEquals(OnboardingState.Done, decision.state)
        assertTrue(decision.finished)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero segments is a programming error`() {
        decideOnboarding(OnboardingState.Writing(0), now = 0, caughtUp = true, totalSegments = 0)
    }
}
