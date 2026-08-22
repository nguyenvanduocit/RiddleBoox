package com.riddleboox.app.reply

/**
 * Chemical formulas and math exponents, as a hand would actually write them.
 *
 * Verbatim from the device: asked to repeat a formula, the model reached for
 * real inline LaTeX — `\( \mathrm{H_2O} \)`, `\( x^2 \)` — not bare `H2O`.
 * Left alone the pen writes every backslash and brace it is given, the same
 * way it would write raw `**markdown**` if [plainText] didn't strip it — see
 * [stripLatexWrappers]. A model that skips LaTeX and just writes `H2O` is
 * covered too, by [toChemFormulas] below; the two shapes don't overlap, so
 * both run.
 *
 * Runs after [plainText]'s own marks are stripped, on the same trimmed text.
 * Order between the two does not matter: a chemistry underscore is always
 * glued to the letter before it (`H_2`, never `foo _2_ bar`), which is
 * exactly the shape [plainText]'s italic regex requires NOT to be glued to —
 * its negative lookbehind `(?<![\w_])` already refuses to treat a
 * word-adjacent underscore as an opening mark (`snake_case_name` survives it
 * unchanged, per its own test), so `H_2O` was never at risk of losing its
 * underscore to markdown-stripping in the first place.
 *
 * Deliberately narrow, the same restraint [plainText] documents for itself —
 * only the shapes that have actually turned up in a reply are handled:
 *
 *  - **LaTeX wrapping** ([stripLatexWrappers]): `\(...\)` and `\[...\]`
 *    delimiters are dropped, `\mathrm{...}` and `\text{...}` keep their
 *    content and lose the command. Nothing else — `\frac`, `\sqrt`, Greek
 *    letters are a real gap, left for whenever a reply is caught reaching
 *    for them.
 *  - **Explicit subscript/superscript** ([toSubscript], [toSuperscript]):
 *    `_2`, `_{12}` and `^2`, `^{-3}` — LaTeX's own markers, which the model
 *    used for subscript and already used correctly for superscript before
 *    this file's exponent handling existed.
 *  - A bare **formula** ([toChemFormulas]), for a reply that skips LaTeX
 *    entirely: a `\b`-bounded word made only of element-symbol fragments —
 *    one capital letter, at most one lowercase, at most three trailing
 *    digits, repeated one or more times (`H2O`, `CO2`, `C6H12O6`, `Fe2O3`).
 *    That shape is also what keeps it from firing on an ordinary capitalised
 *    word: English words carry runs of two or more lowercase letters after
 *    their capital (`Web2.0`'s `Web` cannot match a single `[a-z]?`), and an
 *    all-caps id with a long digit tail (`ISO27001`) cannot either — its
 *    five trailing digits blow past the three-digit cap with no `\b` inside
 *    a digit run to stop at early.
 */
fun mathChemNotation(text: String): String =
    toSuperscript(toChemFormulas(toSubscript(stripLatexWrappers(text))))

private val MATHRM = Regex("""\\(?:mathrm|text)\{([^}]*)\}""")
private val OPEN_DELIM = Regex("""\\[(\[]\s*""")
private val CLOSE_DELIM = Regex("""\s*\\[)\]]""")

private fun stripLatexWrappers(text: String): String {
    val unwrapped = MATHRM.replace(text) { it.groupValues[1] }
    return CLOSE_DELIM.replace(OPEN_DELIM.replace(unwrapped, ""), "")
}

private val SUBSCRIPT_MARK = Regex("""_(?:\{(\d+)\}|(\d))""")
private val EXPONENT = Regex("""\^(?:\{([+-]?\d+)\}|([+-]?\d+))""")

private val SUBSCRIPT_DIGITS = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
)

private val SUPERSCRIPT_CHARS = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻',
)

private fun toSubscript(text: String): String = SUBSCRIPT_MARK.replace(text) { match ->
    (match.groupValues[1].ifEmpty { match.groupValues[2] }).map { SUBSCRIPT_DIGITS[it] ?: it }.joinToString("")
}

private fun toSuperscript(text: String): String = EXPONENT.replace(text) { match ->
    (match.groupValues[1].ifEmpty { match.groupValues[2] }).map { SUPERSCRIPT_CHARS[it] ?: it }.joinToString("")
}

private val FORMULA = Regex("""\b(?:[A-Z][a-z]?\d{0,3})+\b""")

private fun toChemFormulas(text: String): String = FORMULA.replace(text) { match ->
    val token = match.value
    if (token.none(Char::isDigit)) token else token.map { SUBSCRIPT_DIGITS[it] ?: it }.joinToString("")
}
