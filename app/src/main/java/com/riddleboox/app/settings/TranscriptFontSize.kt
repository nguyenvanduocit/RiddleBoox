package com.riddleboox.app.settings

import android.content.Context

/**
 * How big the printed transcript reads back — independent of [ReplyFontSize],
 * which sizes the diary's own hand in rasterized page pixels. This one is sp,
 * for a plain `TextView` on the read-back screen, not the writing page.
 */
enum class TranscriptFontSize(val sp: Float, val label: String) {
    Small(16f, "small"),
    Medium(19f, "medium — default"),
    Large(23f, "large"),
    ExtraLarge(27f, "extra large"),
    ;

    companion object {
        val Default = Medium

        /** What was saved, or [Default] for nothing saved and for anything not one of these names. */
        fun fromStored(name: String?): TranscriptFontSize = entries.find { it.name == name } ?: Default
    }
}

private const val PREFS_FILE = "transcript_settings"
private const val KEY_FONT_SIZE = "transcript_font_size"

/**
 * Where the reader's choice of [TranscriptFontSize] lives between runs.
 *
 * Its own preferences file and key, separate from [ReplyFontSizeStore]: the
 * two settings answer different questions and must not collide.
 */
class TranscriptFontSizeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun read(): TranscriptFontSize = TranscriptFontSize.fromStored(prefs.getString(KEY_FONT_SIZE, null))

    fun write(size: TranscriptFontSize) {
        prefs.edit().putString(KEY_FONT_SIZE, size.name).apply()
    }
}
