package com.riddleboox.app.agent

/**
 * Capability ids persisted in an agent manifest and resolved into toolboxes at
 * runtime.
 *
 * Only what an agent opts into lives here. The default toolset — workspace,
 * memory, drawing — is injected for every agent unconditionally in
 * `MainActivity.agentToolbox` and is deliberately not a capability: a manifest
 * cannot turn it off, so an id for it would only be a second place for the
 * truth to live. Ids from older manifests that are no longer capabilities are
 * dropped by [normalize] on load.
 */
object AgentCapability {
    const val LIBRARY = "library"
    const val DILIB = "dilib"
    const val BOOX_NOTES = "boox_notes"
    const val AGENT_MANAGEMENT = "agent_management"

    val supported: Set<String> = setOf(LIBRARY, DILIB, BOOX_NOTES, AGENT_MANAGEMENT)

    /** Agent-management is intentionally reserved for the built-in manager. */
    fun normalize(requested: Set<String>, isBuiltInManager: Boolean): Set<String> =
        (requested + if (isBuiltInManager) setOf(AGENT_MANAGEMENT) else emptySet())
            .filter { it in supported }
            .filter { it != AGENT_MANAGEMENT || isBuiltInManager }
            .toSet()
}
