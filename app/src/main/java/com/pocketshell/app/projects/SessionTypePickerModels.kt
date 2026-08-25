package com.pocketshell.app.projects

import com.pocketshell.uikit.model.SessionAgentKind

/** The only rows that may be offered by a create-session picker. */
internal fun availableEnginesForCreate(engines: List<RemoteEngine>): List<RemoteEngine> =
    engines.filter { it.availableForCreate && it.enabled && it.available }

/** One profile row after projecting the existing family-specific discoveries. */
internal data class PickerProfile(
    val name: String,
    val default: Boolean,
)

/**
 * Profiles remain discovered through the existing Claude/Codex APIs. The
 * registry supplies the selected engine id; its closed family only chooses
 * which already-published profile StateFlow can be used.
 */
internal fun pickerProfilesForEngine(
    engine: RemoteEngine?,
    claudeProfiles: List<ClaudeProfile>,
    codexProfiles: List<CodexProfile>,
): List<PickerProfile> = when (engine?.family) {
    SessionAgentKind.Claude -> claudeProfiles.map { PickerProfile(it.name, it.default) }
    SessionAgentKind.Codex -> codexProfiles.map { PickerProfile(it.name, it.default) }
    else -> emptyList()
}

/** Stable semantics tag for a registry row; the id is deliberately open-ended. */
fun sessionTypePickerAgentEngineTag(engineId: String): String =
    "$SESSION_TYPE_PICKER_AGENT_ENGINE_TAG_PREFIX$engineId"
