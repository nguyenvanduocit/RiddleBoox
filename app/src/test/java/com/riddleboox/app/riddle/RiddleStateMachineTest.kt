package com.riddleboox.app.riddle

import com.riddleboox.app.agent.AgentDefinition
import com.riddleboox.app.agent.AgentManifest
import com.riddleboox.app.handwriting.FakeRaster
import com.riddleboox.app.handwriting.HandwritingPlanner
import com.riddleboox.app.handwriting.WriteStroke
import com.riddleboox.app.history.ConversationStore
import com.riddleboox.app.ink.StrokeStore
import com.riddleboox.app.reply.FakeChatServer
import com.riddleboox.app.settings.ReplySettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * End-to-end coverage for the class whose own doc on [RiddleStateMachine.carryOut]
 * says the effect layer "cannot" be read in a test — true only while [PagePanel]
 * and [PenGate] are concrete Android/Onyx classes. With those behind fakes, the
 * whole tick loop (ink -> dissolve -> request -> streamed reply -> disk -> back
 * to a standing page) runs on the JVM; Robolectric is only here for
 * [com.riddleboox.app.ink.PageRasterizer]'s `Bitmap`/`Canvas` calls inside
 * [RiddleStateMachine.requestReply], the one thing a fake [PagePanel] can't
 * reach around. See [DecideThinkingTest] for the retry/backoff arithmetic this
 * exercises end-to-end rather than re-proving.
 */
@RunWith(RobolectricTestRunner::class)
class RiddleStateMachineTest {

    private val harnesses = mutableListOf<Harness>()

    private fun harness(replySettings: ReplySettings? = null): Harness =
        Harness(replySettings).also { harnesses.add(it) }

    @After
    fun cleanup() {
        harnesses.forEach { it.root.deleteRecursively() }
    }

    private class Harness(replySettings: ReplySettings? = null, pageWidthPx: Int = 400) {
        val root: File = File.createTempFile("riddle-state-machine-test", "").apply { delete(); mkdirs() }
        val ticker = FakeTicker()
        val panel = FakePagePanel()
        val pen = FakePenGate()
        val strokeStore = StrokeStore()
        val busyEvents = mutableListOf<Boolean>()
        val statusEvents = mutableListOf<String>()
        val conversationStore = ConversationStore(File(root, "conversations-store"))
        val pendingTurnMarker = PendingTurnMarker(root)

        val machine = RiddleStateMachine(
            strokeStore = strokeStore,
            inkCapture = pen,
            panel = panel,
            ticker = ticker,
            agent = AgentDefinition(
                manifest = AgentManifest(id = "test-agent", name = "Test", greetings = listOf("Chào.")),
                systemPrompt = "You are a test diary.",
                workspace = File(root, "workspace").apply { mkdirs() },
            ),
            replySettings = replySettings,
            handwritingPlanner = HandwritingPlanner(FakeRaster()),
            conversationStore = conversationStore,
            pageArchive = null,
            pendingTurnMarker = pendingTurnMarker,
            pageWidthPx = { pageWidthPx },
            onStatusChanged = { statusEvents.add(it) },
            onBusyChanged = { busyEvents.add(it) },
        )

        /** Advances fake ticker time while yielding real wall-clock time to the real IO coroutine dispatcher. */
        fun driveUntilIdle(maxRealMs: Long = 10_000L, stepMs: Long = 16L) {
            val deadline = System.currentTimeMillis() + maxRealMs
            while (System.currentTimeMillis() < deadline) {
                ticker.advance(stepMs)
                if (busyEvents.isNotEmpty() && busyEvents.last() == false) return
                Thread.sleep(2)
            }
            error("did not settle back to Listening within ${maxRealMs}ms real time (last status: ${statusEvents.lastOrNull()})")
        }

        fun tick(stepMs: Long = 16L) = ticker.advance(stepMs)

        fun demoStrokes(text: String) {
            machine.commitDemoText(text)
        }

        /**
         * [RiddleStateMachine.persistTurn] dispatches to its own disk-writer
         * thread and returns immediately — busy going false only means the
         * page settled, not that the write landed. Poll rather than read
         * [conversationStore] once right after [driveUntilIdle].
         */
        fun waitForConversations(count: Int, maxRealMs: Long = 2_000L): List<com.riddleboox.app.history.StoredConversation> {
            val deadline = System.currentTimeMillis() + maxRealMs
            while (System.currentTimeMillis() < deadline) {
                val found = conversationStore.list()
                if (found.size >= count) return found
                Thread.sleep(5)
            }
            error("expected $count conversation(s) within ${maxRealMs}ms, found ${conversationStore.list().size}")
        }
    }

