package com.riddleboox.app.settings

import android.content.Context

/**
 * How thick the writer's own ink is, independent of [PenStyle] — a scale
 * factor applied on top of whichever style's curve is active, the same way
 * turning up the pressure on a real pen thickens a ballpoint's line and a
 * brush's line alike without changing which pen it is.
 */
enum class PenStrokeWidth(val scale: Float, val label: String) {
    Thin(0.7f, "thin"),
    Medium(1.0f, "medium — default"),
    Thick(1.4f, "thick"),
    ;

    companion object {
        val Default = Medium

        /** What was saved, or [Default] for nothing saved and for anything not one of these names. */
        fun fromStored(name: String?): PenStrokeWidth = entries.find { it.name == name } ?: Default
    }
}

private const val PREFS_FILE = "page_settings"
private const val KEY_PEN_WIDTH = "pen_stroke_width"

/** Where the writer's choice of [PenStrokeWidth] lives between runs — same file as [ReplyFontSizeStore]. */
class PenStrokeWidthStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun read(): PenStrokeWidth = PenStrokeWidth.fromStored(prefs.getString(KEY_PEN_WIDTH, null))

    fun write(width: PenStrokeWidth) {
        prefs.edit().putString(KEY_PEN_WIDTH, width.name).apply()
    }
}
