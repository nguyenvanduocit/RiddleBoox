package com.riddleboox.app.riddle

import com.riddleboox.app.agent.DEFAULT_AGENT_GREETINGS
import kotlin.random.Random

/**
 * What the diary writes on an empty page, before anyone has written to it.
 *
 * Its own voice, in the language the app speaks to its owner. Short, because
 * the line has to share the page with whatever gets written underneath it —
 * and because a diary that opens with a paragraph is a diary that talks more
 * than it listens.
 *
 * Several of them, and never twice in a row, because this is the first thing
 * seen every time the app opens: one fixed sentence would read as a splash
 * screen within a day, where a page that greets differently reads as something
 * awake on the other side of it.
 */
/** The chat agent's set, kept as the legacy public name for callers/tests. */
val GREETINGS: List<String> = DEFAULT_AGENT_GREETINGS

/**
 * One greeting, never the same as [previous].
 *
 * Reset is a button, and a button gets pressed twice — drawing the same line
 * again would look like the page had failed to refresh rather than like the
 * diary had answered again.
 */
fun greeting(previous: String? = null, random: Random = Random.Default): String {
    return greeting(GREETINGS, previous, random)
}

/**
 * Picks from one agent's own opening lines, never repeating [previous] when
 * that set has another choice.
 */
fun greeting(
    greetings: List<String>,
    previous: String? = null,
    random: Random = Random.Default,
): String {
    require(greetings.isNotEmpty()) { "An agent must define at least one greeting." }
    val choices = greetings.filter { it != previous }.ifEmpty { greetings }
    return choices[random.nextInt(choices.size)]
}
