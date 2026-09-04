package com.riddleboox.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTest {

    @Test
    fun `a known provider label resolves to its canonical base url`() {
        val form = mapOf("provider" to "OpenRouter", "api_key" to "sk-mine", "model" to "")
        val result = parsePairing(form)
        assertTrue(result is PairingParse.Ok)
        assertEquals("https://openrouter.ai/api", (result as PairingParse.Ok).payload.baseUrl)
    }

    @Test
    fun `other with a base url is accepted`() {
        val form = mapOf("provider" to "other", "base_url" to "https://llm.local", "api_key" to "sk-mine")
        val result = parsePairing(form)
        assertTrue(result is PairingParse.Ok)
        assertEquals("https://llm.local", (result as PairingParse.Ok).payload.baseUrl)
    }

    @Test
    fun `other with no base url is rejected`() {
        val form = mapOf("provider" to "other", "api_key" to "sk-mine")
        assertTrue(parsePairing(form) is PairingParse.Rejected)
    }

    @Test
    fun `an unknown provider label is rejected`() {
        val form = mapOf("provider" to "made-up", "api_key" to "sk-mine")
        assertTrue(parsePairing(form) is PairingParse.Rejected)
    }

    @Test
    fun `a missing api key is rejected`() {
        val form = mapOf("provider" to "OpenAI", "api_key" to "  ")
        assertTrue(parsePairing(form) is PairingParse.Rejected)
    }

    @Test
    fun `stray keyboard spaces are trimmed`() {
        val form = mapOf("provider" to " OpenAI ", "api_key" to " sk-mine ", "model" to " gpt-5 ")
        val result = parsePairing(form) as PairingParse.Ok
        assertEquals("sk-mine", result.payload.apiKey)
        assertEquals("gpt-5", result.payload.model)
    }

    @Test
    fun `a blank model is accepted and stays blank`() {
        val form = mapOf("provider" to "OpenAI", "api_key" to "sk-mine")
        val result = parsePairing(form) as PairingParse.Ok
        assertEquals("", result.payload.model)
    }
}
