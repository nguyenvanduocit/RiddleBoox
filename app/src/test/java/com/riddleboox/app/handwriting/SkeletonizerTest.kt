package com.riddleboox.app.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkeletonizerTest {

    @Test
    fun `already thin line is left untouched`() {
        val mask = maskOf(
            ".....",
            "..#..",
            "..#..",
            "..#..",
            "..#..",
            "..#..",
            ".....",
        )
        val before = mask.render()
        Skeletonizer.thin(mask)
        assertEquals(before, mask.render())
    }

    @Test
    fun `thick bar collapses to a single pixel wide skeleton`() {
        val mask = maskOf(
            ".....",
            ".###.",
            ".###.",
            ".###.",
            ".###.",
            ".###.",
            ".....",
        )
        val original = mask.render()
        Skeletonizer.thin(mask)

        assertFalse("skeleton must be 1px thin:\n${mask.render().joinToString("\n")}", mask.hasSolidBlock())
        assertTrue("skeleton must lose ink", mask.inkedCount < 15)
        assertTrue("skeleton must survive", mask.inkedCount > 0)
        assertSubsetOf(original, mask)
        (0 until mask.height).forEach { y ->
            val inkedInRow = (0 until mask.width).count { x -> mask[x, y] }
            assertTrue("row $y should keep at most one pixel, got $inkedInRow", inkedInRow <= 1)
        }
    }

    @Test
    fun `thinning is idempotent`() {
        val mask = maskOf(
            "........",
            ".####...",
            ".####...",
            ".####...",
            ".####...",
            ".#######",
            ".#######",
            "........",
        )
        Skeletonizer.thin(mask)
        val once = mask.render()
        Skeletonizer.thin(mask)
        assertEquals(once, mask.render())
        assertFalse("skeleton must be 1px thin:\n${once.joinToString("\n")}", mask.hasSolidBlock())
    }

    @Test
    fun `a thin diagonal survives instead of being eaten from both sides`() {
        // Every Vietnamese tone mark is a short thin diagonal at the reply's
        // glyph height. A thinning rule that deletes complementary pixels on
        // each pass erases the whole run and the diary loses its accents.
        val mask = maskOf(
            ".......",
            ".##....",
            "..##...",
            "...##..",
            "....##.",
            ".....#.",
            ".......",
        )
        Skeletonizer.thin(mask)
        assertTrue(
            "diagonal must survive:\n${mask.render().joinToString("\n")}",
            mask.inkedCount >= 5,
        )
        assertFalse("skeleton must be 1px thin", mask.hasSolidBlock())
    }

    @Test
    fun `ink touching the border is never eroded`() {
        val mask = maskOf(
            "#####",
            "#####",
            "#####",
            "#####",
            "#####",
        )
        val before = mask.render()
        Skeletonizer.thin(mask)
        assertEquals("thinning only scans interior pixels, so unpadded ink survives", before, mask.render())
    }

    private fun assertSubsetOf(original: List<String>, thinned: GlyphMask) {
        for (y in 0 until thinned.height) {
            for (x in 0 until thinned.width) {
                if (thinned[x, y]) {
                    assertTrue("($x,$y) was not inked before thinning", original[y][x] == '#')
                }
            }
        }
    }
}
