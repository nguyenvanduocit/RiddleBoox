package com.riddleboox.app.ink

import com.riddleboox.app.settings.PenStyle
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

    @Test
    fun ballpointCurveMatchesTheDiarysOriginalFixedInk() {
        assertEquals(1.0f, baseRadiusPx(0f, PenStyle.Ballpoint), 1e-4f)
        assertEquals(2.7f, baseRadiusPx(1f, PenStyle.Ballpoint), 1e-4f)
        assertEquals(1.85f, baseRadiusPx(0.5f, PenStyle.Ballpoint), 1e-4f)
    }

    @Test
    fun everyStyleClampsRawBooxPressureToItsOwnRange() {
        for (style in PenStyle.entries) {
            assertEquals(style.maxRadiusPx, baseRadiusPx(4096f, style), 1e-4f)
            assertEquals(style.minRadiusPx, baseRadiusPx(-3f, style), 1e-4f)
        }
    }

    @Test
    fun everyStyleIsClearlySeparatedFromBallpointAtTheSamePressure() {
        // A writer switching presets at the same hand pressure has to see a
        // real difference, not a family resemblance — each style's radius at
        // a representative mid pressure stays at least 40% away from
        // Ballpoint's, in whichever direction that style leans.
        val ballpointMid = baseRadiusPx(0.6f, PenStyle.Ballpoint)
        assertTrue(baseRadiusPx(0.6f, PenStyle.Pencil) < ballpointMid * 0.6f)
        assertTrue(baseRadiusPx(0.6f, PenStyle.FountainPen) > ballpointMid * 1.4f)
        assertTrue(baseRadiusPx(0.6f, PenStyle.Brush) > ballpointMid * 1.6f)
    }

    @Test
    fun pencilStaysThinnerThanBallpointAcrossTheWholePressureRange() {
        for (pressure in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertTrue(baseRadiusPx(pressure, PenStyle.Pencil) < baseRadiusPx(pressure, PenStyle.Ballpoint))
        }
    }

    @Test
    fun pencilIsNoticeablyFainterThanTheOtherThreeStyles() {
        // Opacity, not just radius, carries the "pencil" read — a thin but
        // fully opaque line still looks like a fine ballpoint.
        val others = PenStyle.entries - PenStyle.Pencil
        for (style in others) assertTrue(PenStyle.Pencil.alpha < style.alpha - 80)
    }

    @Test
    fun textureJitterIsDeterministicPerPointIndex() {
        // Same index, same jitter every time: a page rasterized twice from the
        // same strokes must produce identical pixels for the AI and the screen.
        assertEquals(textureJitter(7), textureJitter(7), 0f)
        // Different indices are not all forced to the same multiplier.
        val samples = (0 until 20).map { textureJitter(it) }
        assertTrue(samples.distinct().size > 1)
        // Jitter roughens the line hard enough to read as grain, without
        // erasing or doubling it.
        for (s in samples) assertTrue(s in 0.5f..1.5f)
        assertTrue(samples.max() - samples.min() > 0.5f)
    }

    @Test
    fun taperMultiplierThinsOnlyNearBothEndsOfAStroke() {
        val pointCount = 20
        // The very first and last points taper hardest — down to a sliver,
        // not just "a bit thinner", or a brush tip reads as a blunt pen.
        assertTrue(taperMultiplier(0, pointCount) < 0.2f)
        assertTrue(taperMultiplier(0, pointCount) < taperMultiplier(3, pointCount))
        assertTrue(taperMultiplier(pointCount - 1, pointCount) < taperMultiplier(pointCount - 4, pointCount))
        // The middle of a long stroke is untouched.
        assertEquals(1f, taperMultiplier(pointCount / 2, pointCount), 1e-4f)
    }

    @Test
    fun taperMultiplierNeverThinsAOnePointStroke() {
        assertEquals(1f, taperMultiplier(0, 1), 1e-4f)
    }

    @Test
    fun styleAndWidthScaleComposeOnTopOfThePressureCurve() {
        // Brush at full pressure, "thick" width (1.4x): matches the raw curve
        // times the scale, texture/taper aside (full pressure holds taper at
        // its un-thinned end already only away from index 0/last).
        val r = inkRadiusPx(1f, pointIndex = 10, pointCount = 20, style = PenStyle.Brush, widthScale = 1.4f)
        assertEquals(PenStyle.Brush.maxRadiusPx * 1.4f, r, 1e-4f)
    }

    @Test
    fun defaultInkRadiusPxArgumentsReproduceTheOriginalOneArgBehaviour() {
        assertEquals(1.0f, inkRadiusPx(0f), 1e-4f)
        assertEquals(2.7f, inkRadiusPx(1f), 1e-4f)
        assertEquals(1.85f, inkRadiusPx(0.5f), 1e-4f)
        assertEquals(2.7f, inkRadiusPx(4096f), 1e-4f)
        assertEquals(1.0f, inkRadiusPx(-3f), 1e-4f)
    }
}
