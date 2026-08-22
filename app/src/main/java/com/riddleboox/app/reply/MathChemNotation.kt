package com.riddleboox.app.reply

/**
 * Chemical formulas and math expressions, as a hand would actually write
 * them.
 *
 * Verbatim from the device: asked to repeat a formula, the model reached for
 * real inline LaTeX — `\( \mathrm{H_2O} \)`, `\( x^2 \)` — not bare `H2O`.
 * Left alone the pen writes every backslash and brace it is given, the same
 * way it would write raw `**markdown**` if [plainText] didn't strip it. A
 * model that skips LaTeX and just writes `H2O` is covered too, by
 * [toChemFormulas] below; the two shapes don't overlap, so both run.
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
 * [singlePass] runs to a fixed point rather than once: asked for the
 * quadratic formula, a real reply came back with `\frac{-b+\sqrt{\Delta}}{2a}`
 * — a fraction whose numerator itself holds a brace-delimited command. No
 * single regex here allows nested braces (a fraction's own numerator or
 * denominator excludes `{`/`}` on purpose, so it cannot straddle a command
 * that isn't finished yet), so the first pass converts only the innermost
 * piece — `\sqrt{\Delta}` becomes `√(Δ)`, which uses parens, not braces —
 * and leaves the fraction alone. The second pass then finds a fraction whose
 * numerator has no braces left in it at all, and converts that. Each pass
 * resolves one more level of nesting; running until nothing changes (or
 * [MAX_PASSES] is spent) resolves however deep a reply actually nested
 * things, without this file needing to parse balanced braces itself.
 *
 * Deliberately narrow, the same restraint [plainText] documents for itself —
 * only the shapes that have actually turned up in a reply are handled:
 *
 *  - **LaTeX wrapping** ([stripLatexWrappers]): `\(...\)`, `\[...\]` and
 *    `\left`/`\right` delimiters are dropped (the size a delimiter would
 *    have grown to means nothing on a line of ink), `\mathrm{...}` and
 *    `\text{...}` keep their content and lose the command.
 *  - **Fractions and roots** ([toFractions], [toRoots]): `\frac{1}{3}`
 *    becomes `1/3`, `\sqrt{2}` becomes `√(2)`, `\sqrt[3]{x}` becomes `³√(x)`
 *    — a hand has no way to stack a fraction or draw a radical's vinculum on
 *    a line of ink, so all three fall back to the way they'd be said out
 *    loud rather than the way they'd be typeset. The parens around a root's
 *    argument are the same reasoning `(a+b)/c` would need for a fraction
 *    with an expression on top: without them the line after the root reads
 *    as outside it.
 *  - **Symbol and function commands** ([toSymbols]): the zero-argument
 *    names that turn up asking about calculus, algebra or a set — `\int`,
 *    `\sum`, `\Delta`, `\in`, `\sin` and the rest of [SYMBOL_COMMANDS] —
 *    each is one Unicode character or one bare word LaTeX would have
 *    typeset it as anyway.
 *  - **Explicit subscript/superscript** ([toSubscript], [toSuperscript]):
 *    `_2`, `_{12}` and `^2`, `^{-3}` — LaTeX's own markers. Digits (and a
 *    sign) only: `\lim_{x \to 0}`'s subscript is a whole sub-expression, not
 *    a digit run, and Unicode has no general run of subscript letters to
 *    fall back to the way superscript digits do — a real gap, left for
 *    whenever a reply is caught reaching for one.
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
fun mathChemNotation(text: String): String {
    var current = text
    repeat(MAX_PASSES) {
        val next = singlePass(current)
        if (next == current) return next
        current = next
    }
    return current
}

/** However many levels of `\frac{\sqrt{...}}{...}`-style nesting a reply is caught with — see [mathChemNotation]. */
private const val MAX_PASSES = 6

private fun singlePass(text: String): String =
    toSuperscript(
        toChemFormulas(
            toSubscript(
                toSymbols(toRoots(toFractions(stripLatexWrappers(text)))),
            ),
        ),
    )

private val MATHRM = Regex("""\\(?:mathrm|text)\{([^{}]*)\}""")

// (?![a-zA-Z]), not a bare match: without it this also matches the "\right"
// that opens "\rightarrow", stripping it down to a bare "arrow" before
// toSymbols ever gets a chance to convert the whole command to →.
private val LEFT_RIGHT = Regex("""\\(?:left|right)(?![a-zA-Z])""")
private val OPEN_DELIM = Regex("""\\[(\[]\s*""")
private val CLOSE_DELIM = Regex("""\s*\\[)\]]""")

