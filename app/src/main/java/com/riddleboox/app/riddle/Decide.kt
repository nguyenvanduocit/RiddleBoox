package com.riddleboox.app.riddle

import com.riddleboox.app.handwriting.WritePoint
import com.riddleboox.app.ink.InkStroke
import com.riddleboox.app.reply.plainText
import com.riddleboox.app.settings.SendMode

/**
 * What one tick came to: where the diary is now, and what the world has to be
 * told about it.
 *
 * Null from a decider means the tick had nothing to do — most of them don't,
 * sixty times a second — and neither the state nor the page changes.
 */
data class Decision(val state: RiddleState, val effects: List<Effect>)

/** Milliseconds a single stage of the ink-drinking dissolve holds for. */
internal const val DRINK_STAGE_MS = 70L

/**
 * Stages the ink-drinking dissolve goes through — see [RiddleState.Drinking].
 * Measured on-device: 14 stages read the same as 8 (the coarser step is not
 * visible at [DRINK_STAGE_MS] speed) while costing noticeably more e-ink
 * refresh time, so 8 is what's actually spent — the ink is meant to be gone
 * well before the diary's answer is back, not the other way around.
 */
internal const val DRINK_STAGES = 8

/** How long the pen rests before [SendMode.Auto] reads the page as finished. */
internal const val IDLE_COMMIT_MS = 2_800L

/** How often the reply pen ticks while a turn is being written out — see [RiddleStateMachine]. */
internal const val REPLY_TICK_MS = 14L

/** Points revealed per [REPLY_TICK_MS] tick while a turn is being written out. */
internal const val REPLY_POINTS_PER_TICK = 26

/**
 * Floor between actual panel refreshes while a turn is being written out —
 * points still reveal every [REPLY_TICK_MS], this just throttles how often
 * that gets pushed to the (much slower) e-ink hardware.
 */
internal const val REPLY_REFRESH_INTERVAL_MS = 120L

/**
 * Whether this pause is the writer finishing.
 *
 * Under [SendMode.Auto] a long enough rest is the answer, which is the whole
 * gesture the diary was built around: write a line, look up, and it is already
 * being read. It is also the wrong answer for a long entry, where stopping to
 * think is not stopping — so under [SendMode.Manual] no pause is ever long
 * enough and the page is only taken when the writer asks for it
 * ([RiddleStateMachine.sendNow]).
 *
 * @param lastPenUpAtMs when the pen last left the page, or null while it is
 * down — or while the page has already been taken.
 * @param hasInk whether there is anything in the frame to hand over. An empty
 * page committed would ask the diary to answer a blank sheet.
 */
fun shouldCommitOnPause(
    mode: SendMode,
    lastPenUpAtMs: Long?,
    now: Long,
    hasInk: Boolean,
): Boolean {
    if (mode != SendMode.Auto) return false
    val lastUp = lastPenUpAtMs ?: return false
    if (now - lastUp < IDLE_COMMIT_MS) return false
    return hasInk
}

/**
 * What stopping does, from wherever the diary is when the writer asks for it.
 *
 * The writer asks one thing — stop — and it means two different things
 * depending on whether any of the answer has been written yet, which is the
 * kind of decision that belongs in a function that can be read rather than in
 * a `when` buried in the shell.
 */
enum class StopAction {

    /** Nothing is in flight. The label is not even on the chrome. */
    None,

    /**
     * Not a word has been drawn: drop the request and hand back a blank page.
     * The writer's own page is already dissolved by this point and does not
     * come back — it was handed over the moment it was taken.
     */
    Discard,

    /**
     * Words are standing on the page. The pen stops where it is and they stay,
     * as the reply the next page answers.
     */
    CutReply,
}

/** @see StopAction */
fun stopActionFor(state: RiddleState): StopAction = when (state) {
    is RiddleState.Listening -> StopAction.None
    is RiddleState.Drinking -> StopAction.Discard
    is RiddleState.Thinking -> StopAction.Discard
    is RiddleState.Replying -> StopAction.CutReply
}

/**
 * What the status line says while the page waits for the writer — the one place
 * the current [SendMode] is spelled out in words, so that what the chrome
 * promises and what the tick actually does cannot drift apart.
 */
fun idleStatus(mode: SendMode): String = when (mode) {
    SendMode.Auto -> "Viết trong khung"
    SendMode.Manual -> "Viết xong bấm gửi"
}

