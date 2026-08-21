package com.riddleboox.app.riddle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DissolveHashTest {

    @Test
    fun `every point has dissolved by the final stage`() {
        val stages = 14
        for (x in intArrayOf(0, 1, 17, 500, -3)) {
            for (y in intArrayOf(0, 2, 42, 900, -8)) {
                assertTrue(
                    "($x,$y) should be gone by the last stage",
                    isDissolvedAt(x, y, stage = stages - 1, stages = stages),
                )
            }
        }
    }

    @Test
    fun `nothing has dissolved before stage 0 begins`() {
        // stage is only ever called with values in 0 until stages-1 by the state
        // machine, but a negative "not started" sentinel must erase nothing.
        assertFalse(isDissolvedAt(10, 10, stage = -1, stages = 14))
    }

    @Test
    fun `dissolved set only grows as stage increases`() {
        val stages = 14
        for (x in 0..40 step 3) {
            for (y in 0..40 step 5) {
                var wasDissolved = false
                for (stage in 0 until stages) {
                    val nowDissolved = isDissolvedAt(x, y, stage, stages)
                    if (wasDissolved) {
                        assertTrue("($x,$y) flipped back to inked at stage $stage", nowDissolved)
                    }
                    wasDissolved = nowDissolved
                }
            }
        }
    }

    @Test
    fun `negative coordinates hash without crashing and stay deterministic`() {
        val a = isDissolvedAt(-100, -200, stage = 5, stages = 14)
        val b = isDissolvedAt(-100, -200, stage = 5, stages = 14)
        assertTrue(a == b)
    }
}
