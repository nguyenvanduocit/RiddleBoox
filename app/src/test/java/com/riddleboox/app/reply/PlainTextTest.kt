package com.riddleboox.app.reply

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTextTest {

    /** Verbatim from the device: the reply that put asterisks on the page. */
    @Test
    fun `the reply that started this keeps its words and loses its marks`() {
        assertEquals(
            "Vậy là ngươi tự xưng Chúa tể Bóng Tối ư? Một danh hiệu đầy tham vọng—hãy nói ta nghe, " +
                "điều gì khiến ngươi khao khát quyền lực đến thế?",
            plainText(
                "Vậy là ngươi tự xưng **Chúa tể Bóng Tối** ư? Một danh hiệu đầy tham vọng—hãy nói ta nghe, " +
                    "điều gì khiến ngươi khao khát quyền lực đến thế?",
            ),
        )
    }

    @Test
    fun `every emphasis the models reach for`() {
        assertEquals("bold", plainText("**bold**"))
        assertEquals("italic", plainText("*italic*"))
        assertEquals("both", plainText("***both***"))
        assertEquals("under", plainText("__under__"))
        assertEquals("single", plainText("_single_"))
        assertEquals("code", plainText("`code`"))
    }

    @Test
    fun `marks inside a sentence go without taking their neighbours`() {
        assertEquals(
            "Ta nhớ tên ngươi, Duoc — ngươi nói nó rất khẽ.",
            plainText("Ta nhớ **tên ngươi**, _Duoc_ — ngươi nói nó rất khẽ."),
        )
    }

    /** A diary is written to in prose; an asterisk in it is an asterisk. */
    @Test
    fun `punctuation that only looks like markdown survives`() {
        assertEquals("2 * 3 = 6", plainText("2 * 3 = 6"))
        assertEquals("snake_case_name", plainText("snake_case_name"))
        assertEquals("a — dash", plainText("a — dash"))
        assertEquals("*", plainText("*"))
    }

    /** The marks go; the line break is the model's, and stays the model's. */
    @Test
    fun `emphasis spanning a line break still closes`() {
        assertEquals("hai\ndòng", plainText("**hai\ndòng**"))
    }

    @Test
    fun `plain prose comes back untouched`() {
        val prose = "Ta là ký ức của Tom Marvolo Riddle. Ngươi là ai?"
        assertEquals(prose, plainText(prose))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("Chào ngươi.", plainText("  **Chào ngươi.**\n"))
    }
}
