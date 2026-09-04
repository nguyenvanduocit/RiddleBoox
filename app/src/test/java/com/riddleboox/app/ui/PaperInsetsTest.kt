package com.riddleboox.app.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * How a page keeps its chrome on the paper when the window is edge-to-edge.
 *
 * [openPaperWindow] takes the page out from under the system's decor fitting,
 * so nothing but the page itself keeps its head clear of a status bar the
 * firmware insists on, or keeps the soft keyboard from covering the field
 * being typed into. Left to the framework, an uncovered focused field is
 * handled by panning the whole window up — which is how the running head,
 * "save" included, slid off the top of Settings on the BOOX Go line.
 *
 * Insets arrive here by hand: [ViewCompat.dispatchApplyWindowInsets] with a
 * built [WindowInsetsCompat], the way the framework would deliver them once
 * the view is attached. Pinned to 35 for the reason OfflineWatcherTest gives;
 * mdpi so `dp()` is the identity and the numbers below are px.
 */
@Config(sdk = [35], qualifiers = "mdpi")
@RunWith(RobolectricTestRunner::class)
class PaperInsetsTest {

    /** A bare Activity for the page helpers to hang off; registered by hand since it is not in the manifest. */
    class PaperTestActivity : Activity()

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, PaperTestActivity::class.java))
        activity = Robolectric.buildActivity(PaperTestActivity::class.java).setup().get()
    }

    private fun insets(systemBarTop: Int = 0, imeBottom: Int = 0): WindowInsetsCompat =
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, systemBarTop, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottom))
            .build()

    /** The line of captions inside a [runningHead]: back, title, action. */
    private fun View.headRow(): ViewGroup = (this as ViewGroup).getChildAt(0) as ViewGroup

    @Test
    fun `the head drops below a status bar the firmware refuses to hide`() {
        val head = activity.runningHead("settings", "save")

        ViewCompat.dispatchApplyWindowInsets(head, insets(systemBarTop = 50))

        assertEquals(50, head.headRow().paddingTop)
    }

    @Test
    fun `a hidden bar leaves the head exactly where it was`() {
        val head = activity.runningHead("settings", "save")
        val save = head.headRow().getChildAt(2) as TextView

        ViewCompat.dispatchApplyWindowInsets(head, insets(systemBarTop = 0))

        assertEquals(0, head.headRow().paddingTop)
        // The caption keeps its own inset: that padding is the stylus tap
        // target, and the row must add to it, never take it over.
        assertEquals(activity.dp(13), save.paddingTop)
    }

    @Test
    fun `the page gives the keyboard its own room instead of letting the window pan`() {
        val page = activity.paperPage(activity.runningHead("settings", "save"), activity.textBlock())

        ViewCompat.dispatchApplyWindowInsets(page, insets(imeBottom = 700))
        assertEquals(700, page.paddingBottom)

        ViewCompat.dispatchApplyWindowInsets(page, insets(imeBottom = 0))
        assertEquals(0, page.paddingBottom)
    }
}