/**
 * How long the blot at the pen's nib stays lit, and then dark, while the pen
 * waits mid-reply for the next words to arrive.
 *
 * The only thing left on this clock. The diary's waiting used to pulse a second
 * blot in the middle of the page at the same interval; that one is gone, and
 * what it was standing in for is said on the status line instead — see
 * [decideThinking].
 */
internal const val PULSE_MS = 600L

/**
 * How long the diary is given to answer before the page gives up on it.
 *
 * Measured from the moment the page was handed over, and deliberately *not*
 * extended by a lookup: a turn that spends two minutes reading the writer's
 * library is a turn the writer has stopped waiting for.
 */
internal const val REPLY_PATIENCE_MS = 120_000L

/** How many times a failure that is not the network gets tried again. */
internal const val REPLY_RETRY_LIMIT = 3

/**
 * The wait before a retry.
 *
 * Wi-Fi blips resolve in seconds (observed ~16s disconnect -> reconnect
 * on-device) while a DNS or connect failure returns almost instantly, so
 * retrying the moment one comes back would burn the whole budget before the
 * network is up.
 */
internal const val REPLY_RETRY_DELAY_MS = 6_000L

/**
 * Ceiling on [rateLimitRetryDelayMs] — a burst of 429s backs off past the
 * flat [REPLY_RETRY_DELAY_MS], but must still leave room for a few attempts
 * inside [REPLY_PATIENCE_MS] rather than spending the whole budget on one wait.
 */
internal const val REPLY_RATE_LIMIT_MAX_DELAY_MS = 30_000L

/**
 * Backoff for a rate-limited ([isRateLimited]) retry: doubles from
 * [REPLY_RETRY_DELAY_MS] each attempt, capped at [REPLY_RATE_LIMIT_MAX_DELAY_MS].
 * Retrying a throttled request at the same flat interval every other failure
 * uses only prolongs the throttling; growing the wait gives the provider's
 * window a chance to clear.
 */
internal fun rateLimitRetryDelayMs(attempt: Int): Long {
    val shift = (attempt - 1).coerceIn(0, 6)
    return minOf(REPLY_RETRY_DELAY_MS shl shift, REPLY_RATE_LIMIT_MAX_DELAY_MS)
}

/**
 * One stage further into the ink dissolve, or out the other side of it.
 *
 * main.rs:470-485. The writer's committed strokes and the standing reply they
 * answered come off on the same stages, so the page empties as one gesture, and
 * the area they occupy was worked out once at commit — the dissolve does not
 * move, so there is nothing to recompute.
 */
fun decideDrinking(s: RiddleState.Drinking, now: Long): Decision? {
    if (now < s.nextStageAtMs) return null
    val nextStage = s.stage + 1
    if (nextStage >= DRINK_STAGES) {
        return Decision(
            state = RiddleState.Thinking(startedAtMs = now),
            effects = listOf(
                Effect.Render(PageRenderState.EMPTY),
                // The whole area changed at once, which is what GU is for: DU
                // would leave it visibly dirty and GC would blink the panel.
                Effect.Refresh(s.dirtyRect, RefreshMode.Quality),
                Effect.Status("Đang nghĩ…"),
            ),
        )
    }
    return Decision(
        state = s.copy(stage = nextStage, nextStageAtMs = now + DRINK_STAGE_MS),
        effects = listOf(
            Effect.Render(
                PageRenderState(
                    userStrokes = s.committedStrokes,
                    replyStrokes = s.standingReply?.strokes ?: emptyList(),
                    dissolve = DissolveProgress(stage = nextStage, stages = DRINK_STAGES),
                ),
            ),
            Effect.Refresh(s.dirtyRect, RefreshMode.Fast),
        ),
    )
}

/**
 * Waiting on the diary: a blank page until an answer arrives, the patience runs
 * out, or a failure is worth retrying.
 *
 * Blank, and silent — nothing here is on a clock. Riddle pulsed a blot in the
 * middle of the page every 600ms (main.rs:487-549), which on a panel that
 * repaints by flashing is up to two hundred refreshes for a single wait, and
 * says only that the program has not died. What replaces it is the status line,
 * and it changes when something has actually happened: the diary started
 * thinking, it stopped to look a book up, a failed request is being retried.
 * Two to four changes in a turn instead of two hundred, and each one carries a
 * fact.
 *
 * @param event the one piece of news this tick has, or null when the request
 * has produced nothing since the last.
 * @param page the drawing area, used when a failure has to be written onto it.
 * @param pageInFlight the strokes a retry would have to send again — the page
 * itself is not carried in [RiddleState.Thinking], since only ever one is in
 * flight at a time.
 */
