package com.riddleboox.app.riddle

import com.riddleboox.app.ink.InkPoint
import com.riddleboox.app.ink.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Waiting on the diary — the retry budget, the blot.
 *
 * This is where five of the eight bugs found in the last review of this class
 * lived, and it had no test of any kind: the logic was reachable only through a
 * `Handler` tick inside an object that cannot be built off a tablet. What is
 * checked here is what a writer actually experiences when the Wi-Fi drops
 * mid-sentence, and it now costs a millisecond to check.
 */
class DecideThinkingTest {

    private val page = PageRect(0, 0, 1404, 1872)
    private val inFlight = listOf(InkStroke(mutableListOf(InkPoint(10f, 20f, 0.5f))))

    private fun thinking(
        startedAtMs: Long = 0,
        retryCount: Int = 0,
        retryAtMs: Long? = null,
    ) = RiddleState.Thinking(startedAtMs, retryCount, retryAtMs)

    private fun decide(
        s: RiddleState.Thinking = thinking(),
        now: Long,
        event: ReplyEvent? = null,
    ) = decideThinking(s, now, event, page, inFlight)

    // ---- the answer arriving ----

    @Test
    fun `the first words in start the pen and stream the rest`() {
        val decision = decide(now = 400, event = ReplyEvent.Delta("Ta hiểu"))!!
        val replying = decision.state as RiddleState.Replying

        assertTrue("the stream is still open", !replying.streamEnded)
        assertEquals(
            listOf(Effect.BeginReply(REPLY_TOP_PX), Effect.FeedReply("Ta hiểu")),
            decision.effects.filter { it is Effect.BeginReply || it is Effect.FeedReply },
        )
    }

    @Test
    fun `a reply whole before the first tick is recorded and then written`() {
        val decision = decide(
            now = 400,
            event = ReplyEvent.Complete(text = "**Ta** hiểu", transcript = "trời hôm nay"),
        )!!

        assertTrue((decision.state as RiddleState.Replying).streamEnded)
        // Recorded before it is written, and recorded as plain words: the
        // emphasis is a mark on a screen, not something a pen can make. The
        // ink itself goes through feed-and-flush, raw — the feed path is the
        // one that knows a reply may carry a figure's markup.
        val recorded = decision.effects.filterIsInstance<Effect.RecordTurn>().single()
        assertEquals("trời hôm nay", recorded.transcript)
        assertEquals("Ta hiểu", recorded.reply)
        assertEquals("**Ta** hiểu", decision.effects.filterIsInstance<Effect.FeedReply>().single().delta)
        assertEquals(1, decision.effects.filterIsInstance<Effect.FlushReply>().size)
    }

    /**
     * The page was left blank by the last stage of the drinking and nothing
     * drew on it while the diary thought, so there is nothing to clear when the
     * pen starts. Clearing it anyway is a wasted panel refresh at the one
     * moment the writer is watching for the first word.
     */
    @Test
    fun `nothing is repainted for a blot that is not there`() {
        val decision = decide(now = 400, event = ReplyEvent.Delta("x"))!!

        assertTrue(decision.effects.filterIsInstance<Effect.Render>().isEmpty())
        assertTrue(decision.effects.filterIsInstance<Effect.Refresh>().isEmpty())
        assertEquals("x", decision.effects.filterIsInstance<Effect.FeedReply>().single().delta)
    }

    // ---- looking something up ----

    @Test
    fun `a lookup changes the caption and nothing else`() {
        val before = thinking(startedAtMs = 0, retryCount = 2)
        val decision = decide(before, now = 5_000, event = ReplyEvent.Lookup("search_library"))!!

        assertSame("the state is handed back untouched", before, decision.state)
        assertEquals("Leafing through…", decision.effects.filterIsInstance<Effect.Status>().single().text)
    }

    /**
     * Deliberate: a search through a large book is progress, not a stall, and
     * Thinking has no clock of its own — a turn runs until an answer, a
     * network-level timeout, or the retry budget below is spent. Whatever a
     * lookup takes, it takes.
     */
    @Test
    fun `a lookup never times out on its own, however long it takes`() {
        val s = thinking(startedAtMs = 0)
        val looked = decide(s, now = 1_000, event = ReplyEvent.Lookup("read_book"))!!.state as RiddleState.Thinking

        assertNull(decideThinking(looked, now = 1_000L * 60 * 60 * 24, event = null, page = page, pageInFlight = inFlight))
    }

    // ---- failure ----

