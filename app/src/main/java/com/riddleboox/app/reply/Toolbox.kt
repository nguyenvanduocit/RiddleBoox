package com.riddleboox.app.reply

import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.serialization.json.JsonObject

/**
 * What the diary can look up while it is answering.
 *
 * Declared here, beside the one thing that uses it, rather than beside the
 * implementation: [Conversation] needs to know that something can be consulted
 * mid-turn and nothing more, and a stub of two methods is all a test of the
 * tool loop should have to build. The real one is
 * [com.riddleboox.app.tools.DiaryTools], which reads the reader's own library.
 *
 * [call] must not throw. A tool that fails has an answer — "no such book", "the
 * shelf could not be opened" — and the turn continues on it; an exception here
 * would take the whole reply down with it.
 */
interface Toolbox {

    /** What the model is told it may consult. */
    val tools: List<ToolDescriptor>

    /** Runs [name] with [arguments] and returns what it found, as plain words. */
    suspend fun call(name: String, arguments: JsonObject): String

    /**
     * One short line to put on the page while [name] is running.
     *
     * The lookup is the longest silence in a turn — a whole extra request, and
     * on a slow panel several seconds of nothing. What makes that silence
     * bearable is not a mark that moves but knowing what is being done, so this
     * is given the arguments too: "Reading “Câu Chuyện Nghệ Thuật”…" is a wait
     * the writer understands, "Leafing through…" is one they only endure.
     */
    fun note(name: String, arguments: JsonObject): String
}
