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
    fun `a formula's own letters are never subscripted, only its digits`() {
        // SUBSCRIPT_CHARS maps several lowercase letters now (real LaTeX
        // subscripts use them) — Na's 'a', Cl's 'l' and Fe's 'e' are all in
        // that map, and none of the three may be touched by a formula that
        // happens to contain them.
        assertEquals("NaCl", mathChemNotation("NaCl"))
        assertEquals("Fe₂O₃", mathChemNotation("Fe2O3"))
        assertEquals("NaHCO₃", mathChemNotation("NaHCO3"))
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

    /**
     * Verbatim from the device: a fraction was the one shape the first pass
     * of this file didn't cover — asked for a derivative and an integral,
     * the exponent rendered fine but `\frac{1}{3}` reached the page as
     * literal backslashes and braces.
     */
    @Test
    fun `the fraction that was missed renders as division, not its LaTeX source`() {
        assertEquals(
            "Đạo hàm của x² là 2x. Tích phân của x² từ 0 đến 1 bằng 1/3.",
            mathChemNotation(
                """Đạo hàm của x^2 là 2x. Tích phân của x^2 từ 0 đến 1 bằng \frac{1}{3}.""",
            ),
        )
    }

    @Test
    fun `a fraction with an expression on either side keeps its shape`() {
        assertEquals("(a+b)/c", mathChemNotation("""\frac{a+b}{c}"""))
    }

    @Test
    fun `a square root gets a radical sign and parens around its argument`() {
        assertEquals("√(2)", mathChemNotation("""\sqrt{2}"""))
        assertEquals("√(x+1)", mathChemNotation("""\sqrt{x+1}"""))
    }

    @Test
    fun `calculus and common symbol commands become their Unicode character`() {
        assertEquals("∫ x² dx", mathChemNotation("""\int x^2 dx"""))
        assertEquals("lim", mathChemNotation("""\lim"""))
        assertEquals("x → ∞", mathChemNotation("""x \to \infty"""))
        assertEquals("π ≈ 3.14", mathChemNotation("""\pi \approx 3.14"""))
        assertEquals("2 × 3 ≤ 10", mathChemNotation("""2 \times 3 \leq 10"""))
    }

    @Test
    fun `a braced subscript and superscript with a variable and an operator both convert`() {
        // \sum_{i=1}^{n} — a sum's bounds, one of the two real replies that
        // pushed subscript/superscript past digits-only: 'i' and 'n' have
        // subscript/superscript Latin forms, '=' has one at each size too.
        assertEquals("∑ᵢ₌₁ⁿ i", mathChemNotation("""\sum_{i=1}^{n} i"""))
    }

    @Test
    fun `a braced subscript keeps a character with no small form at full size`() {
        // \lim_{x \to 0} — the other real reply. 'x' converts; the arrow
        // (already → by the time toSubscript runs) and the space around it
        // have no subscript form, so both stay exactly as written, at full
        // size, inside the run.
        assertEquals("limₓ → ₀ f(x)", mathChemNotation("""\lim_{x \to 0} f(x)"""))
    }

    @Test
    fun `an unbraced letter subscript or exponent is still a documented gap`() {
        // x_i and x^n — no braces, so the digit-only bare form doesn't fire
        // (widening it would also catch snake_case_name's own underscore and
        // a^b^c's own caret — see SUBSCRIPT_MARK/EXPONENT's own comment).
        // x_{i} or x^{n}, braced, is what reaches the small form instead —
        // see the two tests above.
        assertEquals("x_i", mathChemNotation("x_i"))
        assertEquals("x^n", mathChemNotation("x^n"))
    }

    /**
     * Verbatim from the device: asked for the quadratic formula, `ax^2` and
     * `b^2` rendered fine, but `\ne`, `\Delta` and both `\frac{...}{...}`
     * calls reached the page as literal LaTeX source — `\ne` and `\Delta`
     * because neither command was recognised yet, and `\frac` because its
     * numerator holds `\sqrt{\Delta}`, a brace-delimited command of its own,
     * which the fraction's own (deliberately brace-free) capture group
     * can't straddle in one pass. This is the case [mathChemNotation]'s
     * fixed-point loop exists for.
     */
    @Test
    fun `the quadratic formula that exposed nesting renders in full`() {
        assertEquals(
            "Với a ≠ 0, phương trình ax²+bx+c=0 có biệt thức Δ=b²-4ac. " +
                "Nếu Δ>0: x₁=(-b+√(Δ))/2a, x₂=(-b-√(Δ))/2a.",
            mathChemNotation(
                """Với a \ne 0, phương trình ax^2+bx+c=0 có biệt thức \Delta=b^2-4ac. """ +
                    """Nếu \Delta>0: x_1=\frac{-b+\sqrt{\Delta}}{2a}, x_2=\frac{-b-\sqrt{\Delta}}{2a}.""",
            ),
        )
    }

    @Test
    fun `uppercase and lowercase Greek letters are distinct`() {
        assertEquals("δ vs Δ", mathChemNotation("""\delta vs \Delta"""))
        assertEquals("σ vs Σ", mathChemNotation("""\sigma vs \Sigma"""))
    }

    @Test
    fun `left and right delimiters are dropped, the bracket itself stays`() {
        assertEquals("(a+b)", mathChemNotation("""\left(a+b\right)"""))
        assertEquals("[0, 1]", mathChemNotation("""\left[0, 1\right]"""))
    }

    @Test
    fun `stripping left and right does not eat into rightarrow`() {
        // \right is a prefix of \rightarrow — without its own boundary check,
        // stripping \left/\right would leave a bare "arrow" behind instead
        // of letting toSymbols convert the whole command to →.
        assertEquals("x → ∞", mathChemNotation("""x \rightarrow \infty"""))
    }

    @Test
    fun `an nth root gets a superscript index before the radical`() {
        assertEquals("²√(x)", mathChemNotation("""\sqrt[2]{x}"""))
        assertEquals("³√(8)", mathChemNotation("""\sqrt[3]{8}"""))
    }

    @Test
    fun `function names read as the bare word once the backslash is gone`() {
        assertEquals("sin(x) + cos(x)", mathChemNotation("""\sin(x) + \cos(x)"""))
        assertEquals("ln(x)", mathChemNotation("""\ln(x)"""))
    }

    @Test
    fun `set theory and logic symbols convert`() {
        assertEquals("∀ x ∈ ℝ, x² ≥ 0", mathChemNotation("""\forall x \in ℝ, x^2 \geq 0"""))
        assertEquals("A ⊆ B", mathChemNotation("""A \subseteq B"""))
    }

    @Test
    fun `a fraction nested two levels deep still resolves`() {
        // \frac{1}{\frac{1}{2}} — the outer fraction's denominator is itself
        // a fraction; one pass resolves the inner one, the next resolves the
        // outer now that its own denominator has no braces left in it.
        assertEquals("1/(1/2)", mathChemNotation("""\frac{1}{\frac{1}{2}}"""))
    }

    /**
     * Verbatim from the device: asked to write several formulas to check
     * LaTeX rendering, a real reply used `\qquad` five times as a separator
     * between one formula and the next — every one of them reached the page
     * as literal backslash-q-q-u-a-d.
     */
    @Test
    fun `qquad and quad become a wide plain-text gap`() {
        assertEquals("H₂O,      CO₂", mathChemNotation("""H2O, \qquad CO2"""))
        assertEquals("a    b", mathChemNotation("""a \quad b"""))
    }

    @Test
    fun `thin, medium and thick space commands become a single space, a negative one becomes nothing`() {
        assertEquals("a b", mathChemNotation("""a\,b"""))
        assertEquals("a b", mathChemNotation("""a\;b"""))
        assertEquals("a b", mathChemNotation("""a\:b"""))
        assertEquals("ab", mathChemNotation("""a\!b"""))
    }

    @Test
    fun `eulers identity renders with only the characters that have a superscript form raised`() {
        // e^{i\pi}+1=0 — \pi converts to π first (toSymbols runs before
        // toSuperscript in one pass); π itself has no superscript glyph in
        // Unicode, so it stays full-size right after the raised i.
        assertEquals("eⁱπ+1=0", mathChemNotation("""e^{i\pi}+1=0"""))
    }
}
