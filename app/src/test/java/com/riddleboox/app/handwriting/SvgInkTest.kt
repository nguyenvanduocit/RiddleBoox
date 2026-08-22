package com.riddleboox.app.handwriting

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgInkTest {

    private fun bounds(figure: SvgFigure): List<Float> {
        val points = figure.strokes.flatMap { it.points }
        return listOf(
            points.minOf { it.x }, points.minOf { it.y },
            points.maxOf { it.x }, points.maxOf { it.y },
        )
    }

    // ---- elements ----

    @Test
    fun `a line becomes one two-point stroke, normalized to the origin`() {
        val figure = svgFigure("""<svg viewBox="0 0 100 100"><line x1="10" y1="20" x2="90" y2="60"/></svg>""")
        assertEquals(1, figure.strokes.size)
        assertEquals(listOf(WritePoint(0f, 0f), WritePoint(80f, 40f)), figure.strokes.single().points)
        assertEquals(80f, figure.width, 1e-3f)
        assertEquals(40f, figure.height, 1e-3f)
    }

    @Test
    fun `a rect closes back onto its own first corner`() {
        val figure = svgFigure("""<svg><rect x="5" y="5" width="20" height="10"/></svg>""")
        val points = figure.strokes.single().points
        assertEquals(5, points.size)
        assertEquals(points.first(), points.last())
        assertEquals(20f, figure.width, 1e-3f)
        assertEquals(10f, figure.height, 1e-3f)
    }

    @Test
    fun `a circle is flattened closed, spanning its own diameter`() {
        val figure = svgFigure("""<svg><circle cx="50" cy="50" r="30"/></svg>""")
        val points = figure.strokes.single().points
        assertEquals(points.first().x, points.last().x, 1e-3f)
        assertEquals(points.first().y, points.last().y, 1e-3f)
        assertEquals(listOf(0f, 0f, 60f, 60f), bounds(figure))
    }

    @Test
    fun `an ellipse spans its two radii`() {
        val figure = svgFigure("""<svg><ellipse cx="0" cy="0" rx="40" ry="10"/></svg>""")
        assertEquals(80f, figure.width, 1e-3f)
        assertEquals(20f, figure.height, 1e-3f)
    }

    @Test
    fun `a polygon closes and a polyline does not`() {
        val polygon = svgFigure("""<svg><polygon points="0,0 10,0 10,10"/></svg>""").strokes.single().points
        assertEquals(polygon.first(), polygon.last())
        val polyline = svgFigure("""<svg><polyline points="0,0 10,0 10,10"/></svg>""").strokes.single().points
        assertEquals(3, polyline.size)
    }

    // ---- path data ----

    @Test
    fun `absolute and relative path commands land on the same points`() {
        val absolute = svgFigure("""<svg><path d="M 10 10 L 30 10 L 30 30"/></svg>""")
        val relative = svgFigure("""<svg><path d="m 10 10 l 20 0 l 0 20"/></svg>""")
        assertEquals(absolute.strokes.single().points, relative.strokes.single().points)
    }

    @Test
    fun `implicit linetos follow a moveto's extra pairs`() {
        val figure = svgFigure("""<svg><path d="M 0 0 10 0 10 10"/></svg>""")
        assertEquals(
            listOf(WritePoint(0f, 0f), WritePoint(10f, 0f), WritePoint(10f, 10f)),
            figure.strokes.single().points,
        )
    }

    @Test
    fun `H and V move one axis at a time and Z returns to the subpath start`() {
        val figure = svgFigure("""<svg><path d="M 0 0 H 10 V 10 Z"/></svg>""")
        val points = figure.strokes.single().points
        assertEquals(WritePoint(0f, 0f), points.first())
        assertEquals(WritePoint(0f, 0f), points.last())
        assertTrue(points.contains(WritePoint(10f, 0f)))
        assertTrue(points.contains(WritePoint(10f, 10f)))
    }

    @Test
    fun `each moveto starts a stroke of its own`() {
        val figure = svgFigure("""<svg><path d="M 0 0 L 10 0 M 0 10 L 10 10"/></svg>""")
        assertEquals(2, figure.strokes.size)
    }

    @Test
    fun `a cubic curve is flattened through its bow`() {
        // Symmetric cubic from (0,0) to (30,0) bowing to y=15 at its middle.
        val figure = svgFigure("""<svg><path d="M 0 0 C 10 20 20 20 30 0"/></svg>""")
        val points = figure.strokes.single().points
        val apex = points.maxBy { it.y }
        assertEquals(15f, apex.y, 1f)
        assertTrue(points.size > 10)
    }

    @Test
    fun `a quadratic curve passes halfway to its control point`() {
        val figure = svgFigure("""<svg><path d="M 0 0 Q 15 20 30 0"/></svg>""")
        val apex = figure.strokes.single().points.maxBy { it.y }
        assertEquals(10f, apex.y, 1f)
    }

    @Test
    fun `S reflects the previous cubic control point`() {
        // C ends flat at (20,0) with control (20,10); S reflects to (20,-10),
        // so the joined curve continues smoothly downward then bows up.
        val figure = svgFigure("""<svg><path d="M 0 0 C 0 10 20 10 20 0 S 40 -10 40 0"/></svg>""")
        // The C half bows to +7.5, the reflected S half dips to -7.5: without
        // the reflection the S half would bow upward too and halve the height.
        assertEquals(15f, figure.height, 1f)
        assertEquals(40f, figure.width, 1e-3f)
    }

    @Test
    fun `an arc sweeps through its far side`() {
        // Half circle of radius 5 from (0,0) to (10,0), sweep=1: through (5,-5).
        val figure = svgFigure("""<svg><path d="M 0 0 A 5 5 0 0 1 10 0"/></svg>""")
        val points = figure.strokes.single().points
        // Normalized: minY becomes 0, so the arc's lowest point was -5.
        assertEquals(5f, figure.height, 0.1f)
        val mid = points.minBy { abs(it.x - 5f) }
        assertEquals(0f, mid.y, 0.2f)
    }

    @Test
    fun `arc flags may run together without separators`() {
        val spaced = svgFigure("""<svg><path d="M 0 0 A 5 5 0 1 1 0.1 0"/></svg>""")
        val packed = svgFigure("""<svg><path d="M 0 0 A 5 5 0 110.1 0"/></svg>""")
        assertEquals(spaced.strokes.single().points.size, packed.strokes.single().points.size)
    }

    @Test
    fun `path numbers may butt against each other with dots and minuses`() {
        val figure = svgFigure("""<svg><path d="M.5.5L-10.5-20.5"/></svg>""")
        val points = figure.strokes.single().points
        assertEquals(2, points.size)
        assertEquals(11f, figure.width, 1e-3f)
        assertEquals(21f, figure.height, 1e-3f)
    }

    // ---- transforms and structure ----

    @Test
    fun `a group's translate carries onto its children`() {
        val figure = svgFigure(
            """<svg><g transform="translate(100 50)"><line x1="0" y1="0" x2="10" y2="0"/></g>
               <line x1="100" y1="50" x2="100" y2="60"/></svg>""",
        )
        // Both lines share the translated corner, so the figure is 10x10.
        assertEquals(10f, figure.width, 1e-3f)
        assertEquals(10f, figure.height, 1e-3f)
    }

    @Test
    fun `scale and rotate compose left to right`() {
        val figure = svgFigure(
            """<svg><g transform="scale(2) rotate(90)"><line x1="0" y1="0" x2="10" y2="0"/></g></svg>""",
        )
        // Rotate first maps the line onto +y, then scale doubles it.
        assertEquals(0f, figure.width, 1e-3f)
        assertEquals(20f, figure.height, 1e-3f)
    }

    @Test
    fun `an element's own transform applies too`() {
        val figure = svgFigure("""<svg><line x1="0" y1="0" x2="10" y2="0" transform="scale(3)"/></svg>""")
        assertEquals(30f, figure.width, 1e-3f)
    }

    @Test
    fun `defs and text are skipped, even with shapes inside`() {
        val figure = svgFigure(
            """<svg>
               <defs><circle cx="0" cy="0" r="500"/><g><rect x="0" y="0" width="900" height="900"/></g></defs>
               <text x="0" y="0">giant label</text>
               <line x1="0" y1="0" x2="10" y2="0"/>
               </svg>""",
        )
        assertEquals(1, figure.strokes.size)
        assertEquals(10f, figure.width, 1e-3f)
    }

    @Test
    fun `comments and doctype do not confuse the walk`() {
        val figure = svgFigure(
            """<?xml version="1.0"?><!DOCTYPE svg><!-- a > b --><svg><line x1="0" y1="0" x2="5" y2="0"/></svg>""",
        )
        assertEquals(1, figure.strokes.size)
    }

    // ---- refusals ----

    @Test
    fun `markup with nothing drawable is refused with a reason`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            svgFigure("""<svg><text x="0" y="0">words only</text></svg>""")
        }
        assertTrue(error.message!!.contains("no drawable geometry"))
    }

    @Test
    fun `blank markup is refused`() {
        assertThrows(IllegalArgumentException::class.java) { svgFigure("   ") }
    }

    @Test
    fun `a single point is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            svgFigure("""<svg><line x1="5" y1="5" x2="5" y2="5"/></svg>""")
        }
    }

    @Test
    fun `an unknown path command is refused by name`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            svgFigure("""<svg><path d="M 0 0 W 10 10"/></svg>""")
        }
        assertTrue(error.message!!.contains("W"))
    }

    // ---- decimation ----

    @Test
    fun `decimation keeps endpoints and enforces spacing`() {
        val dense = WriteStroke((0..100).map { WritePoint(it.toFloat(), 0f) })
        val thin = decimated(listOf(dense), spacingPx = 10f).single().points
        assertEquals(WritePoint(0f, 0f), thin.first())
        assertEquals(WritePoint(100f, 0f), thin.last())
        for (i in 1 until thin.size - 1) {
            val gap = hypot(thin[i].x - thin[i - 1].x, thin[i].y - thin[i - 1].y)
            assertTrue("gap $gap under spacing", gap >= 10f)
        }
        assertTrue(thin.size in 3..12)
    }
}
