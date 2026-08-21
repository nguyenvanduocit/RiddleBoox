package com.riddleboox.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

private val DEFAULTS = ReplySettings(
    baseUrl = "https://openrouter.ai/api",
    apiKey = "sk-factory",
    model = "openai/gpt-5.6-luna",
)

class ReplySettingsTest {
    @Test
    fun `typed values survive untouched`() {
        val typed = ReplySettings("https://llm.local", "sk-mine", "qwen2-vl")
        assertEquals(typed, typed.sanitized(DEFAULTS))
    }

    @Test
    fun `stray keyboard spaces are trimmed off every field`() {
        val typed = ReplySettings("  https://llm.local ", " sk-mine\n", "\tqwen2-vl ")
        assertEquals(
            ReplySettings("https://llm.local", "sk-mine", "qwen2-vl"),
            typed.sanitized(DEFAULTS),
        )
    }

    @Test
    fun `an emptied base url falls back to the default`() {
        val typed = ReplySettings("   ", "sk-mine", "qwen2-vl")
        assertEquals(DEFAULTS.baseUrl, typed.sanitized(DEFAULTS).baseUrl)
    }

    @Test
    fun `an emptied model falls back to the default`() {
        val typed = ReplySettings("https://llm.local", "sk-mine", "")
        assertEquals(DEFAULTS.model, typed.sanitized(DEFAULTS).model)
    }

    @Test
    fun `an emptied api key stays empty because that means run offline`() {
        val typed = ReplySettings("https://llm.local", "  ", "qwen2-vl")
        assertEquals("", typed.sanitized(DEFAULTS).apiKey)
    }

    @Test
    fun `a blank default is no rescue for a blank field`() {
        val blankDefaults = ReplySettings("", "", "")
        assertEquals(blankDefaults, ReplySettings(" ", " ", " ").sanitized(blankDefaults))
    }
}
