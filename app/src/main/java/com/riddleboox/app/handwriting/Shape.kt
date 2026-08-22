package com.riddleboox.app.handwriting

/**
 * The small, fixed vocabulary of shapes a reply can draw instead of write —
 * see [ShapeAwareTextRaster]. Two to start, the two outline shapes Android's
 * `Canvas` draws without needing any path math of its own (`drawOval`,
 * `drawRect`) — a triangle or an arrow would need real geometry, and is a
 * real gap, left for whenever a reply is caught reaching for one.
 *
 * [token] is what has to arrive as one whole word from [WriteCursor]'s own
 * whitespace tokenizing for [fromToken] to recognise it — the same
 * constraint a chemistry formula's digits are under in
 * [com.riddleboox.app.reply.mathChemNotation].
 */
enum class Shape(val token: String) {
    Circle("[[circle]]"),
    Box("[[box]]"),
    ;

    companion object {
        fun fromToken(token: String): Shape? = entries.find { it.token == token }
    }
}
