package com.riddleboox.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The script is data, and the data carries invariants the code around it
 * leans on: the page count the prose spells out in words, which page the
 * permission ask follows, that every page fits the paper it is written on,
 * and that no page sends the writer looking for a word that is not on the
 * screen.
 */
class OnboardingScriptTest {

    /**
     * The progress line computes its total from the list, but "six" is written
     * out as a word in two places — the first page here, and the welcome
     * screen's body line (WelcomeOverlay.kt). Add a page and both sentences
     * have to change with this number.
     */
    @Test
    fun `there are six pages, and the first page says so`() {
        assertEquals(6, ONBOARDING_SEGMENTS.size)
        assertTrue(ONBOARDING_SEGMENTS[0].contains("Six"))
    }

    /**
     * MainActivity's ONBOARDING_PERMISSION_CHECKPOINT = 4 puts the all-files
     * access ask right after this page, because this is the page that explains
     * why the diary would want to read the writer's books. Move the page and the
     * constant together.
     */
    @Test
    fun `the fifth page is the one about reading books`() {
        assertTrue(ONBOARDING_SEGMENTS[4].contains("book", ignoreCase = true))
    }

    /**
     * One page of a Note Air 2 (1404 × 1872 px) at the largest reply font
     * (ReplyFontSize.ExtraLarge, 96 px; line height 1.25 em = 120 px) holds
     * (1872 − REPLY_TOP_PX 100 − REPLY_BOTTOM_PX 80) / 120 ≈ 14 lines of about
     * 25 characters each. 220 characters is nine of those lines with room for
     * ragged wrapping; a segment past it is what OnboardingController logs as
     * "overflows one page".
     */
    @Test
    fun `every page fits one sheet at the largest font`() {
        ONBOARDING_SEGMENTS.forEachIndexed { index, segment ->
            assertTrue("page ${index + 1} is ${segment.length} characters", segment.length <= 220)
        }
    }

    /**
     * The labels a page tells the writer to touch are the labels the chrome
     * shows a first-time writer (MainActivity.kt). 'send' is deliberately not
     * among them: it only appears in manual send mode, and the intro runs in
     * the default, automatic one — a page naming it would send the writer
     * looking for a word that is not there.
     */
    @Test
    fun `every quoted label is on the chrome in the default send mode`() {
        val chrome = setOf("new conversation", "memorize", "stop", "history", "settings")
        val quoted = Regex("'([^']+)'")
        ONBOARDING_SEGMENTS.forEachIndexed { index, segment ->
            quoted.findAll(segment).forEach { match ->
                assertTrue("page ${index + 1} names '${match.groupValues[1]}', which is not on the chrome", match.groupValues[1] in chrome)
            }
        }
    }
}
