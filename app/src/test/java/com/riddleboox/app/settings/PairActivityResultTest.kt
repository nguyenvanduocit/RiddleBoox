package com.riddleboox.app.settings

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plain `Intent` extras, no `SettingsActivity` involved — see
 * [pairingPayloadFrom]'s doc for why `SettingsActivity` itself has no
 * dedicated result-handling test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairActivityResultTest {

    @Test
    fun `every extra present is read back as a payload`() {
        val data = Intent()
            .putExtra(PairActivity.EXTRA_BASE_URL, "https://api.openai.com")
            .putExtra(PairActivity.EXTRA_API_KEY, "sk-from-phone")
            .putExtra(PairActivity.EXTRA_MODEL, "gpt-5-vision")
        assertEquals(
            PairingPayload("https://api.openai.com", "sk-from-phone", "gpt-5-vision"),
            pairingPayloadFrom(data),
        )
    }

    @Test
    fun `a blank model extra is read back as an empty model, not rejected`() {
        val data = Intent()
            .putExtra(PairActivity.EXTRA_BASE_URL, "https://api.openai.com")
            .putExtra(PairActivity.EXTRA_API_KEY, "sk-from-phone")
            .putExtra(PairActivity.EXTRA_MODEL, "")
        assertEquals("", pairingPayloadFrom(data)?.model)
    }

    @Test
    fun `a missing base url extra is rejected`() {
        val data = Intent().putExtra(PairActivity.EXTRA_API_KEY, "sk-from-phone")
        assertNull(pairingPayloadFrom(data))
    }

    @Test
    fun `a missing api key extra is rejected`() {
        val data = Intent().putExtra(PairActivity.EXTRA_BASE_URL, "https://api.openai.com")
        assertNull(pairingPayloadFrom(data))
    }
}
