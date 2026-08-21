package com.riddleboox.app.handwriting

/** One pen sample in page space (pixels). */
data class WritePoint(val x: Float, val y: Float)

/** One continuous pen-down stroke, in the order it must be drawn. */
data class WriteStroke(val points: List<WritePoint>)

/**
 * A full page of handwriting, strokes ordered the way a hand would write them
 * (top line first, left to right inside each line).
 *
 * Port of `WritePlan` in references/Riddle/src/main.rs:861-892 minus the
 * animation cursor fields (`stroke_i` / `point_i`), which belong to the state
 * machine that consumes this plan rather than to the plan itself.
 */
data class WritePlan(val strokes: List<WriteStroke>)
