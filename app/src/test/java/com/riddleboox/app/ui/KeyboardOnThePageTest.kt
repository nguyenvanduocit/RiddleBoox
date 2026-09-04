package com.riddleboox.app.ui

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A field on a page must never let the keyboard take the whole screen.
 *
 * In full-screen ("extract") mode the IME replaces the page with its own
 * editor and a DONE button: no running head, no "save", no status bar —
 * exactly what the BOOX Go line showed after typing an api key. Reproduced on
 * a stock Android 13 emulator with a landscape-shaped window. Pinned to 35
 * for the reason OfflineWatcherTest gives.
 */
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class KeyboardOnThePageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a field asks the keyboard to stay off the page`() {
        val field = EditText(context).apply { keepPageVisible() }

        assertNotEquals(0, field.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN)
        assertNotEquals(0, field.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI)
    }
}
