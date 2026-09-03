package com.riddleboox.app.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.riddleboox.app.handwriting.FakeRaster
import com.riddleboox.app.handwriting.HandwritingPlanner
import com.riddleboox.app.ink.EinkRefresher
import com.riddleboox.app.riddle.Ticker
import com.riddleboox.app.ui.RegionView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shell around [decideOnboarding] and [onboardingCaption], driven on a
 * fake clock: what it publishes, and that a checkpoint it parked at stays
 * parked. Robolectric only for [RegionView]'s bitmap layer and the Onyx calls
 * inside [EinkRefresher], which fail closed off-device (`runCatching`).
 * Pinned to 35 for the reason OfflineWatcherTest gives.
 */
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class OnboardingControllerTest {

    private class Harness(checkpointAfter: Int? = null) {
        val ticker = FakeTicker()
        val captions = mutableListOf<String>()
        var checkpoints = 0
        var done = 0
        val regionView = RegionView(ApplicationProvider.getApplicationContext<Context>()).apply {
            layout(0, 0, 400, 800)
        }
        val controller = OnboardingController(
            // A dozen words apiece: FakeRaster turns each word into one short
            // stroke, and REPLY_POINTS_PER_TICK reveals a few of those per
            // tick, so writing a page spans several ticks the way it does on
            // the panel — a page that finishes inside its first tick would
            // never publish its "writing" caption at all.
            segments = listOf(
                "the first page has a dozen small words written out on it now",
                "the second page also has a dozen small words written on it",
                "the third page has a dozen small words written on it as well",
            ),
            regionView = regionView,
            refresher = EinkRefresher(),
            ticker = ticker,
            handwritingPlanner = HandwritingPlanner(FakeRaster()),
            replyFontSizePx = HandwritingPlanner.DEFAULT_FONT_SIZE_PX,
            pageWidthPx = { 400 },
            onDone = { done++ },
            permissionCheckpointAfter = checkpointAfter,
            onPermissionCheckpoint = { checkpoints++ },
            onCaptionChanged = { captions += it },
        )

        /** One hold plus the ticks a dozen-word page takes to write, with slack. */
        fun advanceThroughOnePage() = repeat(320) { ticker.advance(16) }
    }

    @Test
    fun `publishes the caption only when it changes`() {
        val h = Harness()
        h.controller.start()

        h.advanceThroughOnePage()

        assertEquals("page 1 of 3", h.captions.first())
        assertTrue(h.captions.toString(), h.captions.contains("page 1 of 3 · next in 4"))
        assertTrue(h.captions.toString(), h.captions.contains("page 1 of 3 · next in 1"))
        assertTrue(h.captions.toString(), h.captions.contains("page 2 of 3"))
        h.captions.zipWithNext().forEach { (a, b) -> assertTrue("published twice in a row: $a", a != b) }
        // Five distinct strings per page (writing + a four-second countdown) —
        // not one per 16 ms tick.
        assertTrue("${h.captions.size} publishes for about one page", h.captions.size <= 12)
    }

    @Test
    fun `parks at the checkpoint and stays parked through a resume`() {
        val h = Harness(checkpointAfter = 1)
        h.controller.start()
        h.advanceThroughOnePage()
        h.advanceThroughOnePage()
        assertEquals(1, h.checkpoints)
        val publishedWhenParked = h.captions.size

        // MainActivity.onResume calls start() again without knowing the intro
        // is waiting on the overlay; nothing may move until the writer answers.
        h.controller.start()
        h.advanceThroughOnePage()

        assertEquals(1, h.checkpoints)
        assertEquals(0, h.done)
        assertEquals(publishedWhenParked, h.captions.size)
        assertFalse(h.captions.toString(), h.captions.contains("page 3 of 3"))

        h.controller.proceedFromCheckpoint()
        h.advanceThroughOnePage()

        assertTrue(h.captions.toString(), h.captions.contains("page 3 of 3"))
        assertTrue(h.captions.toString(), h.captions.contains("page 3 of 3 · your turn in 1"))
        assertEquals(1, h.done)
        assertEquals("", h.captions.last())
    }

    @Test
    fun `the hold before the checkpoint promises a question, not a page`() {
        val h = Harness(checkpointAfter = 1)
        h.controller.start()
        h.advanceThroughOnePage()
        h.advanceThroughOnePage()

        assertTrue(h.captions.toString(), h.captions.contains("page 2 of 3 · a question in 4"))
        assertFalse(h.captions.toString(), h.captions.any { it.startsWith("page 2 of 3 · next in") })
    }
}

private class FakeTicker : Ticker {
    private var tickFn: (() -> Unit)? = null
    private var elapsedMs = 0L

    override fun nowMs(): Long = elapsedMs
    override fun start(everyMs: Long, tick: () -> Unit) { tickFn = tick }
    override fun stop() { tickFn = null }

    fun advance(byMs: Long) {
        elapsedMs += byMs
        tickFn?.invoke()
    }
}
