package com.riddleboox.app.handwriting

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteCursorTest {

    private fun cursor(
        pageWidthPx: Int = 400,
        jitterPx: Float = 0f,
        paragraphGapPx: Float = 30f,
        startYPx: Float = 0f,
        bottomLimitPx: Float = Float.MAX_VALUE,
        seed: Int = 7,
    ) = WriteCursor(
        raster = FakeRaster(),
        pageWidthPx = pageWidthPx,
        fontSizePx = 96f,
        lineHeightPx = 120f,
        jitterPx = jitterPx,
        marginXPx = 120f,
        paragraphGapPx = paragraphGapPx,
        startYPx = startYPx,
        bottomLimitPx = bottomLimitPx,
        random = Random(seed),
    )

    /** One word per line (page 300 leaves 60px), two lines before the foot. */
    private fun twoLinePage() = cursor(pageWidthPx = 300, bottomLimitPx = 300f)

    /** Every word's bar, as (x, first y) — enough to place it on the page. */
    private fun WritePlan.bars(): List<Pair<Float, Float>> =
        strokes.map { it.points.first().x to it.points.first().y }

    @Test
    fun `words run left to right from the margin`() {
        // Margin 120, "aaaa" is 40 wide, the space between words is 10.
        val plan = cursor().apply { append("aaaa bbbb") }.plan()
        assertEquals(listOf(125f to 2f, 175f to 2f), plan.bars())
    }

    @Test
    fun `a word that would overrun the margin starts the next line`() {
        // Page 300 leaves 60px of line: the second word cannot follow the first.
        val plan = cursor(pageWidthPx = 300).apply { append("aaaa bbbb") }.plan()
        assertEquals(listOf(125f to 2f, 125f to 122f), plan.bars())
    }

    @Test
    fun `a word wider than the line gets the line to itself rather than being cut`() {
        val plan = cursor(pageWidthPx = 200).apply { append("aaaa b") }.plan()
        assertEquals(listOf(125f to 2f, 125f to 122f), plan.bars())
    }

    @Test
    fun `a blank line between paragraphs costs air, not a whole line`() {
        // One line down (120) plus the paragraph's air (30), not two lines.
        val plan = cursor().apply { append("a\n\nb") }.plan()
        assertEquals(listOf(125f to 2f, 125f to 152f), plan.bars())
    }

    @Test
    fun `however many blank lines the text carried, the air is bought once`() {
        val plan = cursor().apply { append("a\n\n\n\nb") }.plan()
        assertEquals(listOf(125f to 2f, 125f to 152f), plan.bars())
    }

    @Test
    fun `a paragraph split across two appends is still one paragraph`() {
        // The model streams the break in pieces; the pen must not read the
        // halves as two separate line breaks.
        val cursor = cursor()
        cursor.append("a\n")
        cursor.append("\nb")
        assertEquals(listOf(125f to 2f, 125f to 152f), cursor.plan().bars())
    }

    @Test
    fun `an explicit newline breaks the line`() {
        val plan = cursor().apply { append("a\nb") }.plan()
        assertEquals(listOf(125f to 2f, 125f to 122f), plan.bars())
    }

    /**
     * The property the whole streaming design rests on: where a word lands
     * cannot depend on what comes after it. Batches break at word boundaries,
     * which is what [com.riddleboox.app.reply.writableCut] guarantees the pen.
     */
    @Test
    fun `text split across appends lands exactly where the whole text would`() {
        val whole = cursor().apply { append("aaaa bbbb cc dddd") }.plan()

        val streamed = cursor()
        val batches = listOf("aaaa ", "bbbb ", "cc ", "dddd")
        val returned = batches.flatMap { streamed.append(it) }

        assertEquals(whole.strokes, streamed.plan().strokes)
        // append() hands back exactly the new ink, and nothing twice.
        assertEquals(whole.strokes, returned)
    }

    @Test
    fun `append returns only the strokes it added`() {
        val cursor = cursor()
        val first = cursor.append("aaaa ")
        val second = cursor.append("bbbb")
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals(first + second, cursor.strokes)
    }

    @Test
    fun `startY offsets the whole reply`() {
        val plan = cursor(startYPx = 500f).apply { append("aaaa") }.plan()
        assertEquals(listOf(125f to 502f), plan.bars())
    }

    @Test
    fun `jitter shifts a whole line together and stays within budget`() {
        // Page 300 leaves room for one word per line, so each stroke is a line.
        val plan = cursor(pageWidthPx = 300, jitterPx = 3f)
            .apply { append("aaaa bbbb cccc dddd") }
            .plan()

        assertEquals(4, plan.strokes.size)
        plan.strokes.forEachIndexed { line, stroke ->
            val wobble = stroke.points.first().y - (line * 120f + 2f)
            assertTrue("line $line drifted by $wobble", abs(wobble) <= 3f)
            // A whole line shifts together: the bar keeps its 7px height.
            assertEquals(6f, stroke.points.last().y - stroke.points.first().y, 0f)
        }
    }

    @Test
    fun `the pen stops at the foot of the page and holds the rest`() {
        val cursor = twoLinePage()
        val written = cursor.append("aaaa bbbb cccc dddd")

        assertEquals(2, written.size)
        assertEquals(listOf(125f to 2f, 125f to 122f), cursor.plan().bars())
        assertTrue("words are waiting for a page", cursor.pageFull)
    }

    @Test
    fun `more text while the page is full is held, not written off the bottom`() {
        val cursor = twoLinePage()
        cursor.append("aaaa bbbb cccc")
        assertEquals(emptyList<WriteStroke>(), cursor.append("dddd eeee"))
        assertEquals(2, cursor.plan().strokes.size)
    }

    @Test
    fun `turning the page starts again at the top with the words that waited`() {
        val cursor = twoLinePage()
        cursor.append("aaaa bbbb cccc dddd")

        val overleaf = cursor.nextPage()
        assertEquals(2, overleaf.size)
        // The new page is the whole of what the pen now holds.
        assertEquals(listOf(125f to 2f, 125f to 122f), cursor.plan().bars())
        assertEquals(overleaf, cursor.plan().strokes)
        assertFalse("nothing left waiting", cursor.pageFull)
    }

    @Test
    fun `a page turn keeps going for as many pages as the reply needs`() {
        val cursor = twoLinePage()
        cursor.append("aaaa bbbb cccc dddd eeee ffff")
        assertTrue(cursor.pageFull)
        cursor.nextPage()
        assertTrue(cursor.pageFull)
        cursor.nextPage()
        assertFalse(cursor.pageFull)
        assertEquals(2, cursor.plan().strokes.size)
    }

    @Test
    fun `a line break with nothing after it does not call for a new page`() {
        val cursor = twoLinePage()
        cursor.append("aaaa bbbb\n")
        assertFalse("a trailing break has nothing to put overleaf", cursor.pageFull)
    }

    @Test
    fun `a page with no foot never fills`() {
        val cursor = cursor(pageWidthPx = 300)
        cursor.append("aaaa bbbb cccc dddd eeee ffff")
        assertFalse(cursor.pageFull)
        assertEquals(6, cursor.plan().strokes.size)
    }

    @Test
    fun `blank text writes nothing`() {
        val cursor = cursor()
        assertEquals(emptyList<WriteStroke>(), cursor.append("   "))
        assertEquals(emptyList<WriteStroke>(), cursor.plan().strokes)
    }

    @Test
    fun `the nib waits at the margin before the first word and after it once written`() {
        val cursor = cursor()
        // Half a line down from the top, at the left margin.
        assertEquals(120f, cursor.penPoint.x, 0f)
        assertEquals(96f * 0.55f, cursor.penPoint.y, 0.01f)

        cursor.append("aaaa")
        // 120 + 40 written, plus the space the next word would sit after.
        assertEquals(170f, cursor.penPoint.x, 0f)
    }

    @Test
    fun `the nib moves down with the line it is waiting on`() {
        val cursor = cursor(pageWidthPx = 300)
        cursor.append("aaaa bbbb")
        assertEquals(120f + 96f * 0.55f, cursor.penPoint.y, 0.01f)
        assertEquals(170f, cursor.penPoint.x, 0f)
    }
}
