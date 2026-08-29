package com.riddleboox.app.riddle

import android.util.Log
import com.riddleboox.app.agent.AgentDefinition
import com.riddleboox.app.handwriting.HandwritingPlanner
import com.riddleboox.app.handwriting.ReplyRevealCursor
import com.riddleboox.app.handwriting.WriteCursor
import com.riddleboox.app.handwriting.WritePlan
import com.riddleboox.app.handwriting.WriteStroke
import com.riddleboox.app.history.ConversationStore
import com.riddleboox.app.history.StoredConversation
import com.riddleboox.app.history.StoredTurn
import com.riddleboox.app.ink.InkPoint
import com.riddleboox.app.ink.InkStroke
import com.riddleboox.app.ink.PageArchive
import com.riddleboox.app.ink.PageRasterizer
import com.riddleboox.app.ink.StrokeStore
import com.riddleboox.app.handwriting.svgFigure
import com.riddleboox.app.reply.Conversation
import com.riddleboox.app.reply.Toolbox
import com.riddleboox.app.reply.completedSvgBlock
import com.riddleboox.app.reply.plainText
import com.riddleboox.app.reply.reasoningFor
import com.riddleboox.app.reply.replyClient
import com.riddleboox.app.reply.replyModel
import com.riddleboox.app.reply.svgOpenAt
import com.riddleboox.app.reply.writableCut
import com.riddleboox.app.settings.ReplySettings
import com.riddleboox.app.settings.SendMode
import com.riddleboox.app.tools.memorizeInstruction
import com.riddleboox.app.tools.readMemories
import com.riddleboox.app.tools.recentMemoriesText
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Drives the diary's whole life cycle: Listening -> Drinking -> Thinking ->
 * Replying -> Listening. Adapted from the state-machine loop in
 * references/Riddle/src/main.rs:412-745, from a 2ms busy loop over a raw
 * framebuffer to an Android [Handler] tick over vector strokes.
 *
 * Where main.rs lingers on the reply and then fades it on a timer, this diary
 * leaves the reply standing and reopens the pen at once — see
 * [RiddleState.Listening.standingReply].
 *
 * The reply reaches the page while the model is still writing it: settled words
 * are laid out and revealed as they arrive, and when the pen catches up it
 * waits with a blot at the nib rather than freezing mid-sentence.
 *
 * Every answered turn is written to [ConversationStore] as it happens, and
 * [resume] hands one of those conversations back to the diary as its memory —
 * so an evening can be picked up days later.
 *
 * Deliberate scope cuts from the Rust original (v1 — full loop working
 * end-to-end on real hardware first, everything below is a natural follow-up):
 *  - No memory protocol: the diary is never given `MEMORY_PROTOCOL`/`⟦show:N⟧`,
 *    the recall syntax by which main.rs lets a reply summon an old page back
 *    onto the screen.
 *  - No "?" guide panel, no eraser-driven "everything erased" detection.
 */
