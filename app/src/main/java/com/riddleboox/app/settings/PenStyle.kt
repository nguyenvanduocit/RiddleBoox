package com.riddleboox.app.settings

import android.content.Context

/**
 * How the writer's own ink looks on the page — a preset, not raw curve
 * parameters, for the same reason [ReplyFontSize] is a preset: nobody wants
 * to hand-tune a pressure exponent to make a page look like it was written
 * with a pencil.
 *
 * [minRadiusPx]/[maxRadiusPx]/[pressureCurveExponent] feed
 * [com.riddleboox.app.ink.inkRadiusPx]; [alpha] is the ink's opacity
 * (0..255, e-ink has no color, only how dark a stroke reads); [hasTexture]
 * and [hasTapering] switch on [com.riddleboox.app.ink.textureJitter] and
 * [com.riddleboox.app.ink.taperMultiplier] for that stroke.
 *
 * [Ballpoint]'s curve (1.0..2.7px, exponent 1, opaque, no texture or
 * tapering) is the diary's original fixed ink — every writer who never opens
 * this setting keeps writing with exactly the pen they always had.
 */
enum class PenStyle(
    val label: String,
    val minRadiusPx: Float,
    val maxRadiusPx: Float,
    val pressureCurveExponent: Float,
    val alpha: Int,
    val hasTexture: Boolean,
    val hasTapering: Boolean,
) {
    Ballpoint("ballpoint pen", 1.0f, 2.7f, 1.0f, 255, hasTexture = false, hasTapering = false),
    FountainPen("fountain pen", 1.3f, 4.2f, 1.0f, 255, hasTexture = false, hasTapering = true),
    Pencil("pencil", 0.5f, 1.6f, 1.0f, 110, hasTexture = true, hasTapering = false),
    Brush("brush", 0.9f, 5.8f, 1.0f, 235, hasTexture = false, hasTapering = true),
    ;

    companion object {
        val Default = Ballpoint

        /** What was saved, or [Default] for nothing saved and for anything not one of these names. */
        fun fromStored(name: String?): PenStyle = entries.find { it.name == name } ?: Default
    }
}

private const val PREFS_FILE = "page_settings"
private const val KEY_PEN_STYLE = "pen_style"

/** Where the writer's choice of [PenStyle] lives between runs — same file as [ReplyFontSizeStore]. */
class PenStyleStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun read(): PenStyle = PenStyle.fromStored(prefs.getString(KEY_PEN_STYLE, null))

    fun write(style: PenStyle) {
        prefs.edit().putString(KEY_PEN_STYLE, style.name).apply()
    }
}
