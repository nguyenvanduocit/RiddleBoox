package com.riddleboox.app.riddle

import java.io.File

/**
 * A crash-safety breadcrumb, not a resume mechanism.
 *
 * [set] is written the moment a page is actually handed to the diary
 * ([RiddleStateMachine.requestReply], only once a key is configured — there is
 * nothing to lose over an excuse that was never going to ask anything) and
 * [clear]ed the moment that turn reaches any conclusion the writer can see:
 * [RiddleStateMachine.finishTurn] (answered or excused), a pen stopped by hand
 * ([RiddleStateMachine.cutReplyShort]), or the page abandoned outright
 * ([RiddleStateMachine.clearPage]). Backgrounding the app mid-turn
 * ([RiddleStateMachine.stop]) does *not* clear it — that path cancels the
 * request and retries or excuses it once the app resumes, on the same
 * in-memory machine, and reaches one of the clearing points above either way.
 *
 * The only way this file survives to the next cold start is the process being
 * killed strictly between [set] and one of those three — which is exactly
 * what [consume] on the next launch needs to tell the writer about. It
 * deliberately carries no page bytes or conversation id: resuming the request
 * automatically risks the diary answering, and billing, the same page twice if
 * the model had in fact already replied before the process died. Detecting
 * and saying so plainly is the whole job; see the recovery-scope decision this
 * was scoped to.
 */
class PendingTurnMarker(filesDir: File) {
    private val file = File(filesDir, MARKER_FILE)

    fun set() {
        runCatching { file.writeText(System.currentTimeMillis().toString()) }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    /** Whether a turn was left in flight by the previous run — clears it either way, so this reads as "once." */
    fun consume(): Boolean {
        val left = file.exists()
        clear()
        return left
    }

    companion object {
        private const val MARKER_FILE = "pending-turn.marker"
    }
}
