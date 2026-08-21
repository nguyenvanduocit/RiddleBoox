package com.riddleboox.app.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRasterizerMathTest {

    private fun stroke(vararg points: Triple<Float, Float, Float>) =
        InkStroke(points.map { InkPoint(it.first, it.second, it.third) }.toMutableList())

    @Test
    fun radiusFollowsPressureAndClampsBothEnds() {
        assertEquals(1.0f, inkRadiusPx(0f), 1e-4f)
        assertEquals(2.7f, inkRadiusPx(1f), 1e-4f)
        assertEquals(1.85f, inkRadiusPx(0.5f), 1e-4f)
        // Raw BOOX pressures (0..4096) saturate instead of exploding.
        assertEquals(2.7f, inkRadiusPx(4096f), 1e-4f)
        assertEquals(1.0f, inkRadiusPx(-3f), 1e-4f)
    }

    @Test
    fun oneStrokeCropsToItsBoxPlusMargin() {
        // Full pressure -> radius 2.7. Box is x 97.3..202.7, y 97.3..152.7;
        // +20 margin gives 145.4x95.4, and that is the crop — no padding to
        // square it up.
        val plan = PageRasterizerMath.plan(
            listOf(stroke(Triple(100f, 100f, 1f), Triple(200f, 150f, 1f))),
        )!!
        assertEquals(77.3f, plan.cropLeft, 1e-3f)
        assertEquals(77.3f, plan.cropTop, 1e-3f)
        assertEquals(145.4f, plan.cropWidth, 1e-3f)
        assertEquals(95.4f, plan.cropHeight, 1e-3f)
        assertEquals(1f, plan.scale, 1e-6f)
        assertEquals(145, plan.outWidthPx)
        assertEquals(95, plan.outHeightPx)
    }

    @Test
    fun scatteredStrokesShareOneBoxClampedToThePageOrigin() {
        val plan = PageRasterizerMath.plan(
            listOf(
                stroke(Triple(50f, 50f, 0f), Triple(60f, 60f, 0f)),
                stroke(Triple(500f, 300f, 1f)),
                stroke(Triple(5f, 400f, 0f)),
            ),
        )!!
        // minX 4 - 20 would go negative, so the crop stops at the page edge;
        // minY 49 - 20 does not, so the top keeps its margin.
        assertEquals(0f, plan.cropLeft, 1e-3f)
        assertEquals(29f, plan.cropTop, 1e-3f)
        assertEquals(522.7f, plan.cropWidth, 1e-3f)
        assertEquals(392f, plan.cropHeight, 1e-3f)
        assertEquals(523, plan.outWidthPx)
        assertEquals(392, plan.outHeightPx)
    }

    @Test
    fun thinWideStrokeStaysThin() {
        // A one-line note: wide and barely any height. It is billed by the
        // pixels it has, so it keeps its shape instead of paying for a square
        // of blank canvas around it (see plan()'s doc for the measurement).
        val plan = PageRasterizerMath.plan(listOf(stroke(Triple(0f, 100f, 0f), Triple(600f, 105f, 0f))))!!
        assertEquals(621f, plan.cropWidth, 1e-3f)
        assertEquals(47f, plan.cropHeight, 1e-3f)
        assertEquals(621, plan.outWidthPx)
        assertEquals(47, plan.outHeightPx)
    }

    @Test
    fun singlePointStillHasArea() {
        val plan = PageRasterizerMath.plan(listOf(stroke(Triple(100f, 200f, 0.5f))))!!
        // radius 1.85 + 20 margin on each side of one dot.
        assertEquals(78.15f, plan.cropLeft, 1e-3f)
        assertEquals(178.15f, plan.cropTop, 1e-3f)
        assertEquals(43.7f, plan.cropWidth, 1e-3f)
        assertEquals(43.7f, plan.cropHeight, 1e-3f)
        assertTrue(plan.cropWidth > 0f && plan.cropHeight > 0f)
        assertEquals(44, plan.outWidthPx)
        assertEquals(44, plan.outHeightPx)
    }

    @Test
    fun singlePointWithNoMarginStaysPositive() {
        // One dot, nothing around it: the crop is the dot itself, 2*radius wide.
        val plan = PageRasterizerMath.plan(listOf(stroke(Triple(100f, 200f, 0f))), marginPx = 0)!!
        assertEquals(2f, plan.cropWidth, 1e-3f)
        assertEquals(2f, plan.cropHeight, 1e-3f)
        assertEquals(2, plan.outWidthPx)
        assertEquals(2, plan.outHeightPx)
    }

    @Test
    fun inkAtThePageOriginIsClampedNotShiftedOffPage() {
        // Half the dot sits off-page at x=0; the crop stops at the page edge.
        val plan = PageRasterizerMath.plan(listOf(stroke(Triple(0f, 0f, 0f))), marginPx = 0)!!
        assertEquals(0f, plan.cropLeft, 1e-3f)
        assertEquals(0f, plan.cropTop, 1e-3f)
        assertEquals(1f, plan.cropWidth, 1e-3f)
        assertEquals(1f, plan.cropHeight, 1e-3f)
        assertTrue(plan.outWidthPx >= 1)
    }

    @Test
    fun bigPageShrinksToTheLongEdgeCapKeepingItsShape() {
        // 2021x1021 of ink: both sides shrink by the same factor, so the
        // long edge lands on the 800px cap and the aspect ratio survives.
        val plan = PageRasterizerMath.plan(listOf(stroke(Triple(0f, 0f, 0f), Triple(2000f, 1000f, 0f))))!!
        assertEquals(2021f, plan.cropWidth, 1e-3f)
        assertEquals(1021f, plan.cropHeight, 1e-3f)
        assertEquals(800f / 2021f, plan.scale, 1e-6f)
        assertEquals(800, plan.outWidthPx)
        assertEquals(404, plan.outHeightPx)
    }

    @Test
    fun smallPageIsNeverUpscaled() {
        val plan = PageRasterizerMath.plan(listOf(stroke(Triple(10f, 10f, 0f), Triple(40f, 20f, 0f))))!!
        assertEquals(1f, plan.scale, 1e-6f)
        assertTrue(plan.outWidthPx < 800)
    }

    @Test
    fun blankPageHasNoPlan() {
        assertNull(PageRasterizerMath.plan(emptyList()))
        assertNull(PageRasterizerMath.plan(listOf(InkStroke())))
    }
}