class RiddleStateMachine(
    private val strokeStore: StrokeStore,
    private val inkCapture: PenGate,
    private val panel: PagePanel,
    /** What the diary's whole sense of time comes from — see [Ticker]. */
    private val ticker: Ticker,
    /** The selected RiddleBoox agent, unrelated to Claude Code's agent model. */
    private val agent: AgentDefinition,
    replySettings: ReplySettings?,
    /**
     * What the diary may look up while it answers — the writer's own books and
     * the evenings already had. Null leaves it answering out of memory alone,
     * which is what it does on a device whose library cannot be reached.
     */
    toolbox: Toolbox? = null,
    private val handwritingPlanner: HandwritingPlanner,
    /** The writer's chosen glyph height for the diary's own hand — see [com.riddleboox.app.settings.ReplyFontSize]. */
    private val replyFontSizePx: Float = HandwritingPlanner.DEFAULT_FONT_SIZE_PX,
    private val conversationStore: ConversationStore,
    /** Where the pages actually sent are kept for the writer to look at. */
    private val pageArchive: PageArchive?,
    /**
     * Crash-safety breadcrumb for a page handed to the diary — see
     * [PendingTurnMarker]'s own doc for exactly when it is set and cleared.
     */
    private val pendingTurnMarker: PendingTurnMarker,
    initialSendMode: SendMode = SendMode.Auto,
    private val pageWidthPx: () -> Int,
    private val onStatusChanged: (String) -> Unit,
    /**
     * Whether a turn is in flight — the chrome shows the way to stop it only
     * while there is something to stop. Called on the tick's thread, which is
     * the main one, whenever the diary moves between waiting and working.
     */
    private val onBusyChanged: (Boolean) -> Unit = {},
) {

    /**
     * Baseline-to-baseline advance for [replyFontSizePx], kept at the same
     * ratio to it that [HandwritingPlanner.DEFAULT_LINE_HEIGHT_PX] holds to
     * [HandwritingPlanner.DEFAULT_FONT_SIZE_PX] — every call that lays out a
     * reply needs both together, so this is computed once rather than at
     * each call site.
     */
    private val replyLineHeightPx = replyFontSizePx * HandwritingPlanner.LINE_HEIGHT_RATIO

    /**
     * Who decides that a page is finished — see [shouldCommitOnPause].
     *
     * Changing it while the page is waiting says so on the status line
     * straight away: the writer has just tapped the chrome and the one thing
     * they need to know is whether the next pause will take the page.
     *
     * Read on the tick and written from the chrome, both on the main thread,
     * so unlike [lastPenUpAtMs] it needs nothing to make it safe.
     */
    var sendMode: SendMode = initialSendMode
        set(value) {
            field = value
            // Turning the automatic pause back on starts its clock here rather
            // than from whenever the pen was last lifted. A page written under
            // [SendMode.Manual] has usually been sitting for minutes, and
            // without this the very next tick reads that as a finished page and
            // takes it — the writer tapped the mode, not the send.
            if (value == SendMode.Auto && lastPenUpAtMs != null) lastPenUpAtMs = ticker.nowMs()
            if (state is RiddleState.Listening) onStatusChanged(idleStatus())
        }

    private fun idleStatus(): String = idleStatus(sendMode)

    /**
     * What the request in flight has produced, waiting for the next tick.
     *
     * Swapped for an empty one rather than emptied, because [Job.cancel] does
     * not wait: a delta already on its way in when the writer asks for a clean
     * page would survive a `clear()` and be written out later as the diary's
     * answer to a completely different page. The abandoned request keeps the
     * queue it was launched with, and nothing reads it again.
     */
    private var replyEvents = ConcurrentLinkedQueue<ReplyEvent>()

    /**
     * The diary's running conversation — null when no key is configured, which
     * is how [commitStrokes] knows to write its own excuse instead of asking.
     * One instance for the life of the machine, so it accumulates the evening's
     * exchanges rather than meeting the writer anew on every page.
     */
    private val conversation: Conversation? = replySettings?.let {
        Conversation(
            client = replyClient(it.baseUrl, it.apiKey),
            model = replyModel(it.model),
            reasoning = reasoningFor(it.model),
            toolbox = toolbox,
            systemPrompt = agent.systemPrompt,
            recentMemories = recentMemoriesText(agent.workspace),
        )
    }
    private val replyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var replyJob: Job? = null

    /**
     * Which conversation on disk the turns being answered belong to.
     *
     * Opening the app starts a new one rather than continuing last night's: a
     * diary is picked up at a blank page, and the evening before is something
     * the writer chooses to turn back to (see [resume]). It reaches
     * [ConversationStore] only when a turn is actually answered, so opening the
     * app and putting it down again leaves nothing behind.
     */
    private var conversationId: String = newConversationId()
    private var conversationStartedAtMs: Long = System.currentTimeMillis()

    /**
     * The page a reply is being asked for — set at [commitPage], and read back
     * by [tickThinking] when a failed request is retried, since the page has to
     * be rasterised and sent again. [RiddleState.Thinking] doesn't carry the
     * strokes itself; every turn only ever has one page in flight, so a single
     * field is enough (Drinking already discarded them from its own state).
     */
    private var lastCommittedStrokes: List<InkStroke> = emptyList()

    /**
     * The pen laying the reply out, and the pen revealing what it laid out —
     * the first turns text into strokes at the nib's position, the second
     * hands those strokes to the page a tick's worth at a time.
     *
     * They live here rather than in [RiddleState.Replying] because both are
     * mutable and both grow as the reply streams in; the state holds only the
     * timing. [startReplying] makes them, [finishTurn] and [clearPage] drop them.
     */
    private var writeCursor: WriteCursor? = null
    private var replyCursor: ReplyRevealCursor? = null

    /**
     * Streamed text not yet written, because it is not yet safe to write —
     * see [writableCut]. Emptied onto the page as each delta settles it.
     */
    private val pendingText = StringBuilder()

    /**
     * The streamed reply as far as it has actually reached the page.
     *
     * Kept because a turn cut short is still a turn: the words are in ink, the
     * writer has read them, and a diary that did not record them would deny
     * having said them the next time this conversation is taken up. Only the
     * streaming path fills it — a greeting or an excuse writes straight through
     * [writeText] and neither belongs in the history.
     *
     * It carries the stroke count each chunk was laid out at, so a turn stopped
     * by hand can be recorded as the words the writer actually watched appear
     * rather than everything the model had already sent — see [ReplySoFar].
     */
    private val written = ReplySoFar()

    /**
     * The one thread every write to the conversation goes down.
     *
     * Ordering is the whole point: a turn is appended when the stream closes
     * and may be rewritten when the writer stops the pen a moment later
     * ([rewriteRecordedTurn]), and those two racing would leave the reply the
     * writer never saw standing in the history. Daemon, so it never holds the
     * process open on its own.
     */
    private val diskWriter: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "riddle-diary-disk").apply { isDaemon = true }
        }

    /** Whether this turn has already reached the conversation on disk. */
    private var turnRecorded: Boolean = false

    /** Turns completed since the last full-screen refresh — see [settleFinishedTurn]. */
    private var turnsSinceFullRefresh: Int = 0

    /** Whether the diary has already opened with its greeting — see [greet]. */
    private var greeted: Boolean = false

    /** The greeting last written, so [greeting] can avoid repeating it. */
    private var lastGreeting: String? = null

    /**
     * Volatile because [onPenDown] and [onPenUp] read it from the Onyx SDK's
     * own reading thread (see
     * `references/boox-riddle-diary/.../DiaryController.kt:135`) while [tick]
     * writes it on the main one. Every write to it happens on the tick's
     * thread and every cross-thread touch is a plain read, so there is no
     * read-modify-write left here to lose.
     */
    @Volatile
    private var state: RiddleState = RiddleState.Listening()
        set(value) {
            val changed = value::class != field::class
            field = value
            if (changed) {
                Log.i(TAG, "state -> ${value::class.simpleName}")
                onBusyChanged(value !is RiddleState.Listening)
            }
        }

    /**
     * When the pen was last lifted, or null while it is down or while the page
     * has already been taken.
     *
     * Its own volatile field rather than part of [RiddleState.Listening]
     * because of who writes it: the Onyx SDK reports pen events on its own
     * reading thread, and `state = s.copy(lastPenUpAtMs = ...)` was a
     * read-modify-write of the whole state from there, racing the tick doing
     * the same on the main thread — either could overwrite the other's page.
     * A single volatile write of one Long cannot interleave with anything.
     */
    @Volatile
    private var lastPenUpAtMs: Long? = null

    fun start() {
        ticker.start(TICK_MS, ::tick)
    }

    fun stop() {
        ticker.stop()
        // The tick is what turns a request into ink, so a request outliving it
        // has nowhere to land. Abandoning it silently would leave the page
        // pulsing its blot on the way back until REPLY_PATIENCE_MS ran out;
        // the failure is left in the queue instead, so the first tick after
        // [start] retries the turn or says so.
        val wasInFlight = replyJob?.isActive == true
        abandonReply()
        if (wasInFlight) replyEvents.add(ReplyEvent.Error("interrupted"))
    }

    /**
     * Drop the request in flight and everything it has produced.
     *
     * The queue is swapped rather than emptied because [Job.cancel] does not
     * wait — see [replyEvents].
     */
    private fun abandonReply() {
        replyJob?.cancel()
        replyEvents = ConcurrentLinkedQueue()
    }

    /**
     * Forward from [InkCaptureController.Callbacks.onPenDown], on the SDK's own
     * reading thread. The answer has to be synchronous — the SDK is asking
     * whether to let this stroke through — so it is a plain read of [state],
     * and the only thing written is [lastPenUpAtMs]: the writer is writing
     * again, so the pause that would have committed the page is over.
     */
    fun onPenDown(): Boolean {
        if (state !is RiddleState.Listening) return false
        lastPenUpAtMs = null
        return true
    }

    /**
     * Forward from [InkCaptureController.Callbacks.onPenUp], on the SDK's own
     * reading thread. The clock is read here rather than on the tick that picks
     * it up, so the pause is measured from when the pen actually left the page.
     */
    fun onPenUp() {
        if (state !is RiddleState.Listening) return
        lastPenUpAtMs = ticker.nowMs()
    }

    private fun tick() {
        val now = ticker.nowMs()
        when (val s = state) {
            is RiddleState.Listening -> tickListening(s, now)
            is RiddleState.Drinking -> tickDrinking(s, now)
            is RiddleState.Thinking -> tickThinking(s, now)
            is RiddleState.Memorizing -> tickMemorizing(s, now)
            is RiddleState.Replying -> tickReplying(s, now)
        }
    }

    /**
     * Carry out what a decider decided: the effects in the order they were
     * given, and then the state they leave the diary in.
     *
     * This is the whole of the seam. Everything above it is arithmetic over
     * values and can be read in a test; everything below is a panel, a pen and
     * a network, and cannot.
     */
    private fun carryOut(decision: Decision) {
        for (effect in decision.effects) perform(effect)
        state = decision.state
    }

    private fun perform(effect: Effect) {
        when (effect) {
            is Effect.Render -> panel.render(effect.page)
            is Effect.BeginReply -> beginReply(effect.startYPx)
            is Effect.WriteText -> writeText(effect.text)
            is Effect.FeedReply -> feedReply(effect.delta)
            Effect.FlushReply -> flushPendingText()
            is Effect.AppendReplyInk -> panel.appendReplyStrokes(effect.strokes, effect.area)
            Effect.ClearReplyLayer -> panel.clearReplyLayer()
            is Effect.Refresh -> when (effect.mode) {
                RefreshMode.Fast -> panel.requestFastPartialRefresh(effect.area)
                RefreshMode.Handwriting -> panel.requestHandwritingRefresh(effect.area)
                RefreshMode.Quality -> panel.requestQualityPartialRefresh(effect.area)
            }
            Effect.FullRefresh -> panel.requestFullRefresh()
            is Effect.Status -> onStatusChanged(effect.text)
            is Effect.PenInput -> inkCapture.setInputEnabled(effect.enabled)
            Effect.ClearRawInk -> inkCapture.clearRawInkLayer()
            is Effect.AskDiary -> requestReply(effect.page)
            Effect.AbandonRequest -> replyJob?.cancel()
            is Effect.RecordTurn -> persistTurn(effect.transcript, effect.reply)
            is Effect.Note ->
                if (effect.warning) Log.w(TAG, effect.message) else Log.i(TAG, effect.message)
        }
    }

    // ---- Listening: main.rs:414-468 ----

    private fun tickListening(s: RiddleState.Listening, now: Long) {
        // Waits for the page to have a width: the greeting is laid out against
        // it, and onResume can run before the view has measured.
        if (!greeted && pageWidthPx() > 0) {
            greeted = true
            greet(now)
            return
        }
        if (!shouldCommitOnPause(sendMode, lastPenUpAtMs, now, hasInk = !strokeStore.isEmpty)) return
        commitPage(s.standingReply, now)
    }

    /**
     * The writer handing the page over themselves.
     *
     * The way a page is finished under [SendMode.Manual] — the label that calls
     * this is on the chrome only in that mode, since under [SendMode.Auto] the
     * pause already does it. It goes through the same [commitPage] the pause
     * does, so a page sent by hand and a page taken from a pause are the same
     * turn.
     *
     * Silent when there is nothing to send: an empty frame, or a diary already
     * busy with the last page. Neither is worth a line of chrome saying so —
     * the page itself already shows which of the two it is.
     */
    fun sendNow() {
        val s = state as? RiddleState.Listening ?: return
        if (strokeStore.isEmpty) return
        commitPage(s.standingReply, ticker.nowMs())
    }

    /**
     * The writer asking the diary to put its kept memories in order — the
     * memorize label on the chrome.
     *
     * A housekeeping pass, not a turn: the whole conversation so far goes up
     * with the instruction and the memory tools do the work, but nothing is
     * handed over and nothing reaches the conversation's history or the store
     * on disk — see [Conversation.memorize] and [decideMemorizing]. The one
     * visible trace is the diary's closing line, written out in its own hand
     * in place of the standing reply. No [PendingTurnMarker] either: what the
     * pass changed is already in the memory file the moment a tool ran, so a
     * crash mid-pass loses nothing that was kept.
     *
     * Silent when there is ink on the page: the pass ends by blanking the
     * page for that line, and ink not yet handed over must never be blanked
     * out from under the writer — send it or turn to a new page first. Silent
     * too while a turn is in flight, same as every other chrome action.
     */
    fun memorize() {
        val s = state as? RiddleState.Listening ?: return
        if (!strokeStore.isEmpty) return
        val now = ticker.nowMs()
        val diary = conversation
        if (diary == null) {
            // No voice to tend the memory: the same excuse an unanswerable
            // page gets, written in place of the standing reply.
            inkCapture.setInputEnabled(false)
            panel.render(PageRenderState.EMPTY)
            state = writeWholeReply(excuseFor("no key"), now, startYPx = REPLY_TOP_PX)
            onStatusChanged("Replying (no LLM key configured)")
            return
        }
        inkCapture.setInputEnabled(false)
        // Bound to the queue live at launch, same as [requestReply].
        val events = replyEvents
        replyJob = replyScope.launch {
            try {
                // Read here, not on the click's thread: the memory file is
                // disk, however small.
                val instruction = memorizeInstruction(readMemories(agent.workspace))
                val line = diary.memorize(
                    instruction = instruction,
                    onLookup = { tool, note -> events.add(ReplyEvent.Lookup(tool, note)) },
                )
                if (line.isBlank()) {
                    events.add(ReplyEvent.Error("empty reply"))
                } else {
                    events.add(ReplyEvent.Complete(line, transcript = ""))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "memory pass failed: $message")
                events.add(ReplyEvent.Error(message))
            }
        }
        state = RiddleState.Memorizing(startedAtMs = now, standingReply = s.standingReply)
        onStatusChanged("Committing to memory…")
    }

    /**
     * The way out of a turn that is taking too long, or has already said
     * enough — the label that calls this is on the chrome only while there is
     * something to stop.
     *
     * What it means depends on how far the turn got, and that decision is
     * [stopActionFor] rather than a `when` here. Waiting: the request is
     * dropped and the page comes back blank, which is the same clean page
     * "new page" gives. Writing: the pen stops where it stands and what it
     * has written stays, because the writer has already read it.
     */
    fun stopNow() {
        when (stopActionFor(state)) {
            StopAction.None -> return
            StopAction.Discard -> {
                Log.i(TAG, "turn stopped before any of it was written")
                clearPage()
                state = RiddleState.Listening()
                onStatusChanged(idleStatus())
            }
            StopAction.DropQuietly -> {
                Log.i(TAG, "memory pass dropped by hand")
                val standing = (state as? RiddleState.Memorizing)?.standingReply
                abandonReply()
                inkCapture.setInputEnabled(true)
                state = RiddleState.Listening(standingReply = standing)
                onStatusChanged(idleStatus())
            }
            StopAction.CutReply -> cutReplyShort()
        }
    }

    /**
     * Stops the pen mid-reply and leaves the page standing at exactly that
     * word.
     *
     * Three things have to agree afterwards or the diary contradicts itself:
     * what is drawn on the panel, what the standing reply holds, and what the
     * conversation records. All three are taken from the same cut — the
     * strokes the reveal cursor had actually begun — so a resumed conversation
     * cannot claim a sentence the writer never saw. Text already laid out past
     * that point is dropped with the request; it was never on the page.
     */
    private fun cutReplyShort() {
        abandonReply()
        pendingTurnMarker.clear()
        val cursor = replyCursor
        val plan = WritePlan(cursor?.revealed().orEmpty())
        val standing = written.seen(cursor?.revealedStrokes ?: 0).trim()
        Log.i(TAG, "reply stopped by hand after ${plan.strokes.size} strokes")
        pendingText.setLength(0)
        // Recorded already means the stream closed and the whole reply went to
        // disk while the pen was still drawing it; what stands on the page now
        // is less than that, and is what the conversation must carry.
        if (turnRecorded) rewriteRecordedTurn(standing) else if (standing.isNotEmpty()) {
            persistTurn(transcript = "", reply = standing)
        }
        written.reset()
        panel.render(PageRenderState(replyStrokes = plan.strokes))
        panel.clearReplyLayer()
        writeCursor = null
        replyCursor = null
        settleFinishedTurn(writeBounds(plan.strokes) ?: panel.drawingRect())
        inkCapture.setInputEnabled(true)
        state = RiddleState.Listening(standingReply = plan)
        onStatusChanged(idleStatus())
    }

    /**
     * The line the diary opens with, written out in its own hand and left
     * standing like any other reply — so a blank page is an invitation rather
     * than a question of whether the thing is working at all.
     *
     * It never reaches [Conversation]: the diary has not been told anything
     * yet, and a greeting nobody wrote in answer to would sit in the history as
     * a turn that never happened, coloring every reply after it.
     */
    private fun greet(now: Long) {
        val line = greeting(agent.greetings, previous = lastGreeting)
        lastGreeting = line
        inkCapture.setInputEnabled(false)
        state = writeWholeReply(line, now, startYPx = REPLY_TOP_PX)
        onStatusChanged("…")
    }

    private fun commitPage(standingReply: WritePlan?, now: Long) {
        val committed = strokeStore.strokes.map { InkStroke(it.points.toMutableList()) }
        strokeStore.clear()
        commitStrokes(committed, standingReply, now)
    }

    /**
     * Debug/demo entry point: writes [text] as if a pen had just written it —
     * runs it through the same handwriting generator the diary's own reply
     * uses, so the injected page looks and behaves like the real thing all
     * the way through the real call. Lets a demo run (or this class get
     * exercised end-to-end) without the Onyx pen, which the raw digitizer
     * layer requires and `adb shell input` cannot fake.
     *
     * No-ops outside [RiddleState.Listening] — same "one turn in flight"
     * rule a real pen is subject to.
     */
    fun commitDemoText(text: String) {
        val s = state as? RiddleState.Listening ?: return
        val plan = handwritingPlanner.plan(
            text = text,
            pageWidthPx = pageWidthPx(),
            fontSizePx = replyFontSizePx,
            lineHeightPx = replyLineHeightPx,
            startYPx = DEMO_TEXT_TOP_PX,
        )
        val committed = plan.strokes.map { stroke ->
            InkStroke(stroke.points.map { InkPoint(it.x, it.y, DEMO_PRESSURE) }.toMutableList())
        }
        if (committed.isEmpty()) return
        commitStrokes(committed, s.standingReply, ticker.nowMs())
    }

    /**
     * @param standingReply the reply the writer was answering, still on the
     * page — it dissolves along with [committed], since the page it stood on
     * is the page being handed to the diary.
     */
    private fun commitStrokes(committed: List<InkStroke>, standingReply: WritePlan?, now: Long) {
        // The page has been taken; the pause that gave it up is spent. Without
        // this the next Listening would inherit a pause already long enough and
        // commit the writer's first stroke out from under them.
        lastPenUpAtMs = null
        lastCommittedStrokes = committed
        inkCapture.setInputEnabled(false)
        inkCapture.clearRawInkLayer()
        val standingStrokes = standingReply?.strokes ?: emptyList()
        panel.render(PageRenderState(userStrokes = committed, replyStrokes = standingStrokes))

        if (conversation == null) {
            // No spirit to answer: skip the ink-drinking ritual and write the
            // excuse straight away, laid out below where the writing was
            // (main.rs:432-437). Like `beginReplying`, the page blanks first —
            // a reply is always revealed onto a clear page.
            panel.render(PageRenderState.EMPTY)
            state = writeWholeReply(
                text = excuseFor("no key"),
                now = now,
                startYPx = replyStartBelow(committed, panel.drawingRect().height, lineHeightPx = replyLineHeightPx),
            )
            onStatusChanged("Replying (no LLM key configured)")
            return
        }

        requestReply(committed)
        panel.render(
            PageRenderState(
                userStrokes = committed,
                replyStrokes = standingStrokes,
                dissolve = DissolveProgress(stage = 0, stages = DRINK_STAGES),
            ),
        )
        val dirtyRect = union(inkBounds(committed), writeBounds(standingStrokes))
            ?: panel.drawingRect()
        panel.requestFastPartialRefresh(dirtyRect)
        state = RiddleState.Drinking(
            committedStrokes = committed,
            standingReply = standingReply,
            dirtyRect = dirtyRect,
            stage = 0,
            nextStageAtMs = now + DRINK_STAGE_MS,
        )
        onStatusChanged("Drinking the ink…")
    }

    private fun requestReply(strokes: List<InkStroke>) {
        val diary = conversation ?: return
        // A page is genuinely in flight from here — see [PendingTurnMarker].
        pendingTurnMarker.set()
        // Bound to the queue live at launch, so a request abandoned mid-flight
        // cannot post into the one the next turn reads.
        val events = replyEvents
        replyJob = replyScope.launch {
            val png = PageRasterizer.rasterize(strokes)
            if (png == null) {
                events.add(ReplyEvent.Error("blank page"))
                return@launch
            }
            pageArchive?.save(png, System.currentTimeMillis())?.let { file ->
                Log.i(TAG, "page sent, archived at ${file.absolutePath}")
            }
            try {
                val turn = diary.ask(
                    pageImagePng = png,
                    onReplyText = { delta -> events.add(ReplyEvent.Delta(delta)) },
                    onLookup = { tool, note -> events.add(ReplyEvent.Lookup(tool, note)) },
                    onContractRepair = {
                        events.add(ReplyEvent.Lookup("transcribe_page", "reading the page again…"))
                    },
                )
                Log.i(
                    TAG,
                    "turn answered: ${diary.messages.size} messages remembered, " +
                        "read back \"${turn.transcript.take(60)}\"",
                )
                if (turn.reply.isBlank()) {
                    events.add(ReplyEvent.Error("empty reply"))
                } else {
                    events.add(ReplyEvent.Complete(turn.reply, turn.transcript))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "reply failed: $message")
                events.add(ReplyEvent.Error(message))
            }
        }
    }

    // ---- Drinking: main.rs:470-485 ----

    private fun tickDrinking(s: RiddleState.Drinking, now: Long) {
        carryOut(decideDrinking(s, now) ?: return)
    }

    // ---- Thinking: main.rs:487-549 ----

    private fun tickThinking(s: RiddleState.Thinking, now: Long) {
        val decision = decideThinking(
            s = s,
            now = now,
            event = replyEvents.poll(),
            page = panel.drawingRect(),
            pageInFlight = lastCommittedStrokes,
        )
        carryOut(decision ?: return)
    }

    // ---- Memorizing: no main.rs ancestor — the memorize label is this port's own ----

    private fun tickMemorizing(s: RiddleState.Memorizing, now: Long) {
        carryOut(decideMemorizing(s, now, replyEvents.poll()) ?: return)
    }

    /**
     * Arm the reply animation: a pen at [startYPx] to lay words out, a cursor
     * to reveal what it lays down, and a blank layer on the view for the ink
     * to accumulate on.
     */
    private fun startReplying(now: Long, startYPx: Float): RiddleState.Replying {
        beginReply(startYPx)
        return RiddleState.Replying(nextTickAtMs = now, lastArrivalAtMs = now)
    }

    /** [Effect.BeginReply]: the writing apparatus, with no state of its own. */
    private fun beginReply(startYPx: Float) {
        writeCursor = handwritingPlanner.cursor(
            pageWidthPx = pageWidthPx(),
            fontSizePx = replyFontSizePx,
            lineHeightPx = replyLineHeightPx,
            startYPx = startYPx,
            bottomLimitPx = panel.drawingRect().height - REPLY_BOTTOM_PX,
        )
        replyCursor = ReplyRevealCursor()
        pendingText.setLength(0)
        written.reset()
        turnRecorded = false
        panel.beginReply()
    }

    /** A reply that is already whole — a greeting, an excuse, a short answer. */
    private fun writeWholeReply(text: String, now: Long, startYPx: Float): RiddleState.Replying {
        val replying = startReplying(now, startYPx)
        writeText(text)
        return replying.copy(streamEnded = true)
    }

    /** Lay [text] down at the nib and hand the new strokes to the revealing pen. */
    private fun writeText(text: String) {
        val cursor = writeCursor ?: return
        replyCursor?.add(cursor.append(text))
    }

    /**
     * Parse [markup] — a complete `<svg>…</svg>` block the reply carried
     * inline — and lay the figure on the page at the nib, its strokes handed
     * to the revealing pen so a drawing appears the way handwriting does:
     * stroke by stroke, not as a stamp.
     *
     * Refusals are quiet on purpose. Markup the parser cannot read, or a page
     * too full for a readable figure, drops the drawing with a log note and
     * the turn carries on in words — there is no tool round left open to
     * answer an error into, and ink that was never laid cannot be missed by
     * [written] or the history.
     */
    private fun drawFigure(markup: String) {
        val cursor = writeCursor ?: return
        val figure = runCatching { svgFigure(markup) }.getOrElse { error ->
            Log.w(TAG, "figure markup refused: ${error.message}")
            return
        }
        val placed = cursor.placeFigure(figure)
        if (placed == null) {
            Log.w(TAG, "figure dropped: no room left on the page")
            return
        }
        Log.i(TAG, "figure placed: ${placed.size} strokes, ${placed.sumOf { it.points.size }} points")
        replyCursor?.add(placed)
    }

    /**
     * Takes streamed [delta] and writes as much of it as is now settled; the
     * rest waits for the delta that settles it (see [writableCut]).
     *
     * A figure travels in this same stream as `<svg>…</svg>` markup, and a
     * completed block is as settled as a word with whitespace after it: the
     * text before it is written, the block itself becomes ink through
     * [drawFigure], and only then is the remainder cut as usual —
     * [writableCut] holds anything from a still-open block back, so markup
     * never reaches the page as angle brackets.
     */
    private fun feedReply(delta: String) {
        pendingText.append(delta)
        while (true) {
            val block = completedSvgBlock(pendingText.toString()) ?: break
            val before = pendingText.substring(0, block.first)
            val markup = pendingText.substring(block.first, block.last + 1)
            pendingText.delete(0, block.last + 1)
            if (before.isNotBlank()) {
                val ink = inkable(before)
                writeText(ink)
                written.append(ink, strokesLaidOut = writeCursor?.strokes?.size ?: 0)
            }
            drawFigure(markup)
        }
        val cut = writableCut(pendingText.toString())
        if (cut <= 0) return
        val chunk = pendingText.substring(0, cut)
        pendingText.delete(0, cut)
        val ink = inkable(chunk)
        writeText(ink)
        written.append(ink, strokesLaidOut = writeCursor?.strokes?.size ?: 0)
    }

    /**
     * A settled chunk as the pen should write it: emphasis stripped, and the
     * whitespace that closed it kept as one character.
     *
     * [plainText] trims, and every chunk ends on whitespace by construction
     * ([writableCut] cuts at a word break) — so trimming and nothing else would
     * butt each chunk against the next. The pen itself does not care, since it
     * tokenizes anyway, but [written] is plain text that gets persisted and
     * read back: dropped, the record of a cut-short turn reads `Tahiểu`.
     */
    private fun inkable(chunk: String): String {
        val text = plainText(chunk)
        if (text.isEmpty()) return ""
        val brokeLine = chunk.takeLastWhile { it.isWhitespace() }.contains('\n')
        return if (brokeLine) text + "\n" else "$text "
    }

    /**
     * Writes one answered turn into the conversation being had, off the tick
     * thread — a page that turned out to be illegible still counts as a turn,
     * because the diary did answer it and the answer must read back in order.
     */
    private fun persistTurn(transcript: String, reply: String) {
        val turn = StoredTurn(
            timestampMs = System.currentTimeMillis(),
            transcript = transcript,
            reply = reply,
        )
        val id = conversationId
        val startedAtMs = conversationStartedAtMs
        turnRecorded = true
        diskWriter.execute {
            runCatching { conversationStore.appendTurn(id, startedAtMs, turn, agentId = agent.id) }
                .onFailure { error -> Log.w(TAG, "conversation append failed: ${error.message}") }
        }
    }

    /**
     * Corrects the turn just recorded to the words the writer actually saw.
     *
     * A streamed reply is recorded the moment the stream closes, which is
     * seconds before the pen has finished drawing it — that ordering is what
     * survives the app being killed mid-reply, and it is why stopping by hand
     * has to go back and fix what was written down. Same single thread as the
     * append, so the correction cannot arrive before the thing it corrects.
     */
    private fun rewriteRecordedTurn(reply: String) {
        val id = conversationId
        diskWriter.execute {
            runCatching { conversationStore.replaceLastReply(id, reply) }
                .onFailure { error -> Log.w(TAG, "conversation rewrite failed: ${error.message}") }
        }
    }

    // ---- Replying: main.rs:551-637 ----

    private fun tickReplying(s: RiddleState.Replying, now: Long) {
        // Words are taken in on every tick, not only on the slower drawing
        // one: text that arrived is text the pen can be laying out already.
        var next = if (s.streamEnded) s else takeArrivingWords(s, now)
        if (now < next.nextTickAtMs) {
            state = next
            return
        }
        val cursor = replyCursor ?: return

        // Only this tick's new ink is painted, onto the layer holding
        // everything painted before it — so a tick costs the points it
        // reveals, not the whole reply so far (main.rs:590-609's `dirty`
        // accumulator is likewise per-tick, not cumulative).
        val newlyRevealed = cursor.revealMore(REPLY_POINTS_PER_TICK)
        val newBounds = writeBounds(newlyRevealed)
        if (newlyRevealed.isNotEmpty()) {
            panel.appendReplyStrokes(newlyRevealed, newBounds ?: panel.drawingRect())
        }
        var dirty = union(next.pendingDirtyRect, newBounds)

        // Caught up with a model still writing: hold the nib where it is and
        // mark it, rather than ending a reply that isn't finished.
        val (afterBlot, blotDirty) = blotThisTick(next, waiting = !next.streamEnded && cursor.caughtUp, now = now)
        next = afterBlot
        dirty = union(dirty, blotDirty)

        // Points reveal every tick, but the panel is throttled to its own
        // refresh cadence — flushing every tick queued refresh requests
        // faster than the hardware could drain them (see class doc).
        val done = next.streamEnded && cursor.caughtUp && writeCursor?.pageFull != true
        val (flushedRect, lastRefreshAtMs) = if (dirty != null && (done || now - next.lastRefreshAtMs >= REPLY_REFRESH_INTERVAL_MS)) {
            panel.requestHandwritingRefresh(dirty)
            null to now
        } else {
            dirty to next.lastRefreshAtMs
        }

        // The page is written full and has been shown; wipe it and carry on
        // at the top. No pause first: the pen writes far slower than anyone
        // reads, so the lines above have stood for a good while by now.
        if (cursor.caughtUp && writeCursor?.pageFull == true &&
            newlyRevealed.isEmpty() && flushedRect == null
        ) {
            turnPage()
            state = next.copy(
                nextTickAtMs = now + REPLY_TICK_MS,
                pendingDirtyRect = null,
                lastRefreshAtMs = now,
                blotAt = null,
            )
            return
        }

        if (done) {
            finishTurn()
        } else {
            state = next.copy(
                nextTickAtMs = now + REPLY_TICK_MS,
                pendingDirtyRect = flushedRect,
                lastRefreshAtMs = lastRefreshAtMs,
            )
        }
    }

    /**
     * Drains everything the stream has produced since the last tick into the
     * laying-out pen, and decides whether the stream is over.
     *
     * A stream that fails mid-reply ends the turn where it stands. Ink cannot
     * be lifted off the page: there is no retrying the request behind words
     * the writer has already read, and no writing an excuse for a diary that
     * plainly did answer.
     */
    private fun takeArrivingWords(s: RiddleState.Replying, now: Long): RiddleState.Replying {
        var next = s
        while (true) {
            when (val event = replyEvents.poll()) {
                is ReplyEvent.Delta -> {
                    feedReply(event.text)
                    next = next.copy(lastArrivalAtMs = now)
                }
                // A lookup after the pen has started: nothing to write, but it
                // is the stream saying it is still alive, which is exactly what
                // the patience clock here measures.
                is ReplyEvent.Lookup -> {
                    Log.i(TAG, "looking up mid-reply: ${event.tool}")
                    next = next.copy(lastArrivalAtMs = now)
                }
                is ReplyEvent.Complete -> {
                    flushPendingText()
                    persistTurn(event.transcript, plainText(event.text))
                    return next.copy(streamEnded = true)
                }
                is ReplyEvent.Error -> {
                    Log.w(TAG, "reply cut short mid-writing: ${event.message}")
                    flushPendingText()
                    persistCutShortTurn()
                    return next.copy(streamEnded = true)
                }
                null -> {
                    // A stream that stops arriving would otherwise leave the
                    // blot pulsing forever.
                    if (now - next.lastArrivalAtMs < REPLY_PATIENCE_MS) return next
                    Log.w(TAG, "reply stalled mid-writing, giving up")
                    replyJob?.cancel()
                    flushPendingText()
                    persistCutShortTurn()
                    return next.copy(streamEnded = true)
                }
            }
        }
    }

    /**
     * Whatever was held back was never a mark; it is words, and it is the
     * last of them — with two exceptions a figure adds. A block that
     * completed on the very last delta is drawn ([feedReply]'s own loop,
     * reused by feeding it nothing new), and a block the stream ended in the
     * middle of is dropped with a note: half a figure's markup is not words
     * either, and inked angle brackets cannot be taken back.
     */
    private fun flushPendingText() {
        if (pendingText.isEmpty()) return
        feedReply("")
        val text = pendingText.toString()
        pendingText.setLength(0)
        val opensAt = svgOpenAt(text)
        if (opensAt != null) Log.w(TAG, "reply ended mid-figure; the unfinished markup is dropped")
        val ink = plainText(if (opensAt == null) text else text.substring(0, opensAt))
        if (ink.isEmpty()) return
        writeText(ink)
        written.append(ink, strokesLaidOut = writeCursor?.strokes?.size ?: 0)
    }

    /**
     * Records a turn the stream never finished: what stands on the page, with
     * no transcript, because the page's own words come back at the end of the
     * stream and this one never got there.
     *
     * Nothing is written when the page is bare — a stream that failed before
     * its first word left no turn behind to record.
     */
    private fun persistCutShortTurn() {
        if (turnRecorded) return
        val standing = written.whole.trim()
        if (standing.isEmpty()) return
        persistTurn(transcript = "", reply = standing)
    }

    /**
     * The waiting blot for this tick: blinking at the nib while the pen has
     * nothing to write, gone the moment it does. Returns the state carrying
     * the blot's position, and the area the panel has to redraw for it.
     */
    private fun blotThisTick(
        s: RiddleState.Replying,
        waiting: Boolean,
        now: Long,
    ): Pair<RiddleState.Replying, PageRect?> {
        if (!waiting) {
            val showing = s.blotAt ?: return s to null
            panel.render(PageRenderState.EMPTY)
            return s.copy(blotAt = null) to blotRect(showing)
        }
        if (now < s.nextBlotAtMs) return s to null
        val at = if (s.blotAt == null) writeCursor?.penPoint else null
        panel.render(if (at == null) PageRenderState.EMPTY else PageRenderState(blotAt = at))
        val moved = at ?: s.blotAt
        return s.copy(blotAt = at, nextBlotAtMs = now + PULSE_MS) to moved?.let { blotRect(it) }
    }

    /**
     * A fresh page for the rest of a long reply: the ink already shown comes
     * off, and the words the pen had no room for go on at the top.
     */
    private fun turnPage() {
        val cursor = writeCursor ?: return
        Log.i(TAG, "reply fills the page, carrying on overleaf")
        panel.render(PageRenderState.EMPTY)
        panel.beginReply()
        replyCursor = ReplyRevealCursor().apply { add(cursor.nextPage()) }
        // A whole page of ink leaving at once is exactly what a full refresh
        // is for — and it pays off the ghosting this turn has built up.
        turnsSinceFullRefresh = 0
        panel.requestFullRefresh()
    }

    /**
     * The reply is written: hand the page back to the writer with that reply
     * still standing on it.
     *
     * The reply moves off [com.riddleboox.app.ui.RegionView]'s reply layer and
     * into the render state first. The layer is a baked bitmap — it can only
     * leave as one block, where the standing reply has to dissolve point by
     * point alongside the next page of writing. Handing the plan over before
     * retiring the layer keeps the picture identical across the switch
     * (RegionView draws exactly what it holds).
     */
    private fun finishTurn() {
        pendingTurnMarker.clear()
        val plan = writeCursor?.plan() ?: WritePlan(emptyList())
        panel.render(PageRenderState(replyStrokes = plan.strokes))
        panel.clearReplyLayer()
        writeCursor = null
        replyCursor = null
        pendingText.setLength(0)
        settleFinishedTurn(writeBounds(plan.strokes) ?: panel.drawingRect())
        inkCapture.setInputEnabled(true)
        state = RiddleState.Listening(standingReply = plan)
        onStatusChanged(idleStatus())
    }

    /**
     * A finished turn leaves the page blank again, and every partial update
     * that got it there left a little ghosting behind. Most turns settle their
     * own area with GU and never flash; every [TURNS_PER_FULL_REFRESH]-th turn
     * pays the accumulated ghosting off with one full-screen GC.
     */
    private fun settleFinishedTurn(area: PageRect) {
        turnsSinceFullRefresh++
        if (turnsSinceFullRefresh >= TURNS_PER_FULL_REFRESH) {
            turnsSinceFullRefresh = 0
            panel.requestFullRefresh()
        } else {
            panel.requestQualityPartialRefresh(area)
        }
    }

    /**
     * Turn to a new page: the conversation being had is left where it stands in
     * the history, and a new one begins.
     *
     * Nothing is lost by it — every turn was written to disk as it was answered
     * — and nothing is carried over either: the diary forgets the evening
     * rather than answering the next line as though it knew the writer. The
     * greeting comes back, because this is a first page again.
     */
    fun newPage() {
        clearPage()
        conversation?.reset()
        conversationId = newConversationId()
        conversationStartedAtMs = System.currentTimeMillis()
        greeted = false
        state = RiddleState.Listening()
        onStatusChanged(idleStatus())
    }

    /**
     * Take up [past] where it was left off: the diary is handed its memory of
     * that conversation back, and the turns written from here are added to it.
     *
     * Its last answer is written out again in ink and left standing, so the
     * page the writer returns to is the page they left — and the next line goes
     * underneath it, exactly as it would have that evening.
     */
    fun resume(past: StoredConversation) {
        clearPage()
        conversation?.restore(past.toTurns())
        conversationId = past.id
        conversationStartedAtMs = past.startedAtMs
        // Nothing to greet: the diary and the writer have already met, in this
        // very conversation.
        greeted = true
        // A page with no width yet cannot be laid out on — the pen would have
        // no room for any word and hold all of them (see [WriteCursor]). The
        // memory is restored either way; only the ink of that last answer is
        // skipped, and [greet] guards the same way for the same reason.
        val standing = past.turns.lastOrNull()?.reply.orEmpty().takeIf { pageWidthPx() > 0 }.orEmpty()
        if (standing.isBlank()) {
            state = RiddleState.Listening()
            onStatusChanged(idleStatus())
            return
        }
        // The pen closes while that reply is laid down, exactly as it does for
        // a live one ([commitStrokes]) — ink from a hand resting on the page
        // would otherwise land in the middle of it. [finishTurn] opens it again.
        inkCapture.setInputEnabled(false)
        state = writeWholeReply(standing, ticker.nowMs(), startYPx = REPLY_TOP_PX)
        onStatusChanged("…")
    }

    /** Abandon whatever is happening and return the page to blank paper. */
    private fun clearPage() {
        abandonReply()
        pendingTurnMarker.clear()
        lastPenUpAtMs = null
        strokeStore.clear()
        inkCapture.setInputEnabled(true)
        inkCapture.clearRawInkLayer()
        // Clearing mid-reply would otherwise leave that reply's ink baked into
        // the layer, showing through the blank page it returns to.
        panel.clearReplyLayer()
        writeCursor = null
        replyCursor = null
        pendingText.setLength(0)
        // Abandoned on purpose, so it is not recorded and does not leak into
        // the next turn: the writer asked for a clean page mid-reply.
        written.reset()
        panel.render(PageRenderState.EMPTY)
        turnsSinceFullRefresh = 0
        panel.requestFullRefresh()
    }

    companion object {
        private const val TAG = "RiddleStateMachine"

        /** File-name-safe, collision-free, and meaningless on purpose: a
         * conversation is recognised by its first line, not by its id. */
        private fun newConversationId(): String = UUID.randomUUID().toString()

        private const val TICK_MS = 16L

        /** Where [commitDemoText]'s synthetic "user" writing starts — near the page top, like a real first line. */
        private const val DEMO_TEXT_TOP_PX = 120f

        /** Mid-weight, uniform pressure — a demo pen never presses harder or softer. */
        private const val DEMO_PRESSURE = 0.6f

        /**
         * How many turns of rect-scoped updates the page carries before one
         * full-screen GC clears their accumulated ghosting. Low enough that
         * faint leftovers never build into something readable, high enough
         * that the flash is a rare event rather than punctuation between turns.
         */
        private const val TURNS_PER_FULL_REFRESH = 5
    }
}
