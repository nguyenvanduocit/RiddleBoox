package com.riddleboox.app.riddle

import com.riddleboox.app.settings.SendMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a pause in the writing is the writer finishing, and when it is only a
 * pause.
 *
 * The diary used to answer that question one way — long enough is finished —
 * which reads the page wrong for anyone writing more than a couple of
 * sentences: thinking mid-page took the page away mid-thought. In
 * [SendMode.Manual] no pause is ever long enough and the writer says when.
 */
class DecideListeningTest {

    private val penUp = 10_000L

    @Test
    fun `a pause long enough is the writer finishing`() {
        assertTrue(
            shouldCommitOnPause(SendMode.Auto, penUp, penUp + IDLE_COMMIT_MS, hasInk = true),
        )
    }

    @Test
    fun `a pause still within the settling time is only a pause`() {
        assertFalse(
            shouldCommitOnPause(SendMode.Auto, penUp, penUp + IDLE_COMMIT_MS - 1, hasInk = true),
        )
    }

    @Test
    fun `manual never commits, however long the pen has been down`() {
        assertFalse(
            shouldCommitOnPause(SendMode.Manual, penUp, penUp + 10 * IDLE_COMMIT_MS, hasInk = true),
        )
    }

    @Test
    fun `a pen still on the page has not paused at all`() {
        assertFalse(shouldCommitOnPause(SendMode.Auto, null, penUp + IDLE_COMMIT_MS, hasInk = true))
    }

    @Test
    fun `an empty page has nothing to hand over`() {
        assertFalse(
            shouldCommitOnPause(SendMode.Auto, penUp, penUp + IDLE_COMMIT_MS, hasInk = false),
        )
    }
}