    @Test
    fun `the diary opens with a greeting and nothing is recorded for it`() {
        val h = harness()
        h.machine.start()
        h.tick(0)
        h.driveUntilIdle()

        assertEquals(listOf(true, false), h.busyEvents)
        assertTrue("greeting text reaches the page", h.panel.renders.any { it.replyStrokes.isNotEmpty() })
        assertTrue("a greeting is never a turn", h.conversationStore.list().isEmpty())
    }

    @Test
    fun `writing without a configured key answers with an excuse and records nothing`() {
        val h = harness(replySettings = null)
        h.machine.start()
        h.tick(0)
        h.driveUntilIdle() // greeting

        h.busyEvents.clear()
        h.demoStrokes("Hôm nay tôi buồn.")
        h.driveUntilIdle()

        assertEquals(listOf(true, false), h.busyEvents)
        assertTrue("no key configured is never a turn", h.conversationStore.list().isEmpty())
        assertTrue("the pen reopens once the excuse is written", h.pen.inputEnabledCalls.last())
    }

    @Test
    fun `stopping the pen mid-excuse leaves nothing recorded and reopens the page`() {
        val h = harness(replySettings = null)
        h.machine.start()
        h.tick(0)
        h.driveUntilIdle() // greeting

        h.busyEvents.clear()
        h.demoStrokes("Một câu trả lời đủ dài để chưa viết xong ngay khi bút vừa chạm mực trên trang giấy này.")
        // A couple of ticks reveal some of the excuse but not all of it —
        // long enough that stopping catches it mid-reveal rather than after
        // finishTurn already returned to Listening.
        repeat(2) { h.tick() }

        h.machine.stopNow()
        h.driveUntilIdle()

        assertTrue("an excuse cut short is still never a turn", h.conversationStore.list().isEmpty())
        assertEquals(listOf(true, false), h.busyEvents)
    }

    @Test
    fun `a real streamed reply is answered, drawn, and recorded`() {
        FakeChatServer(FakeChatServer.turn(transcript = "Hôm nay tôi buồn.", reply = "Ta hiểu.")).use { server ->
            val h = harness(replySettings = ReplySettings(server.baseUrl, "sk-test", "openai/gpt-5.6-luna"))
            h.machine.start()
            h.tick(0)
            h.driveUntilIdle() // greeting

            h.busyEvents.clear()
            h.demoStrokes("Hôm nay tôi buồn.")
            h.driveUntilIdle()

            // Drinking -> Thinking -> Replying are each their own busy=true
            // transition (RiddleStateMachine's state setter fires on every
            // class change, not just the Listening<->busy edge); only the
            // final return to Listening is ever false.
            assertTrue("busy the whole way through", h.busyEvents.dropLast(1).all { it })
            assertEquals("idle exactly once, at the end", false, h.busyEvents.last())
            val conversations = h.waitForConversations(1)
            val turn = conversations.single().turns.single()
            assertEquals("Hôm nay tôi buồn.", turn.transcript)
            assertEquals("Ta hiểu.", turn.reply)

            val request = server.takeRequest()
            assertEquals("Bearer sk-test", request.authorization)
            assertTrue("cleared once the turn is safely on disk", !h.pendingTurnMarker.consume())
        }
    }

