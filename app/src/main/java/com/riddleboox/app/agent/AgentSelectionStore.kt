package com.riddleboox.app.agent

import android.content.Context

/** The selected persona is a small preference, not part of an agent's data. */
class AgentSelectionStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(defaultId: String = "chat"): String = prefs.getString(KEY_SELECTED, defaultId) ?: defaultId

    fun write(id: String) {
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    private companion object {
        const val FILE = "agent_selection"
        const val KEY_SELECTED = "selected_agent"
    }
}
