package com.riddleboox.app.handwriting

/**
 * One piece of a word's ink: plain text at the word's own size, or a
 * subscript/superscript chunk to be drawn smaller and offset from the
 * baseline — see [ScriptAwareTextRaster].
 */
internal sealed class ScriptRun {
    abstract val text: String

    data class Plain(override val text: String) : ScriptRun()
    data class Sub(override val text: String) : ScriptRun()
    data class Sup(override val text: String) : ScriptRun()
}

/**
 * Splits [token] into [ScriptRun]s wherever a `_`/`^` marker of LaTeX's own
 * subscript/superscript syntax survives to reach a raster — braced
 * (`_{content}`) or bare (`_x`, one character).
 *
 * Runs, not a single base+script pair: a chemistry formula carries several
 * (`SO_4^{2-}` is base "SO", sub "4", sup "2-"; `H_2SO_4` has *two*
 * subscripts with a plain "SO" between them), and a summation's bounds sit
 * on either side of the same symbol (`\sum_{i=1}^{n}` — sub then sup on an
 * empty base). Scanning left to right and emitting whatever shape each
 * marker turns out to have covers all of these the same way, one pass.
 *
 * A marker with nothing after it, or an unmatched `{` with no closing `}`,
 * is not a run — it is exactly what it looks like, a bare underscore or
 * caret, and is folded into the surrounding plain text instead.
 */
internal fun parseScriptRuns(token: String): List<ScriptRun> {
    val runs = mutableListOf<ScriptRun>()
    val plain = StringBuilder()
    var i = 0
    while (i < token.length) {
        val marker = token[i]
        val script = if (marker == '_' || marker == '^') scriptContentAt(token, i + 1) else null
        if (script == null) {
            plain.append(marker)
            i++
            continue
        }
        if (plain.isNotEmpty()) {
            runs += ScriptRun.Plain(plain.toString())
            plain.clear()
        }
        val (content, nextIndex) = script
        runs += if (marker == '_') ScriptRun.Sub(content) else ScriptRun.Sup(content)
        i = nextIndex
    }
    if (plain.isNotEmpty()) runs += ScriptRun.Plain(plain.toString())
    return runs
}

/**
 * The scripted content starting right after a `_`/`^` at [start] and the
 * index just past it, or null when there is nothing there to script.
 */
private fun scriptContentAt(token: String, start: Int): Pair<String, Int>? {
    if (start >= token.length) return null
    if (token[start] != '{') return token[start].toString() to start + 1
    val close = token.indexOf('}', start + 1)
    if (close == -1) return null
    val inner = token.substring(start + 1, close)
    return if (inner.isEmpty()) null else inner to close + 1
}
