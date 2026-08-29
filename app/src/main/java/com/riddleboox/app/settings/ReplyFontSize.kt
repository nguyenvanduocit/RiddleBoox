package com.riddleboox.app.settings

import android.content.Context

/**
 * How big the diary's own hand is on the page — a preset rather than a raw
 * number, because a hand-typed pixel value on a stylus tablet is how a
 * working setup turns into unreadable ink or a page that holds three words.
 */
enum class ReplyFontSize(val px: Float, val label: String) {
    Small(48f, "small"),
    Medium(64f, "medium — default"),
    Large(80f, "large"),
    ExtraLarge(96f, "extra large"),
    ;

    companion object {
        val Default = Medium

        /** What was saved, or [Default] for nothing saved and for anything not one of these names. */
        fun fromStored(name: String?): ReplyFontSize = entries.find { it.name == name } ?: Default
    }
}

private const val PREFS_FILE = "page_settings"
private const val KEY_FONT_SIZE = "reply_font_size"

/**
 * Where the writer's choice of [ReplyFontSize] lives between runs.
 *
 * Plain preferences, like [SendModeStore]: nothing here is a credential.
 */
class ReplyFontSizeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun read(): ReplyFontSize = ReplyFontSize.fromStored(prefs.getString(KEY_FONT_SIZE, null))

    fun write(size: ReplyFontSize) {
        prefs.edit().putString(KEY_FONT_SIZE, size.name).apply()
    }
}
