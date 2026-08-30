package com.riddleboox.app.riddle

import com.riddleboox.app.agent.AgentDefinition
import com.riddleboox.app.agent.AgentManifest
import com.riddleboox.app.handwriting.FakeRaster
import com.riddleboox.app.handwriting.GlyphMask
import com.riddleboox.app.handwriting.HandwritingPlanner
import com.riddleboox.app.handwriting.TextRaster
import com.riddleboox.app.handwriting.WriteStroke
import com.riddleboox.app.history.ConversationStore
import com.riddleboox.app.ink.StrokeStore
import com.riddleboox.app.reply.FakeChatServer
import com.riddleboox.app.settings.ReplySettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private fun harness(
        replySettings: ReplySettings? = null,
        raster: TextRaster = FakeRaster(),
    ): Harness = Harness(replySettings, raster = raster).also { harnesses.add(it) }

    @After
    fun cleanup() {
        harnesses.forEach { it.root.deleteRecursively() }
    }

    private class Harness(
        replySettings: ReplySettings? = null,
        pageWidthPx: Int = 400,
        raster: TextRaster = FakeRaster(),
    ) {
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
            handwritingPlanner = HandwritingPlanner(raster),
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
    fun `a demo write lands below the standing reply instead of overlapping it`() {
        FakeChatServer(
            FakeChatServer.turn(
                transcript = "kể chuyện dài",
                reply = "aaaaaaaaaa bbbbbbbbbb cccccccccc dddddddddd eeeeeeeeee ffffffffff",
            ),
        ).use { server ->
            val h = harness(replySettings = ReplySettings(server.baseUrl, "sk-test", "openai/gpt-5.6-luna"))
            h.machine.start()
            h.tick(0)
            h.driveUntilIdle() // greeting

            h.demoStrokes("kể chuyện dài")
            h.driveUntilIdle()

            val standing = h.panel.renders.last().replyStrokes
            val standingBottom = standing.flatMap { it.points }.maxOf { it.y }
            assertTrue(
                "the reply must span past the demo tool's old fixed top position for this test to mean anything",
                standingBottom > 120f,
            )

            h.demoStrokes("thêm một đoạn nữa")
            val afterSecondDemo = h.panel.renders.last { it.userStrokes.isNotEmpty() }
            val demoTop = afterSecondDemo.userStrokes.flatMap { it.points }.minOf { it.y }

            assertTrue(
                "the second demo write starts below the standing reply rather than overlapping it",
                demoTop > standingBottom,
            )
        }
    }

    @Test
    fun `writing without a configured key answers with a missing-key line and records nothing`() {
        val h = harness(replySettings = null)
        h.machine.start()
        h.tick(0)
        h.driveUntilIdle() // greeting

        h.busyEvents.clear()
        h.demoStrokes("Hôm nay tôi buồn.")
        h.driveUntilIdle()

        assertEquals(listOf(true, false), h.busyEvents)
        assertTrue("no key configured is never a turn", h.conversationStore.list().isEmpty())
        assertTrue("the pen reopens once the line is written", h.pen.inputEnabledCalls.last())
    }

    @Test
    fun `an unanswerable page says where the key goes, and says it differently next time`() {
        val raster = RecordingRaster()
        val h = harness(replySettings = null, raster = raster)
        h.machine.start()
        h.tick(0)
        h.driveUntilIdle() // greeting

        raster.written.clear()
        h.demoStrokes("Hôm nay tôi buồn.")
        h.driveUntilIdle()
        val first = raster.text()

        raster.written.clear()
        h.demoStrokes("Và hôm qua nữa.")
        h.driveUntilIdle()
        val second = raster.text()

        assertTrue("the first page is sent to Settings: $first", first.contains("Settings"))
        assertTrue("the second page is sent to Settings: $second", second.contains("Settings"))
        assertNotEquals("the same line twice reads as a stuck screen", first, second)
    }

    @Test
    fun `stopping the pen mid-line leaves nothing recorded and reopens the page`() {
        val h = harness(replySettings = null)
        h.machine.start()
        h.tick(0)
        h.driveUntilIdle() // greeting

        h.busyEvents.clear()
        h.demoStrokes("Một câu trả lời đủ dài để chưa viết xong ngay khi bút vừa chạm mực trên trang giấy này.")
        // A couple of ticks reveal some of the line but not all of it —
        // long enough that stopping catches it mid-reveal rather than after
        // finishTurn already returned to Listening.
        repeat(2) { h.tick() }

        h.machine.stopNow()
        h.driveUntilIdle()

        assertTrue("a line cut short is still never a turn", h.conversationStore.list().isEmpty())
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
    fun `a debug write lands as reply ink underneath the standing reply, not user ink`() {
        val h = harness()
        h.machine.start()
        h.tick(0)
        h.driveUntilIdle() // greeting

        val beforeCount = h.panel.renders.last().replyStrokes.size
        val outcome = h.machine.debugWriteReplyInk("more")
        assertTrue("write is accepted while listening", outcome.startsWith("wrote at y="))
        val after = h.panel.renders.last()
        assertTrue("no user ink is drawn by a debug write", after.userStrokes.isEmpty())
        assertTrue(
            "the greeting's strokes are still standing alongside the new ones",
            after.replyStrokes.size > beforeCount,
        )
    }

    @Test
    fun `a debug write is rejected while a turn is in flight`() {
        FakeChatServer(FakeChatServer.stream("Ta đã ghi nhớ.")).use { server ->
            val h = harness(replySettings = ReplySettings(server.baseUrl, "sk-test", "openai/gpt-5.6-luna"))
            h.machine.start()
            h.tick(0)
            h.driveUntilIdle() // greeting

            h.machine.memorize()
            val outcome = h.machine.debugWriteReplyInk("nope")
            assertTrue("rejected, not silently dropped", outcome.startsWith("rejected:"))
        }
    }

    @Test
    fun `the memorize label runs a housekeeping pass and records nothing`() {
        FakeChatServer(FakeChatServer.stream("Ta đã ghi nhớ chuyện này.")).use { server ->
            val h = harness(replySettings = ReplySettings(server.baseUrl, "sk-test", "openai/gpt-5.6-luna"))
            h.machine.start()
            h.tick(0)
            h.driveUntilIdle() // greeting

            h.busyEvents.clear()
            h.panel.renders.clear()
            h.machine.memorize()
            h.driveUntilIdle()

            assertTrue("busy the whole way through", h.busyEvents.dropLast(1).all { it })
            assertEquals("idle exactly once, at the end", false, h.busyEvents.last())
            assertTrue("the closing line reaches the page", h.panel.renders.any { it.replyStrokes.isNotEmpty() })
            assertTrue("the pen reopens", h.pen.inputEnabledCalls.last())
            assertTrue("a memory pass is never a turn", h.conversationStore.list().isEmpty())
            assertTrue(h.statusEvents.any { it == "Committing to memory…" })
            assertTrue(
                "the note goes up in place of a page",
                server.takeRequest().body.contains("put your kept memories in order"),
            )
            assertTrue("nothing was ever marked in flight", !PendingTurnMarker(h.root).consume())
        }
    }

    /**
     * The pass ends by blanking the page for its closing line, and ink not
     * yet handed over must never be blanked out from under the writer.
     */
    @Test
    fun `memorize is refused while unsent ink is on the page`() {
        val h = harness()
        h.machine.start()
        h.tick(0)
        h.driveUntilIdle() // greeting
        h.strokeStore.beginStroke(com.riddleboox.app.ink.InkPoint(10f, 20f, 0.5f))
        h.strokeStore.finishCurrent()

        h.busyEvents.clear()
        h.machine.memorize()

        assertTrue("nothing starts; the ink stays the writer's", h.busyEvents.isEmpty())
    }

    @Test
    fun `memorize without a configured key answers with a missing-key line and records nothing`() {
        val h = harness(replySettings = null)
        h.machine.start()
        h.tick(0)
        h.driveUntilIdle() // greeting

        h.busyEvents.clear()
        h.machine.memorize()
        h.driveUntilIdle()

        assertEquals(listOf(true, false), h.busyEvents)
        assertTrue("a missing-key line is never a turn", h.conversationStore.list().isEmpty())
        assertTrue("the pen reopens once the line is written", h.pen.inputEnabledCalls.last())
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
                h.statusEvents.any { it.contains("Too many requests") },
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

/**
 * A [TextRaster] that keeps what it was asked to draw, so a test can read the
 * words a reply was written in — a [com.riddleboox.app.handwriting.WritePlan]
 * is strokes by then, and strokes cannot be read back.
 */
private class RecordingRaster : TextRaster {
    val written = mutableListOf<String>()

    fun text(): String = written.joinToString("")

    override fun measure(text: String, fontSizePx: Float): Float = text.length * 10f

    override fun rasterize(text: String, fontSizePx: Float): GlyphMask {
        written.add(text)
        val mask = GlyphMask((text.length * 10).coerceAtLeast(1), 20)
        for (y in 2..8) mask[5, y] = true
        return mask
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
