package com.riddleboox.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProvidersTest {

    @Test
    fun `the build default is recognised as OpenRouter`() {
        assertEquals("OpenRouter", providerFor("https://openrouter.ai/api")?.label)
    }

    @Test
    fun `openai's root is recognised as OpenAI`() {
        assertEquals("OpenAI", providerFor("https://api.openai.com")?.label)
    }

    @Test
    fun `whitespace from the on-screen keyboard does not hide a provider`() {
        assertEquals("OpenRouter", providerFor(" https://openrouter.ai/api ")?.label)
    }

    /**
     * Exact match only: a hand-typed variant ("…/api/") shows as "other" with
     * its url in plain view, rather than being silently renamed to a provider
     * whose canonical url would then overwrite it on save.
     */
    @Test
    fun `anything else belongs to no provider`() {
        assertNull(providerFor("https://openrouter.ai/api/"))
        assertNull(providerFor("https://my-proxy.local:8080"))
        assertNull(providerFor(""))
    }
}
