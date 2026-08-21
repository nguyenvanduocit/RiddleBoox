package com.riddleboox.app.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkeletonTracerTest {

    @Test
    fun `single line traces to one stroke ordered from its endpoint`() {
        val strokes = SkeletonTracer.trace(
            maskOf(
                ".....",
                "..#..",
                "..#..",
                "..#..",
                "..#..",
                "..#..",
                ".....",
            )
        )
        assertEquals(1, strokes.size)
        assertEquals(
            listOf(
                MaskPoint(2, 1),
                MaskPoint(2, 2),
                MaskPoint(2, 3),
                MaskPoint(2, 4),
                MaskPoint(2, 5),
            ),
            strokes[0],
        )
    }

    @Test
    fun `corner is traced as one continuous stroke`() {
        val strokes = SkeletonTracer.trace(
            maskOf(
                ".......",
                "..#....",
                "..#....",
                "..#....",
                "..###..",
                ".......",
                ".......",
            )
        )
        assertEquals(1, strokes.size)
        assertEquals(
            listOf(
                MaskPoint(2, 1),
                MaskPoint(2, 2),
                MaskPoint(2, 3),
                MaskPoint(2, 4),
                MaskPoint(3, 4),
                MaskPoint(4, 4),
            ),
            strokes[0],
        )
    }

    @Test
    fun `separate strokes come out ordered left to right`() {
        val strokes = SkeletonTracer.trace(
            maskOf(
                ".......",
                ".#...#.",
                ".#...#.",
                ".#...#.",
                ".#...#.",
                ".......",
            )
        )
        assertEquals(2, strokes.size)
        assertEquals(1, strokes[0].minOf { it.x })
        assertEquals(5, strokes[1].minOf { it.x })
        assertTrue(strokes.all { it.size == 4 })
    }

    @Test
    fun `a mark of one or two pixels is a stroke of its own`() {
        // What a full stop thins down to. Dropping it costs the reply its
        // sentence endings, so the tracer keeps every run it walks.
        val strokes = SkeletonTracer.trace(
            maskOf(
                ".....",
                ".#...",
                ".#...",
                ".....",
                ".....",
            )
        )
        assertEquals(1, strokes.size)
        assertEquals(listOf(MaskPoint(1, 1), MaskPoint(1, 2)), strokes[0])
    }

    @Test
    fun `a closed loop with no endpoint is still traced`() {
        val strokes = SkeletonTracer.trace(
            maskOf(
                "......",
                ".####.",
                ".#..#.",
                ".#..#.",
                ".####.",
                "......",
            )
        )
        // The greedy walk closes the ring and leaves the last two corner pixels
        // behind, each its own 1-pixel run — drawn on the ring's own outline,
        // under ink the loop stroke has already laid down.
        val loop = strokes.maxBy { it.size }
        assertEquals(10, loop.size)
        assertEquals(MaskPoint(1, 1), loop.first())
        assertEquals(listOf(1, 1), strokes.filter { it !== loop }.map { it.size })
    }
}
