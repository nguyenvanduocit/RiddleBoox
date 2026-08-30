package com.riddleboox.app.riddle

/**
 * What the diary writes when no real answer arrived.
 *
 * Not a port of oracle.rs's excuse text (out of scope, see [RiddleStateMachine]'s
 * class doc) — short persona-consistent lines for the failure paths main.rs also
 * handles without an answer (main.rs:432-437,501,521,533).
 *
 * Each failure gets its own line, because these lines are the only account of
 * the failure the writer ever sees. Blurred ink means the page itself could not
 * be read; a diary that says that when the request never left the device sends
 * the writer off rewriting perfectly legible handwriting while the actual fault
 * is the Wi-Fi.
 */
/**
 * Whether [reason] means the request never got off the device — DNS, routing or
 * socket, as Android words them: "Unable to resolve host ...", "Failed to
 * connect to ...", "Network is unreachable", "... timeout after 30000ms".
 *
 * Worth telling apart from every other failure because it is the one that fixes
 * itself: this Note Air 2 loses DNS for tens of seconds each time its Wi-Fi
 * changes bands, so a turn that fails this way deserves the patience budget
 * rather than three quick attempts.
 */
fun isUnreachable(reason: String): Boolean =
    reason.contains("resolve host", ignoreCase = true) ||
        reason.contains("failed to connect", ignoreCase = true) ||
        reason.contains("unreachable", ignoreCase = true) ||
        reason.contains("timeout", ignoreCase = true)

/**
 * Whether [reason] means the model provider itself is throttling the
 * request — ktor's `ClientRequestException.message` for a 429 response reads
 * `"Client request(...) invalid: 429 Too Many Requests. Text: ..."`, so the
 * status code and its reason phrase are both matched.
 *
 * Told apart from [isUnreachable] because a flat retry interval only
 * prolongs a live rate limit — see [rateLimitRetryDelayMs].
 */
fun isRateLimited(reason: String): Boolean =
    reason.contains("429") ||
        reason.contains("too many requests", ignoreCase = true) ||
        reason.contains("rate limit", ignoreCase = true)

fun excuseFor(reason: String): String = when {
    reason.contains("blank page", ignoreCase = true) ->
        "There is nothing written here to answer."

    isRateLimited(reason) ->
        "Too many hands have reached for the ink tonight — wait a moment and try again."

    isUnreachable(reason) ->
        "Your words are on the page, but tonight they reach no further than this room."

    reason.contains("timed out", ignoreCase = true) ->
        "The diary listens, but tonight the silence stretches too long."

    reason.contains("empty reply", ignoreCase = true) ->
        "The ink blurred; the diary could not make out what was written."

    else -> "Something between the page and the memory gave way. Try once more."
}
