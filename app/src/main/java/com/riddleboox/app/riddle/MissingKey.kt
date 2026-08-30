package com.riddleboox.app.riddle

import kotlin.random.Random

/**
 * What the diary writes back on a page it can read but cannot answer, because
 * no API key has been saved yet.
 *
 * The page still takes ink and still gets handed over — a diary that refuses
 * the pen until it is configured is a diary that teaches the writer nothing
 * about what is missing. What comes back instead of a reply is this line, in
 * the diary's own hand, in the place the reply would have stood.
 *
 * Every line names Settings, because the line *is* the instruction: this is
 * written on a full-screen page with no dialog and no error banner anywhere
 * near it, so a line that only says the diary is mute leaves the writer with
 * nowhere to go.
 *
 * Several of them, and never twice in a row, for the same reason [GREETINGS]
 * has several: a writer without a key sees this on every page they write, and
 * one fixed sentence repeated down the evening reads as a stuck screen rather
 * than as something answering.
 */
val MISSING_KEY_LINES: List<String> = listOf(
    "Your words landed on the page, but I have no voice yet — open Settings and give me an API key.",
    "I can read this page and answer none of it: Settings is still waiting for an API key.",
    "The ink is here; the voice is not. Add an API key in Settings and I will answer properly.",
    "Nothing carries beyond this room tonight — no API key has been written into Settings.",
    "I will keep the page. The answering needs a key, and Settings is where it goes.",
    "Write as much as you like; I stay quiet until an API key is saved in Settings.",
)

/**
 * One line, never the same as [previous].
 *
 * [previous] is the last line this diary wrote, threaded through by the state
 * machine, so consecutive unanswerable pages read as two remarks rather than
 * one sentence printed twice.
 */
fun missingKeyLine(previous: String? = null, random: Random = Random.Default): String {
    val choices = MISSING_KEY_LINES.filter { it != previous }.ifEmpty { MISSING_KEY_LINES }
    return choices[random.nextInt(choices.size)]
}
