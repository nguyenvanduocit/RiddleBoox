package com.riddleboox.app.reply

import org.junit.Assert.assertEquals
import org.junit.Test

class MathChemNotationTest {

    @Test
    fun `common formulas get their digits subscripted`() {
        assertEquals("H₂O", mathChemNotation("H2O"))
        assertEquals("CO₂", mathChemNotation("CO2"))
        assertEquals("C₆H₁₂O₆", mathChemNotation("C6H12O6"))
        assertEquals("Fe₂O₃", mathChemNotation("Fe2O3"))
        assertEquals("CaCO₃", mathChemNotation("CaCO3"))
    }

    @Test
    fun `a formula keeps its place in a sentence`() {
        assertEquals(
            "Công thức của nước là H₂O, đơn giản vậy thôi.",
            mathChemNotation("Công thức của nước là H2O, đơn giản vậy thôi."),
        )
    }

    @Test
    fun `a word with no digits is untouched`() {
        assertEquals("NaCl", mathChemNotation("NaCl"))
        assertEquals("Chào ngươi", mathChemNotation("Chào ngươi"))
    }

    @Test
    fun `plain numbers and ordinary words are not mistaken for formulas`() {
        assertEquals("123", mathChemNotation("123"))
        assertEquals("Web2.0", mathChemNotation("Web2.0"))
        // Standard id, not an element run: five trailing digits blow past the
        // three-digit cap a real formula's subscript run stays under.
        assertEquals("ISO27001", mathChemNotation("ISO27001"))
        assertEquals("gpt-5.6-luna", mathChemNotation("gpt-5.6-luna"))
        assertEquals("3 quả táo", mathChemNotation("3 quả táo"))
    }

    @Test
    fun `a caret exponent is superscripted`() {
        assertEquals("x²", mathChemNotation("x^2"))
        assertEquals("E=mc²", mathChemNotation("E=mc^2"))
        assertEquals("10⁻³", mathChemNotation("10^-3"))
        assertEquals("10²³ hạt", mathChemNotation("10^23 hạt"))
    }

    @Test
    fun `a caret before anything but a signed number is left alone`() {
        assertEquals("x^n", mathChemNotation("x^n"))
        assertEquals("a^b^c", mathChemNotation("a^b^c"))
    }

    @Test
    fun `plainText composes stripped marks with formula and exponent notation`() {
        assertEquals("H₂O", plainText("**H2O**"))
        assertEquals("x²", plainText("_x^2_"))
    }

    /**
     * Verbatim from the device: asked to repeat a formula, the model reached
     * for real inline LaTeX instead of bare `H2O` — this is the reply that
     * showed up on the page as literal backslashes and braces.
     */
    @Test
    fun `the reply that started this renders as a formula, not its LaTeX source`() {
        assertEquals(
            "Công thức nước là H₂O, khi CO₂ và x² là bình phương của x.",
            mathChemNotation(
                """Công thức nước là \( \mathrm{H_2O} \), khi \( \mathrm{CO_2} \) """ +
                    """và \( x^2 \) là bình phương của \( x \).""",
            ),
        )
    }

    @Test
    fun `mathrm and text wrappers keep their content and lose the command`() {
        // Bare digits inside the wrapper still go through the plain-formula
        // heuristic once unwrapped — the wrapper only ever hid the command.
        assertEquals("H₂O", mathChemNotation("""\mathrm{H2O}"""))
        assertEquals("Fe", mathChemNotation("""\text{Fe}"""))
    }

    @Test
    fun `display math delimiters are dropped along with inline ones`() {
        assertEquals("x²", mathChemNotation("""\[ x^2 \]"""))
    }

    @Test
    fun `explicit LaTeX subscript matches plain-formula subscript`() {
        assertEquals("H₂O", mathChemNotation("H_2O"))
        assertEquals("H₁₂O", mathChemNotation("""H_{12}O"""))
    }

    @Test
    fun `braced exponents are superscripted too`() {
        assertEquals("10⁻²³", mathChemNotation("""10^{-23}"""))
    }
}
