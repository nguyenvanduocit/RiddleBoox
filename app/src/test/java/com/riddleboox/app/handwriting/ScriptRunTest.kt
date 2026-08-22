package com.riddleboox.app.handwriting

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptRunTest {

    @Test
    fun `plain text with no marker is one run`() {
        assertEquals(listOf(ScriptRun.Plain("abc")), parseScriptRuns("abc"))
    }

    @Test
    fun `a braced subscript splits base and content apart`() {
        assertEquals(
            listOf(ScriptRun.Plain("lim"), ScriptRun.Sub("x→0")),
            parseScriptRuns("lim_{x→0}"),
        )
    }

    @Test
    fun `a bare one-character subscript needs no braces`() {
        assertEquals(listOf(ScriptRun.Plain("x"), ScriptRun.Sub("i")), parseScriptRuns("x_i"))
    }

    @Test
    fun `superscript then trailing plain text after the closing brace`() {
        assertEquals(
            listOf(ScriptRun.Plain("e"), ScriptRun.Sup("iπ"), ScriptRun.Plain("+1=0")),
            parseScriptRuns("e^{iπ}+1=0"),
        )
    }

    @Test
    fun `subscript and superscript on the same empty base, one after the other`() {
        // \sum_{i=1}^{n} with nothing written before the subscript.
        assertEquals(
            listOf(ScriptRun.Sub("i=1"), ScriptRun.Sup("n")),
            parseScriptRuns("_{i=1}^{n}"),
        )
    }

    @Test
    fun `two subscripts either side of a plain run`() {
        // H_2SO_4 — two separate subscripts, not one run spanning "SO".
        assertEquals(
            listOf(ScriptRun.Plain("H"), ScriptRun.Sub("2"), ScriptRun.Plain("SO"), ScriptRun.Sub("4")),
            parseScriptRuns("H_2SO_4"),
        )
    }

    @Test
    fun `an unmatched opening brace is treated as plain text, not a run`() {
        assertEquals(listOf(ScriptRun.Plain("x_{unclosed")), parseScriptRuns("x_{unclosed"))
    }

    @Test
    fun `a marker with nothing after it is plain text`() {
        assertEquals(listOf(ScriptRun.Plain("x_")), parseScriptRuns("x_"))
        assertEquals(listOf(ScriptRun.Plain("x^")), parseScriptRuns("x^"))
    }

    @Test
    fun `empty braces are plain text, not an empty run`() {
        assertEquals(listOf(ScriptRun.Plain("x_{}y")), parseScriptRuns("x_{}y"))
    }

    @Test
    fun `an empty string yields no runs`() {
        assertEquals(emptyList<ScriptRun>(), parseScriptRuns(""))
    }
}
