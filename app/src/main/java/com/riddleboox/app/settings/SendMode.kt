package com.riddleboox.app.settings

import android.content.Context

/**
 * Who decides that a page is finished.
 *
 * [Auto] is the diary reading the pause: the pen goes quiet for a moment and
 * the page is taken. It suits a line or two and gets in the way of anything
 * longer — a pause to think mid-page is not the writer being done. [Manual]
 * leaves the moment to the writer, who says so on the page's own chrome.
 */
enum class SendMode(val label: String) {
    Auto("tự động"),
    Manual("thủ công"),
}

private const val PREFS_FILE = "page_settings"
private const val KEY_SEND_MODE = "send_mode"

/**
 * Where the writer's choice of [SendMode] lives between runs.
 *
 * Plain preferences, unlike [SettingsStore]: nothing here is a credential, and
 * paying the Keystore for a single enum would make reading it on the way to
 * first paint cost more than the page it configures.
 */
class SendModeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** [SendMode.Auto] until the writer says otherwise: it is how the diary has always opened. */
    fun read(): SendMode = when (prefs.getString(KEY_SEND_MODE, null)) {
        SendMode.Manual.name -> SendMode.Manual
        else -> SendMode.Auto
    }

    fun write(mode: SendMode) {
        prefs.edit().putString(KEY_SEND_MODE, mode.name).apply()
    }
}