    /**
     * The reply protocol lets the model put a figure into its answer as
     * inline `<svg>` markup. [FakeChatServer.turn] streams the reply in
     * 7-character deltas, so the block arrives in many pieces — exactly the
     * shape the hold-back in `writableCut` and the drain in `feedReply`
     * exist for: no angle bracket may ever reach the page as ink.
     */
    @Test
    fun `an svg block streamed inside the reply becomes a drawn figure, not ink`() {
        val reply = "Đây.\n<svg viewBox=\"0 0 16 8\"><line x1=\"0\" y1=\"0\" x2=\"16\" y2=\"8\"/></svg>\nXong."
        FakeChatServer(FakeChatServer.turn(transcript = "vẽ một đường chéo", reply = reply)).use { server ->
            val h = harness(replySettings = ReplySettings(server.baseUrl, "sk-test", "openai/gpt-5.6-luna"))
            h.machine.start()
            h.tick(0)
            h.driveUntilIdle() // greeting

            h.busyEvents.clear()
            h.demoStrokes("vẽ một đường chéo")
            h.driveUntilIdle()

            // Page 400 wide, margins 56: the figure fills the 288px content
            // width where no word from FakeRaster comes close.
            val standing = h.panel.renders.last { it.replyStrokes.isNotEmpty() }.replyStrokes
            val figureStrokes = standing.filter { s ->
                s.points.maxOf { it.x } - s.points.minOf { it.x } > 200f
            }
            assertEquals("the figure stands in the finished plan", 1, figureStrokes.size)
            val words = standing.filterNot { it in figureStrokes }
            assertTrue("the words around the figure are on the page too", words.isNotEmpty())

            // The record keeps the words alone; the markup became ink.
            assertEquals("Đây.\n\nXong.", h.waitForConversations(1).single().turns.single().reply)
        }
    }

    /**
     * The marker's whole job is surviving a process kill strictly between a
     * page being handed to the diary and that turn concluding — this is the
     * one thing a fresh [PendingTurnMarker] pointed at the same files can
     * observe about that window without actually killing the process.
     */
    @Test
    fun `handing a page to the diary marks it in flight before any tick runs`() {
        FakeChatServer(FakeChatServer.turn(transcript = "hi", reply = "hello")).use { server ->
            val h = harness(replySettings = ReplySettings(server.baseUrl, "sk-test", "openai/gpt-5.6-luna"))
            h.machine.start()
            h.tick(0)
            h.driveUntilIdle() // greeting
            assertTrue("nothing in flight while listening", !PendingTurnMarker(h.root).consume())

            h.demoStrokes("Xin chào")

            assertTrue("marked before the first tick even runs", PendingTurnMarker(h.root).consume())
        }
    }

    @Test
    fun `a 429 is retried and the diary answers once the throttle clears`() {
        FakeChatServer(
            FakeChatServer.error(429, "{\"error\":\"rate limited\"}"),
            FakeChatServer.turn(transcript = "Xin chào.", reply = "Ta nghe."),
        ).use { server ->
            val h = harness(replySettings = ReplySettings(server.baseUrl, "sk-test", "openai/gpt-5.6-luna"))
            h.machine.start()
            h.tick(0)
            h.driveUntilIdle() // greeting

            h.busyEvents.clear()
            h.demoStrokes("Xin chào.")
            h.driveUntilIdle(maxRealMs = 15_000L)

            assertTrue("busy the whole way through", h.busyEvents.dropLast(1).all { it })
            assertEquals("idle exactly once, at the end", false, h.busyEvents.last())
            val conversations = h.waitForConversations(1)
            assertEquals("Ta nghe.", conversations.single().turns.single().reply)
            assertTrue(
                "the throttled attempt says so on the status line",
                h.statusEvents.any { it.contains("Quá nhiều yêu cầu") },
            )
        }
    }
}

/** Drives [RiddleStateMachine] on demand rather than a real [android.os.Handler] loop. */
private class FakeTicker : Ticker {
    private var tickFn: (() -> Unit)? = null
    private var elapsedMs = 0L

    override fun nowMs(): Long = elapsedMs
    override fun start(everyMs: Long, tick: () -> Unit) { tickFn = tick }
    override fun stop() { tickFn = null }

    fun advance(byMs: Long) {
        elapsedMs += byMs
        tickFn?.invoke()
    }
}

private class FakePagePanel : PagePanel {
    val renders = mutableListOf<PageRenderState>()

    override fun render(state: PageRenderState) { renders.add(state) }
    override fun beginReply() = Unit
    override fun appendReplyStrokes(strokes: List<WriteStroke>, dirtyRect: PageRect) = Unit
    override fun clearReplyLayer() = Unit
    override fun drawingRect(): PageRect = PageRect(0, 0, 400, 600)
    override fun requestFastPartialRefresh(area: PageRect) = Unit
    override fun requestHandwritingRefresh(area: PageRect) = Unit
    override fun requestQualityPartialRefresh(area: PageRect) = Unit
    override fun requestFullRefresh() = Unit
}

private class FakePenGate : PenGate {
    val inputEnabledCalls = mutableListOf<Boolean>()

    override fun setInputEnabled(enabled: Boolean) { inputEnabledCalls.add(enabled) }
    override fun clearRawInkLayer() = Unit
}
