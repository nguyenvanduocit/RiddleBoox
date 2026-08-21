package com.riddleboox.app.ink

data class InkPoint(val x: Float, val y: Float, val pressure: Float)

class InkStroke(val points: MutableList<InkPoint> = mutableListOf())

/**
 * The page's ink as it is being written.
 *
 * Synchronized because the two ends of it are on different threads: the Onyx
 * SDK delivers raw pen callbacks on its own reading thread (see
 * `references/boox-riddle-diary/.../DiaryController.kt:135`), while the diary's
 * tick reads the page on the main one. Unguarded, a pen stroke finishing while
 * [com.riddleboox.app.riddle.RiddleStateMachine] is copying the page for the
 * model throws ConcurrentModificationException out of the tick.
 */
class StrokeStore {
    private val _strokes = mutableListOf<InkStroke>()
    private var current: InkStroke? = null

    /** A snapshot: the live list keeps growing under a pen that is still down. */
    val strokes: List<InkStroke> @Synchronized get() = _strokes.toList()

    val isEmpty: Boolean @Synchronized get() = _strokes.isEmpty()

    val count: Int @Synchronized get() = _strokes.size

    @Synchronized
    fun beginStroke(p: InkPoint) {
        current = InkStroke(mutableListOf(p))
    }

    @Synchronized
    fun addPoint(p: InkPoint) {
        current?.points?.add(p)
    }

    @Synchronized
    fun finishCurrent() {
        current?.let { _strokes.add(it) }
        current = null
    }

    /** Drops the stroke in progress too: a clear ends the page, pen down or not. */
    @Synchronized
    fun clear() {
        _strokes.clear()
        current = null
    }
}