fun decideThinking(
    s: RiddleState.Thinking,
    now: Long,
    event: ReplyEvent?,
    page: PageRect,
    pageInFlight: List<InkStroke>,
): Decision? {
    when (event) {
        // The first words are in: the page clears and the pen starts, with the
        // rest of the reply still on its way.
        is ReplyEvent.Delta -> return Decision(
            state = RiddleState.Replying(nextTickAtMs = now, lastArrivalAtMs = now),
            effects = listOf(
                Effect.BeginReply(REPLY_TOP_PX),
                Effect.FeedReply(event.text),
                Effect.Status("Đang viết trả lời…"),
            ),
        )

        // A reply short enough to arrive whole before the first tick.
        is ReplyEvent.Complete -> {
            val text = plainText(event.text)
            return Decision(
                state = RiddleState.Replying(
                    nextTickAtMs = now,
                    lastArrivalAtMs = now,
                    streamEnded = true,
                ),
                effects = listOf(
                    Effect.RecordTurn(event.transcript, text),
                    Effect.BeginReply(REPLY_TOP_PX),
                    Effect.WriteText(text),
                    Effect.Status("Đang viết trả lời…"),
                ),
            )
        }

        // Still thinking, and now with a reason to say so. Only the caption
        // changes; the state is handed back untouched so that neither the retry
        // count nor the patience clock is reset by it.
        is ReplyEvent.Lookup -> return Decision(
            state = s,
            effects = listOf(
                Effect.Note("looking up: ${event.tool}"),
                Effect.Status(event.note.ifBlank { "Đang lần giở…" }),
            ),
        )

        is ReplyEvent.Error -> {
            // A request that never left the device keeps retrying until the
            // patience budget runs out — this panel's Wi-Fi drops DNS for tens
            // of seconds whenever it changes bands, which outlasts a
            // three-attempt budget. A provider-side 429 gets the same
            // unlimited-attempts treatment (it resolves on its own, same as a
            // Wi-Fi blip) but backs off instead of retrying at a flat interval.
            // Anything else (a bad key, a wrong model) will fail the same way
            // forever, so it gets three.
            val unreachable = isUnreachable(event.message)
            val rateLimited = isRateLimited(event.message)
            val attempt = s.retryCount + 1
            if (unreachable || rateLimited || s.retryCount < REPLY_RETRY_LIMIT) {
                val delay = if (rateLimited) rateLimitRetryDelayMs(attempt) else REPLY_RETRY_DELAY_MS
                return Decision(
                    state = s.copy(retryCount = attempt, retryAtMs = now + delay),
                    effects = listOf(
                        Effect.Note("reply retry $attempt after: ${event.message}", warning = true),
                        Effect.Status(
                            when {
                                rateLimited -> "Quá nhiều yêu cầu — đang chờ rồi thử lại (lần $attempt)…"
                                unreachable -> "Mất kết nối — đang thử lại (lần $attempt)…"
                                else -> "Trục trặc — thử lại $attempt/$REPLY_RETRY_LIMIT…"
                            },
                        ),
                    ),
                )
            }
            return excuse(event.message, now, page)
        }

        null -> Unit
    }

    if (now - s.startedAtMs >= REPLY_PATIENCE_MS) {
        val giveUp = excuse("timed out", now, page)
        return giveUp.copy(effects = listOf(Effect.AbandonRequest) + giveUp.effects)
    }

    val retryAt = s.retryAtMs
    if (retryAt != null) {
        if (now < retryAt) return null
        return Decision(
            state = s.copy(retryAtMs = null),
            effects = listOf(Effect.AskDiary(pageInFlight)),
        )
    }

    // Nothing has happened and nothing is due: the page is already blank and
    // the status line already says what it is waiting for, so this tick has
    // nothing to change.
    return null
}

/**
 * The line written when the diary could not answer at all. Never persisted: a
 * canned "the diary could not answer" would clutter the conversation with turns
 * that never really happened.
 */
private fun excuse(reason: String, now: Long, page: PageRect) = Decision(
    state = RiddleState.Replying(nextTickAtMs = now, lastArrivalAtMs = now, streamEnded = true),
    effects = listOf(
        Effect.BeginReply(REPLY_TOP_PX),
        Effect.WriteText(excuseFor(reason)),
        Effect.Status("Đang viết trả lời…"),
    ),
)