private fun stripLatexWrappers(text: String): String {
    val unwrapped = MATHRM.replace(text) { it.groupValues[1] }
    val sized = LEFT_RIGHT.replace(unwrapped, "")
    return CLOSE_DELIM.replace(OPEN_DELIM.replace(sized, ""), "")
}

private val FRAC = Regex("""\\frac\{([^{}]*)\}\{([^{}]*)\}""")

private fun toFractions(text: String): String = FRAC.replace(text) { match ->
    "${parenthesizeIfCompound(match.groupValues[1])}/${parenthesizeIfCompound(match.groupValues[2])}"
}

/** `1` stays `1`; `a+b` becomes `(a+b)` — a bare term needs no help reading as one. */
private fun parenthesizeIfCompound(term: String): String =
    if (term.any { it in "+-*/ " }) "($term)" else term

private val SQRT = Regex("""\\sqrt\{([^{}]*)\}""")
private val NTH_ROOT = Regex("""\\sqrt\[(\d+)\]\{([^{}]*)\}""")

private fun toRoots(text: String): String {
    // Matched before the plain \sqrt below: \sqrt[3]{x} would otherwise lose
    // its [3] to nothing (\sqrt's own regex doesn't expect a `[...]` between
    // the command and its brace) and read as a square, not a cube, root.
    val nth = NTH_ROOT.replace(text) { match ->
        val index = match.groupValues[1].map { SUPERSCRIPT_CHARS[it] ?: it }.joinToString("")
        "$index√(${match.groupValues[2]})"
    }
    return SQRT.replace(nth) { match -> "√(${match.groupValues[1]})" }
}

private val SYMBOL_COMMANDS = mapOf(
    // Calculus and analysis.
    "int" to "∫", "sum" to "∑", "prod" to "∏", "lim" to "lim", "infty" to "∞",
    "partial" to "∂", "nabla" to "∇",
    // Arrows and relations.
    "rightarrow" to "→", "to" to "→", "leftarrow" to "←",
    "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "leftrightarrow" to "↔",
    "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
    "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝", "cong" to "≅",
    // Set theory and logic.
    "forall" to "∀", "exists" to "∃", "in" to "∈", "notin" to "∉",
    "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "supseteq" to "⊇",
    "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "setminus" to "∖",
    "perp" to "⊥", "parallel" to "∥", "angle" to "∠",
    // Operators and misc symbols.
    "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
    "cdots" to "⋯", "ldots" to "…", "vdots" to "⋮", "ddots" to "⋱",
    // Function names — upright words once the backslash is gone, not symbols.
    "sin" to "sin", "cos" to "cos", "tan" to "tan", "cot" to "cot", "sec" to "sec", "csc" to "csc",
    "arcsin" to "arcsin", "arccos" to "arccos", "arctan" to "arctan",
    "sinh" to "sinh", "cosh" to "cosh", "tanh" to "tanh",
    "log" to "log", "ln" to "ln", "exp" to "exp",
    "det" to "det", "gcd" to "gcd", "min" to "min", "max" to "max",
    // Lowercase Greek.
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
    "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
    "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
    "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    // Uppercase Greek — only the letters that actually differ in shape from
    // their lowercase form (\Alpha, \Beta etc. look identical to plain
    // Latin A, B and are not real LaTeX commands).
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
    "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
)

// Longest names first: alternation tries left to right, and without this a
// short prefix that is also its own valid command (\le inside \leq, \in
// inside \infty) could win before the longer, correct one is ever tried.
// (In practice the trailing (?![a-zA-Z]) below forces a backtrack-and-retry
// that reaches the right one regardless — see its own comment — but sorting
// means the common case matches on the first attempt instead of a retry.)
private val SYMBOL_COMMAND = Regex(
    """\\(${SYMBOL_COMMANDS.keys.sortedByDescending { it.length }.joinToString("|")})(?![a-zA-Z])""",
)

// Not `\b`: a command's own closing boundary is "no more letters", not "no
// more word characters" — `\sum_{i=1}` has `_` right after the command name,
// and `_`/digits count as word characters too, so `\b` would refuse to match
// there and leave `\sum` untouched right when a subscript follows it.
private fun toSymbols(text: String): String = SYMBOL_COMMAND.replace(text) { match ->
    SYMBOL_COMMANDS.getValue(match.groupValues[1])
}

private val SUBSCRIPT_MARK = Regex("""_(?:\{([+-]?\d+)\}|([+-]?\d))""")
private val EXPONENT = Regex("""\^(?:\{([+-]?\d+)\}|([+-]?\d+))""")

private val SUBSCRIPT_DIGITS = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋',
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
