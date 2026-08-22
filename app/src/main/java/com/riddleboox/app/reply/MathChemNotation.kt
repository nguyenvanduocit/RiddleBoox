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
 *    content and lose the command.
 *  - **Fractions and roots** ([toFractions], [toRoots]): `\frac{1}{3}`
 *    becomes `1/3`, `\sqrt{2}` becomes `√(2)` — a hand has no way to stack a
 *    fraction or draw a radical's vinculum on a line of ink, so both fall
 *    back to the way they'd be said out loud rather than the way they'd be
 *    typeset. The parens around a root's argument are the same reasoning
 *    `(a+b)/c` would need for a fraction with an expression on top: without
 *    them the line after the root reads as outside it.
 *  - **Symbol commands** ([toSymbols]): the zero-argument names that turn
 *    up asking about calculus — `\int`, `\sum`, `\lim`, `\infty`, `\to`,
 *    `\pi` and the rest of [SYMBOL_COMMANDS] — each is one Unicode
 *    character LaTeX would have typeset it as anyway.
 *  - **Explicit subscript/superscript** ([toSubscript], [toSuperscript]):
 *    `_2`, `_{12}` and `^2`, `^{-3}` — LaTeX's own markers, which the model
 *    used for subscript and already used correctly for superscript before
 *    this file's exponent handling existed. Digits only: `\lim_{x \to 0}`'s
 *    subscript is a whole sub-expression, not a digit run, and Unicode has
 *    no general run of subscript letters to fall back to the way superscript
 *    digits do — a real gap, left for whenever a reply is caught reaching
 *    for one.
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
    toSuperscript(
        toChemFormulas(
            toSubscript(
                toSymbols(toRoots(toFractions(stripLatexWrappers(text)))),
            ),
        ),
    )

private val MATHRM = Regex("""\\(?:mathrm|text)\{([^}]*)\}""")
private val OPEN_DELIM = Regex("""\\[(\[]\s*""")
private val CLOSE_DELIM = Regex("""\s*\\[)\]]""")

private fun stripLatexWrappers(text: String): String {
    val unwrapped = MATHRM.replace(text) { it.groupValues[1] }
    return CLOSE_DELIM.replace(OPEN_DELIM.replace(unwrapped, ""), "")
}

private val FRAC = Regex("""\\frac\{([^{}]*)\}\{([^{}]*)\}""")

private fun toFractions(text: String): String = FRAC.replace(text) { match ->
    "${parenthesizeIfCompound(match.groupValues[1])}/${parenthesizeIfCompound(match.groupValues[2])}"
}

/** `1` stays `1`; `a+b` becomes `(a+b)` — a bare term needs no help reading as one. */
private fun parenthesizeIfCompound(term: String): String =
    if (term.any { it in "+-*/ " }) "($term)" else term

private val SQRT = Regex("""\\sqrt\{([^{}]*)\}""")

private fun toRoots(text: String): String = SQRT.replace(text) { match -> "√(${match.groupValues[1]})" }

private val SYMBOL_COMMANDS = mapOf(
    "int" to "∫", "sum" to "∑", "lim" to "lim", "infty" to "∞",
    "rightarrow" to "→", "to" to "→",
    "pi" to "π", "theta" to "θ", "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "cdot" to "·", "times" to "×", "pm" to "±", "approx" to "≈",
    "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠",
)

// Not `\b`: a command's own closing boundary is "no more letters", not "no
// more word characters" — `\sum_{i=1}` has `_` right after the command name,
// and `_`/digits count as word characters too, so `\b` would refuse to match
// there and leave `\sum` untouched right when a subscript follows it.
private val SYMBOL_COMMAND = Regex("""\\(${SYMBOL_COMMANDS.keys.joinToString("|")})(?![a-zA-Z])""")

private fun toSymbols(text: String): String = SYMBOL_COMMAND.replace(text) { match ->
    SYMBOL_COMMANDS.getValue(match.groupValues[1])
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
