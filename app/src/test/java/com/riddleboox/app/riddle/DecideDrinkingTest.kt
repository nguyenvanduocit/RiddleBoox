package com.riddleboox.app.riddle

import com.riddleboox.app.handwriting.WritePlan
import com.riddleboox.app.handwriting.WriteStroke
import com.riddleboox.app.handwriting.WritePoint
import com.riddleboox.app.ink.InkPoint
import com.riddleboox.app.ink.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ink-drinking dissolve: the writer's page and the answer it was written
 * under coming off together, one stage at a time.
 *
 * None of this could be checked before [decideDrinking] existed — it was
 * fourteen stages of animation expressed as calls into a `View`, so the only
 * way to know a stage had been skipped was to watch a tablet.
 */
class DecideDrinkingTest {

    private val area = PageRect(100, 100, 400, 400)
    private val page = listOf(InkStroke(mutableListOf(InkPoint(120f, 130f, 0.5f))))
    private val standing = WritePlan(listOf(WriteStroke(listOf(WritePoint(150f, 300f)))))

    private fun drinking(stage: Int, nextStageAtMs: Long) = RiddleState.Drinking(
        committedStrokes = page,
        standingReply = standing,
        dirtyRect = area,
        stage = stage,
        nextStageAtMs = nextStageAtMs,
    )

    @Test
    fun `a stage that is not due yet costs the page nothing`() {
        assertNull(decideDrinking(drinking(stage = 3, nextStageAtMs = 1_000), now = 999))
    }

    @Test
    fun `both hands of ink dissolve on the same stage`() {
        val decision = decideDrinking(drinking(stage = 3, nextStageAtMs = 1_000), now = 1_000)!!
        val rendered = decision.effects.filterIsInstance<Effect.Render>().single().page

        assertEquals(4, rendered.dissolve!!.stage)
        assertEquals(page, rendered.userStrokes)
        assertEquals(standing.strokes, rendered.replyStrokes)
    }

    @Test
    fun `a stage is held for its own interval before the next`() {
        val decision = decideDrinking(drinking(stage = 3, nextStageAtMs = 1_000), now = 1_007)!!
        val next = decision.state as RiddleState.Drinking

        assertEquals(4, next.stage)
        // Measured from now, not from the deadline that was missed: a tick that
        // ran late must not make the stage after it shorter to compensate.
        assertEquals(1_007 + DRINK_STAGE_MS, next.nextStageAtMs)
    }

    @Test
    fun `a dissolving page redraws only the area the ink was in`() {
        val decision = decideDrinking(drinking(stage = 0, nextStageAtMs = 0), now = 0)!!
        val refresh = decision.effects.filterIsInstance<Effect.Refresh>().single()

        assertEquals(area, refresh.area)
        assertEquals(RefreshMode.Fast, refresh.mode)
    }

    @Test
    fun `the last stage leaves a blank page and starts the diary thinking`() {
        val decision = decideDrinking(drinking(stage = DRINK_STAGES - 1, nextStageAtMs = 500), now = 500)!!
        val thinking = decision.state as RiddleState.Thinking

        assertEquals(500, thinking.startedAtMs)
        assertEquals(PageRenderState.EMPTY, decision.effects.filterIsInstance<Effect.Render>().single().page)
    }

    /**
     * A whole region changing at once is what GU is for: DU would leave it
     * visibly dirty, and GC would blink the entire panel for it.
     */
    @Test
    fun `an emptied page is settled properly rather than left dirty or flashed`() {
        val decision = decideDrinking(drinking(stage = DRINK_STAGES - 1, nextStageAtMs = 500), now = 500)!!
        val refresh = decision.effects.filterIsInstance<Effect.Refresh>().single()

        assertEquals(RefreshMode.Quality, refresh.mode)
        assertEquals(area, refresh.area)
        assertTrue(
            "no full-screen flash between drinking and thinking",
            decision.effects.none { it == Effect.FullRefresh },
        )
    }

    @Test
    fun `the ink is shown before the panel is asked to redraw it`() {
        val decision = decideDrinking(drinking(stage = 2, nextStageAtMs = 0), now = 0)!!
        val kinds = decision.effects.map { it::class }

        assertEquals(
            listOf(Effect.Render::class, Effect.Refresh::class),
            kinds,
        )
    }
}
