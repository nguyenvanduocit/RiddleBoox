package com.riddleboox.app.riddle

import org.junit.Assert.assertEquals
import org.junit.Test

class DecideStopTest {

    @Test
    fun `a page waiting for the writer has nothing to stop`() {
        assertEquals(StopAction.None, stopActionFor(RiddleState.Listening()))
    }

    /**
     * The request is already in flight while the ink dissolves
     * ([RiddleStateMachine.commitStrokes] asks before Drinking starts), so
     * stopping here has to drop it too.
     */
    @Test
    fun `stopping while the ink is being drunk drops the request`() {
        val drinking = RiddleState.Drinking(
            committedStrokes = emptyList(),
            standingReply = null,
            dirtyRect = PageRect(0, 0, 10, 10),
            stage = 2,
            nextStageAtMs = 0,
        )
        assertEquals(StopAction.Discard, stopActionFor(drinking))
    }

    @Test
    fun `stopping while waiting on the diary leaves a blank page`() {
        assertEquals(StopAction.Discard, stopActionFor(RiddleState.Thinking(startedAtMs = 0)))
    }

    @Test
    fun `stopping while the pen writes keeps what it wrote`() {
        assertEquals(StopAction.CutReply, stopActionFor(RiddleState.Replying(nextTickAtMs = 0)))
    }
}
