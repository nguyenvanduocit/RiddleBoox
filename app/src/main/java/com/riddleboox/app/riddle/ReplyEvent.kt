package com.riddleboox.app.riddle

/**
 * What a request in flight has produced, waiting for the next tick to read it.
 *
 * Its own file rather than a private class inside [RiddleStateMachine] because
 * both sides of the seam need it: the shell fills a queue with these from the
 * network's thread, and the pure deciders in [Decide.kt] take one as the news
 * from outside that a tick has to answer to.
 */
sealed class ReplyEvent {

    /** Reply text as it streams, in the order the model produced it. */
    data class Delta(val text: String) : ReplyEvent()

    /**
     * The whole reply, once the stream closes, and the page's own words as the
     * diary read them back — the pair a conversation is remembered as.
     */
    data class Complete(val text: String, val transcript: String) : ReplyEvent()

    data class Error(val message: String) : ReplyEvent()

    /**
     * The diary stopped to look something up in the writer's library,
     * mid-request — the pause is longer on purpose and the page says so.
     *
     * [tool] is for the log. [note] is what goes on the page, already worded
     * for the writer by [com.riddleboox.app.reply.Toolbox.note] — "Reading
     * “Câu Chuyện Nghệ Thuật”…" rather than "read_book". It has a default
     * because a lookup with nothing to say about itself is still a lookup, and
     * the caption falls back to a general one.
     */
    data class Lookup(val tool: String, val note: String = "") : ReplyEvent()
}
