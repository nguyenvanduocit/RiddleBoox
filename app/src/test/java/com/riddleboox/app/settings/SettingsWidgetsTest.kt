package com.riddleboox.app.settings

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The api key field and the two "type it in…" dialogs are all [valueField]:
 * this is the one place that keeps the keyboard off the Settings page. Pinned
 * to 35 for the reason OfflineWatcherTest gives.
 */
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class SettingsWidgetsTest {

    class WidgetsTestActivity : Activity()

    private fun activity(): Activity {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, WidgetsTestActivity::class.java))
        return Robolectric.buildActivity(WidgetsTestActivity::class.java).setup().get()
    }

    @Test
    fun `a value field keeps the keyboard off the page`() {
        val field = activity().valueField("", InputType.TYPE_CLASS_TEXT)

        assertNotEquals(0, field.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN)
        assertNotEquals(0, field.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI)
    }
}
