package com.riddleboox.app.riddle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExcusesTest {

    /**
     * The exact message Android threw on-device when the Wi-Fi changed bands
     * mid-turn — the failure this whole distinction exists for.
     */
    private val dnsFailure =
        """Unable to resolve host "api.openai.com": No address associated with hostname"""

    @Test
    fun `a network failure does not blame the handwriting`() {
        val excuse = excuseFor(dnsFailure)

        assertNotEquals(excuseFor("empty reply"), excuse)
        assertEquals(false, excuse.contains("blurred", ignoreCase = true))
    }

    @Test
    fun `every reachability failure gives the same account`() {
        val expected = excuseFor(dnsFailure)

        assertEquals(expected, excuseFor("Failed to connect to api.openai.com/1.2.3.4:443"))
        assertEquals(expected, excuseFor("Network is unreachable"))
        assertEquals(expected, excuseFor("Socket timeout after 30000ms"))
    }

    @Test
    fun `each other failure keeps its own line`() {
        val excuses = listOf(
            excuseFor("no key"),
            excuseFor("blank page"),
            excuseFor(dnsFailure),
            excuseFor("timed out"),
            excuseFor("empty reply"),
            excuseFor("Expected status code 200 but was 404"),
            excuseFor("Client request(...) invalid: 429 Too Many Requests. Text: ..."),
        )

        assertEquals(excuses.size, excuses.toSet().size)
    }

    /**
     * The exact shape ktor's `ClientRequestException.message` takes for a 429
     * response — see [isRateLimited].
     */
    private val rateLimitFailure = "Client request(POST https://openrouter.ai/api/v1/chat/completions) " +
        "invalid: 429 Too Many Requests. Text: \"...\""

    @Test
    fun `a provider throttle does not blame the handwriting or the network`() {
        assertEquals(true, isRateLimited(rateLimitFailure))
        assertEquals(false, isUnreachable(rateLimitFailure))

        val excuse = excuseFor(rateLimitFailure)
        assertEquals(false, excuse.contains("blurred", ignoreCase = true))
        assertNotEquals(excuseFor(dnsFailure), excuse)
    }

    @Test
    fun `rate limit is recognized by status code, phrase, or explicit words`() {
        assertEquals(true, isRateLimited("429"))
        assertEquals(true, isRateLimited("Too Many Requests"))
        assertEquals(true, isRateLimited("rate limit exceeded"))
        assertEquals(false, isRateLimited("Expected status code 200 but was 404"))
    }
}
