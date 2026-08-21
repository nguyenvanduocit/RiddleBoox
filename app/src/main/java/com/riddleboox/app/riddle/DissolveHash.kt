package com.riddleboox.app.riddle

/**
 * Deterministic per-pixel hash for the "diary drinks the ink" dissolve effect.
 * Port of `px_hash`, references/Riddle/src/ink.rs:150-157 (u32 wrapping arithmetic).
 *
 * Kotlin Int already wraps on overflow like Rust's u32 wrapping_mul, but its `shr`
 * sign-extends where Rust's u32 `>>` zero-fills — [ushr] is used here to match.
 */
private fun pxHash(x: Int, y: Int): Int {
    var h = (x * 0x9E3779B1.toInt()) xor (y * 0x85EBCA6B.toInt())
    h = h xor (h ushr 13)
    h *= 0xC2B2AE35.toInt()
    return h xor (h ushr 16)
}

/**
 * Whether the point at ([x], [y]) has dissolved by [stage] of [stages] total stages.
 * Port of the erase condition in `dissolve_pass`, ink.rs:167: `px_hash(x,y) % stages <= stage`.
 * The hash is reinterpreted as unsigned before the modulo, matching u32 semantics.
 */
fun isDissolvedAt(x: Int, y: Int, stage: Int, stages: Int): Boolean {
    val unsigned = pxHash(x, y).toLong() and 0xFFFFFFFFL
    return (unsigned % stages) <= stage
}
