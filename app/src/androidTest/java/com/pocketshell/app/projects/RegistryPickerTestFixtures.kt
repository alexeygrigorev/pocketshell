package com.pocketshell.app.projects

import com.pocketshell.uikit.model.SessionAgentKind

/** Registry rows used by picker UI/connected launch tests. */
internal fun pickerTestEngine(
    id: String,
    family: SessionAgentKind,
    label: String = id,
    supportsSkipPermissions: Boolean = family != SessionAgentKind.OpenCode,
): RemoteEngine = RemoteEngine(
    id = id,
    familyId = family.name.lowercase(),
    family = family,
    label = label,
    launch = RemoteEngineLaunch(
        supportsSkipPermissions = supportsSkipPermissions,
    ),
)

internal val pickerTestEngines: List<RemoteEngine>
    get() = listOf(
        pickerTestEngine("claude", SessionAgentKind.Claude, "Claude"),
        pickerTestEngine("codex", SessionAgentKind.Codex, "Codex"),
        pickerTestEngine("opencode", SessionAgentKind.OpenCode, "OpenCode"),
        pickerTestEngine("grok", SessionAgentKind.Grok, "Grok"),
    )
