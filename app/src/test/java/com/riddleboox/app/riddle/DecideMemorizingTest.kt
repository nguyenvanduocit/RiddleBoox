package com.riddleboox.app.riddle

import com.riddleboox.app.handwriting.WritePlan
import com.riddleboox.app.handwriting.WritePoint
import com.riddleboox.app.handwriting.WriteStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Waiting on a memory pass — what makes it a sibling of [DecideThinkingTest]
 * and not a copy: no retry, no excuse, and above all no [Effect.RecordTurn]
 * anywhere, because a housekeeping exchange must never reach the conversation
 * on disk.
 */
class DecideMemorizingTest {

    private val standingReply = WritePlan(
        listOf(WriteStroke(listOf(WritePoint(30f, 40f), WritePoint(90f, 45f)))),
    )

    private fun memorizing(
        startedAtMs: Long = 0,
        standing: WritePlan? = standingReply,
    ) = RiddleState.Memorizing(startedAtMs = startedAtMs, standingReply = standing)

    // ---- the closing line arriving ----

    @Test
    fun `the closing line clears the standing reply and is written whole`() {
        val decision = decideMemorizing(memorizing(), now = 400, event = ReplyEvent.Complete("**Ta** đã ghi nhớ.", ""))!!

        val replying = decision.state as RiddleState.Replying
        assertTrue("the line arrives whole; there is no stream to wait on", replying.streamEnded)
        assertEquals(PageRenderState.EMPTY, decision.effects.filterIsInstance<Effect.Render>().single().page)
        assertEquals(RefreshMode.Quality, decision.effects.filterIsInstance<Effect.Refresh>().single().mode)
        assertEquals(REPLY_TOP_PX, decision.effects.filterIsInstance<Effect.BeginReply>().single().startYPx)
        // Through WriteText, the greeting-and-excuse path, as plain words —
        // not feed-and-flush, whose ink is what a stopped pen records.
        assertEquals("Ta đã ghi nhớ.", decision.effects.filterIsInstance<Effect.WriteText>().single().text)
        assertTrue(decision.effects.none { it is Effect.FeedReply })
    }

    /** The page was already bare, so clearing it would only spend a refresh. */
    @Test
    fun `a bare page is not repainted before the line`() {
        val decision = decideMemorizing(memorizing(standing = null), now = 400, event = ReplyEvent.Complete("Đã xong.", ""))!!

        assertTrue(decision.effects.filterIsInstance<Effect.Render>().isEmpty())
        assertTrue(decision.effects.filterIsInstance<Effect.Refresh>().isEmpty())
        assertEquals("Đã xong.", decision.effects.filterIsInstance<Effect.WriteText>().single().text)
    }

    @Test
    fun `markup in the closing line never reaches the page as ink`() {
        val markedUp = "Đã ghi. <svg viewBox=\"0 0 4 4\"><line x1=\"0\" y1=\"0\" x2=\"4\" y2=\"4\"/></svg>"
        val decision = decideMemorizing(memorizing(), now = 400, event = ReplyEvent.Complete(markedUp, ""))!!

        assertEquals("Đã ghi.", decision.effects.filterIsInstance<Effect.WriteText>().single().text)
    }

    // ---- looking something up ----

    @Test
    fun `a lookup changes the caption and nothing else`() {
        val before = memorizing()
        val decision = decideMemorizing(before, now = 5_000, event = ReplyEvent.Lookup("remember", "committing this to memory…"))!!

        assertSame("the state is handed back untouched", before, decision.state)
        assertEquals("committing this to memory…", decision.effects.filterIsInstance<Effect.Status>().single().text)
    }

    @Test
    fun `a lookup with nothing to say falls back to the pass's own caption`() {
        val decision = decideMemorizing(memorizing(), now = 5_000, event = ReplyEvent.Lookup("remember"))!!

        assertEquals("Committing to memory…", decision.effects.filterIsInstance<Effect.Status>().single().text)
    }

    // ---- failure ----

    /**
     * Deliberately no retry, unlike [decideThinking]: `remember` may already
     * have run by the time a failure comes back, and running the pass again
     * on a guess would keep the same facts twice. The label is the retry.
     */
    @Test
    fun `failure hands the page back untouched with no retry`() {
        val before = memorizing()
        val decision = decideMemorizing(before, now = 5_000, event = ReplyEvent.Error("boom"))!!

        val listening = decision.state as RiddleState.Listening
        assertSame("the standing reply never left the page", before.standingReply, listening.standingReply)
        assertTrue("the pen reopens", decision.effects.filterIsInstance<Effect.PenInput>().single().enabled)
        assertTrue("no second attempt goes up on its own", decision.effects.none { it is Effect.AskDiary })
        assertTrue(decision.effects.filterIsInstance<Effect.Render>().isEmpty())
    }

    // ---- the waiting itself ----

    /** A memory pass has no clock of its own — see [decideThinking]'s doc for why. */
    @Test
    fun `waiting with no news changes nothing, however long it waits`() {
        assertNull(decideMemorizing(memorizing(), now = 600, event = null))
        assertNull(decideMemorizing(memorizing(), now = 1_000L * 60 * 60 * 24, event = null))
    }

    /** A memory pass streams no deltas; one here is stale news from an abandoned turn. */
    @Test
    fun `a stray delta is stale news and changes nothing`() {
        assertNull(decideMemorizing(memorizing(), now = 600, event = ReplyEvent.Delta("Ta")))
    }

    /** The rule the whole state exists for. */
    @Test
    fun `a memory pass is never recorded as a turn`() {
        val decisions = listOfNotNull(
            decideMemorizing(memorizing(), now = 400, event = ReplyEvent.Complete("Đã ghi nhớ.", "")),
            decideMemorizing(memorizing(), now = 400, event = ReplyEvent.Error("boom")),
        )

        assertEquals(2, decisions.size)
        decisions.forEach { decision ->
            assertTrue(decision.effects.none { it is Effect.RecordTurn })
        }
    }
}
