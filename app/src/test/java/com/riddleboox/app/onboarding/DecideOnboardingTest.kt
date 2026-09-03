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

    /** [decideOnboarding] never reads `caughtUp`/`now` once the sequence is [OnboardingState.Done]. */
    @Test
    fun `done ignores caughtUp and never advances`() {
        val decision = decideOnboarding(OnboardingState.Done, now = 0, caughtUp = false, totalSegments = totalSegments)

        assertEquals(OnboardingState.Done, decision.state)
        assertTrue(!decision.advance)
        assertTrue(decision.finished)
    }

    /** A single-segment sequence has no next segment to advance to — it must finish, not wrap to `Writing(1)`. */
    @Test
    fun `holding past its deadline with only one segment finishes rather than advancing`() {
        val state = OnboardingState.Holding(segmentIndex = 0, holdUntilMs = 5_000)
        val decision = decideOnboarding(state, now = 5_000, caughtUp = true, totalSegments = 1)

        assertEquals(OnboardingState.Done, decision.state)
        assertTrue(!decision.advance)
        assertTrue(decision.finished)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero segments is a programming error`() {
        decideOnboarding(OnboardingState.Writing(0), now = 0, caughtUp = true, totalSegments = 0)
    }

    // --- onboardingCaption: the progress line above the page ---

    @Test
    fun `caption while writing names the page and the total`() {
        assertEquals("page 1 of 3", onboardingCaption(OnboardingState.Writing(0), now = 0, totalSegments = totalSegments))
        assertEquals("page 3 of 3", onboardingCaption(OnboardingState.Writing(2), now = 0, totalSegments = totalSegments))
    }

    @Test
    fun `caption while holding counts whole seconds down, rounding up`() {
        val state = OnboardingState.Holding(segmentIndex = 0, holdUntilMs = 5_000)

        assertEquals("page 1 of 3 · next in 4", onboardingCaption(state, now = 1_000, totalSegments = totalSegments))
        assertEquals("page 1 of 3 · next in 4", onboardingCaption(state, now = 1_001, totalSegments = totalSegments))
        assertEquals("page 1 of 3 · next in 3", onboardingCaption(state, now = 2_000, totalSegments = totalSegments))
        assertEquals("page 1 of 3 · next in 1", onboardingCaption(state, now = 4_999, totalSegments = totalSegments))
    }

    /** The tick that reaches the deadline advances in the same breath; a "next in 0" would be a lie the eye can catch. */
    @Test
    fun `caption while holding never counts below one`() {
        val state = OnboardingState.Holding(segmentIndex = 0, holdUntilMs = 5_000)

        assertEquals("page 1 of 3 · next in 1", onboardingCaption(state, now = 5_000, totalSegments = totalSegments))
        assertEquals("page 1 of 3 · next in 1", onboardingCaption(state, now = 6_000, totalSegments = totalSegments))
    }

    @Test
    fun `caption on the last page hands the page over instead of promising a next one`() {
        val state = OnboardingState.Holding(segmentIndex = totalSegments - 1, holdUntilMs = 5_000)

        assertEquals("page 3 of 3 · your turn in 2", onboardingCaption(state, now = 3_000, totalSegments = totalSegments))
    }

    /** What follows the hold after the books page is the permission ask, not a page — the line must not promise "next". */
    @Test
    fun `caption while holding at the permission checkpoint announces a question`() {
        val state = OnboardingState.Holding(segmentIndex = 1, holdUntilMs = 5_000)

        assertEquals("page 2 of 3 · a question in 2", onboardingCaption(state, now = 3_000, totalSegments = totalSegments, checkpointAfter = 1))
        assertEquals("page 2 of 3 · next in 2", onboardingCaption(state, now = 3_000, totalSegments = totalSegments, checkpointAfter = null))
    }

    @Test
    fun `caption is empty once done`() {
        assertEquals("", onboardingCaption(OnboardingState.Done, now = 0, totalSegments = totalSegments))
    }
}
