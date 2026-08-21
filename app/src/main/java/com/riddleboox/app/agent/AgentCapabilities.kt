package com.riddleboox.app.agent

/** Capability ids persisted in an agent manifest and resolved into toolboxes at runtime. */
object AgentCapability {
    const val WORKSPACE = "workspace"
    const val LIBRARY = "library"
    const val DILIB = "dilib"
    const val BOOX_NOTES = "boox_notes"
    const val AGENT_MANAGEMENT = "agent_management"

    val supported: Set<String> = setOf(WORKSPACE, LIBRARY, DILIB, BOOX_NOTES, AGENT_MANAGEMENT)

    /** Agent-management is intentionally reserved for the built-in manager. */
    fun normalize(requested: Set<String>, isBuiltInManager: Boolean): Set<String> =
        (requested + WORKSPACE + if (isBuiltInManager) AGENT_MANAGEMENT else "")
            .filter { it in supported }
            .filter { it != AGENT_MANAGEMENT || isBuiltInManager }
            .toSet()
}
