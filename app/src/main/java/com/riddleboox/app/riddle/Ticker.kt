package com.riddleboox.app.riddle

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The clock the diary lives on: what time it is, and when to look again.
 *
 * One interface rather than two, because the two halves have to agree. Every
 * deadline in [RiddleStateMachine] reads `now >= somethingAtMs`, where `now`
 * comes from the clock and `somethingAtMs` was set an interval the scheduler
 * was asked for ago — a tick scheduled on one time base and measured against
 * another drifts, and the drift shows up as a dissolve stage that lasts twice
 * as long on one device as another.
 *
 * It exists so the state machine can be run without a device at all: the whole
 * of its timing — retry budgets, dissolve stages, blot pulses, the refresh
 * throttle — is `now` arithmetic, and `Handler` and `SystemClock` are stubs
 * that throw in a JVM test rather than compute. A test drives a fake forward by
 * whole seconds in a microsecond; the panel drives the real one at 16ms.
 */
interface Ticker {

    /**
     * Milliseconds since boot. Monotonic on purpose: the writer's tablet
     * correcting its wall clock mid-reply must not skip a deadline or hang one.
     */
    fun nowMs(): Long

    /**
     * Call [tick] as soon as possible, and again every [everyMs] until [stop].
     *
     * Idempotent: starting an already-running ticker replaces the loop rather
     * than racing a second one alongside it.
     */
    fun start(everyMs: Long, tick: () -> Unit)

    /** Stop calling back. Safe when never started, and safe from inside a tick. */
    fun stop()
}

/**
 * The real one: the main looper, which is also the thread every view call the
 * tick makes has to happen on.
 */
class HandlerTicker(looper: Looper = Looper.getMainLooper()) : Ticker {

    private val handler = Handler(looper)

    /** The loop currently armed, and the token [stop] cancels it by. */
    private var scheduled: Runnable? = null

    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun start(everyMs: Long, tick: () -> Unit) {
        stop()
        val loop = object : Runnable {
            override fun run() {
                tick()
                // Re-armed after the work rather than before it, so a tick that
                // runs long delays the next one instead of stacking a queue of
                // ticks the panel can never drain. The identity check is what
                // makes stopping from inside a tick actually stop: this
                // runnable is already off the queue by then, and re-arming it
                // here would put the loop back.
                if (scheduled === this) handler.postDelayed(this, everyMs)
            }
        }
        scheduled = loop
        handler.post(loop)
    }

    override fun stop() {
        scheduled?.let { handler.removeCallbacks(it) }
        scheduled = null
    }
}
