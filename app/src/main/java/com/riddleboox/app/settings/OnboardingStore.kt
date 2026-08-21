package com.riddleboox.app.settings

import android.content.Context

/** Đã xem hết phần giới thiệu lần mở app đầu tiên chưa — cùng pattern [SendModeStore]. */
class OnboardingStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(): Boolean = prefs.getBoolean(KEY_SEEN, false)

    fun write(seen: Boolean) {
        prefs.edit().putBoolean(KEY_SEEN, seen).apply()
    }

    private companion object {
        const val FILE = "onboarding"
        const val KEY_SEEN = "seen"
    }
}
