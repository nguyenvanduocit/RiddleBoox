package com.riddleboox.app.settings

import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPageTest {

    @Test
    fun `every provider label appears on the form`() {
        val html = pairingPage("tok123", PROVIDERS)
        for (provider in PROVIDERS) assertTrue(html.contains(provider.label))
    }

    @Test
    fun `the token rides along as a hidden field`() {
        val html = pairingPage("tok123", PROVIDERS)
        assertTrue(html.contains("tok123"))
        assertTrue(html.contains("type=\"hidden\""))
    }

    @Test
    fun `the form posts to slash pair`() {
        assertTrue(pairingPage("tok123", PROVIDERS).contains("action=\"/pair\""))
    }

    @Test
    fun `an other option is always offered`() {
        assertTrue(pairingPage("tok123", PROVIDERS).contains("value=\"other\""))
    }

    @Test
    fun `a token is html-escaped so it cannot break out of the attribute`() {
        val html = pairingPage("\"><script>", PROVIDERS)
        assertTrue(!html.contains("\"><script>"))
    }

    @Test
    fun `the confirmation page says it was sent`() {
        assertTrue(confirmationPage().contains("sent"))
    }

    @Test
    fun `the rejection page carries the reason, escaped`() {
        assertTrue(rejectedPage("paste an API key").contains("paste an API key"))
    }
}