    @Test
    fun `a failure that is not the network gets three tries and then an excuse`() {
        var s = thinking()
        repeat(REPLY_RETRY_LIMIT) { attempt ->
            val decision = decideThinking(s, now = 1_000, ReplyEvent.Error("bad key"), page, inFlight)!!
            s = decision.state as RiddleState.Thinking
            assertEquals(attempt + 1, s.retryCount)
            assertEquals(1_000 + REPLY_RETRY_DELAY_MS, s.retryAtMs)
        }
        val giveUp = decideThinking(s, now = 2_000, ReplyEvent.Error("bad key"), page, inFlight)!!

        assertTrue("the fourth failure is written out", giveUp.state is RiddleState.Replying)
        assertEquals(excuseFor("bad key"), giveUp.effects.filterIsInstance<Effect.WriteText>().single().text)
    }

    /**
     * This panel's Wi-Fi drops DNS for tens of seconds whenever it changes
     * bands, which outlasts a three-attempt budget — so a request that never
     * left the device keeps trying rather than giving up on it.
     */
    @Test
    fun `a request that never left the device keeps trying past the budget`() {
        val exhausted = thinking(retryCount = REPLY_RETRY_LIMIT + 5)
        val decision = decideThinking(
            exhausted,
            now = 1_000,
            ReplyEvent.Error("Unable to resolve host \"openrouter.ai\""),
            page,
            inFlight,
        )!!

        val next = decision.state as RiddleState.Thinking
        assertEquals(REPLY_RETRY_LIMIT + 6, next.retryCount)
        assertEquals(1_000 + REPLY_RETRY_DELAY_MS, next.retryAtMs)
    }

    /**
     * A 429 gets the same unlimited-attempts treatment as a dead network — it
     * resolves on its own — but must not retry at the flat interval, or every
     * retry lands inside the same throttle window it is waiting out.
     */
    @Test
    fun `a provider throttle keeps trying past the three-attempt budget`() {
        val exhausted = thinking(retryCount = REPLY_RETRY_LIMIT + 5)
        val decision = decideThinking(
            exhausted,
            now = 1_000,
            ReplyEvent.Error("Client request(...) invalid: 429 Too Many Requests. Text: ..."),
            page,
            inFlight,
        )!!

        val next = decision.state as RiddleState.Thinking
        assertEquals(REPLY_RETRY_LIMIT + 6, next.retryCount)
        assertEquals(1_000 + rateLimitRetryDelayMs(REPLY_RETRY_LIMIT + 6), next.retryAtMs)
    }

    /** Each successive 429 waits longer, up to the cap. */
    @Test
    fun `a provider throttle backs off further on each attempt, up to the cap`() {
        var s = thinking()
        val delays = mutableListOf<Long>()
        repeat(6) {
            val decision = decideThinking(s, now = 0, ReplyEvent.Error("429"), page, inFlight)!!
            s = decision.state as RiddleState.Thinking
            delays.add(s.retryAtMs!!)
        }

        assertEquals(listOf(6_000L, 12_000L, 24_000L, 30_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun `a retry waits for the network to come back before it is spent`() {
        val armed = thinking(retryAtMs = 5_000)

        assertNull("not yet", decide(armed, now = 4_999))

        val decision = decide(armed, now = 5_000)!!
        assertNull((decision.state as RiddleState.Thinking).retryAtMs)
        assertEquals(
            "the same page goes back up the wire",
            inFlight,
            decision.effects.filterIsInstance<Effect.AskDiary>().single().page,
        )
    }

    // ---- the waiting itself ----

    /**
     * The panel repaints by flashing, so anything on a clock is paid for in
     * flashes. Ticking on an unchanged wait must produce nothing at all,
     * however long the wait runs — there is no patience budget to spend.
     */
    @Test
    fun `waiting with no news changes nothing, however long it waits`() {
        assertNull(decide(thinking(), now = 599))
        assertNull(decide(thinking(), now = 600))
        assertNull(decide(thinking(), now = 5_000))
        assertNull(decide(thinking(), now = 1_000L * 60 * 60 * 24))
    }

    /** What replaces the blot: the caption, worded by whoever did the looking. */
    @Test
    fun `a lookup says on the page which book is being opened`() {
        val decision = decide(thinking(), now = 900, event = ReplyEvent.Lookup("read_book", "Reading “Tiên Nghịch”…"))!!

        assertEquals("Reading “Tiên Nghịch”…", decision.effects.filterIsInstance<Effect.Status>().single().text)
        assertTrue("không được vẽ gì lên trang", decision.effects.filterIsInstance<Effect.Render>().isEmpty())
    }

    /** A lookup that says nothing about itself is still a lookup. */
    @Test
    fun `a lookup with nothing to say falls back to a general caption`() {
        val decision = decide(thinking(), now = 900, event = ReplyEvent.Lookup("read_book"))!!

        assertEquals("Leafing through…", decision.effects.filterIsInstance<Effect.Status>().single().text)
    }
}
