package com.pocketshell.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.app.sessions.StartDirectoryAutocompleteController
import com.pocketshell.app.sessions.StartDirectoryAutocompleteField
import com.pocketshell.app.sessions.rememberStartDirectoryAutocompleteController
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Picker for "new session" type — issue #171 round 2.
 *
 * The maintainer's refinement comment requires that "+ New session"
 * prompts the user for the SESSION TYPE (agent vs shell) and, when
 * "Agent" is chosen, a sub-picker for the agent CLI
 * (`claude` / `codex` / `opencode`). The folder is the explicit `cwd`
 * for the new session.
 *
 * The sheet is presented from [FolderListScreen] when the user taps the
 * FAB or an empty-folder row. Confirming the sheet fires
 * [onCreate] with the chosen kind + cwd + (optional) agent CLI; the
 * caller routes to `AppDestination.TmuxSession` with the right
 * `startDirectory` and (for agent sessions) a `startCommand` that the
 * tmux create path invokes via `send-keys` so the agent CLI runs as
 * the first command in the new pane.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionTypePickerSheet(
    folderPath: String,
    folderLabel: String,
    onDismiss: () -> Unit,
    onCreate: (choice: SessionTypeChoice) -> Unit,
    suggestStartDirectories: (suspend (String) -> List<String>)? = null,
    claudeProfiles: List<ClaudeProfile> = emptyList(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val autocompleteController = rememberStartDirectoryAutocompleteController(suggestStartDirectories)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        modifier = Modifier.testTag(SESSION_TYPE_PICKER_SHEET_TAG),
    ) {
        SessionTypePickerContent(
            folderPath = folderPath,
            folderLabel = folderLabel,
            onCancel = onDismiss,
            onCreate = onCreate,
            autocompleteController = autocompleteController,
            claudeProfiles = claudeProfiles,
        )
    }
}

/**
 * Pure content of the sheet — split out from the [ModalBottomSheet]
 * wrapper so Compose tests can drive the body without paying for the
 * sheet animation harness.
 */
@Composable
internal fun SessionTypePickerContent(
    folderPath: String,
    folderLabel: String,
    onCancel: () -> Unit,
    onCreate: (choice: SessionTypeChoice) -> Unit,
    autocompleteController: StartDirectoryAutocompleteController? = null,
    claudeProfiles: List<ClaudeProfile> = emptyList(),
) {
    var sessionType by remember { mutableStateOf(SessionType.Agent) }
    var agentKind by remember { mutableStateOf(AgentCli.Claude) }
    // Issue #428: default ON — the maintainer almost always wants the
    // agent launched without per-action approval prompts.
    var skipPermissions by remember { mutableStateOf(true) }
    var startDirectory by remember { mutableStateOf(folderPath) }
    // Issue #627: selected Claude profile. null = default (no config dir override).
    var claudeProfile by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .fillMaxHeight(SESSION_TYPE_PICKER_HEIGHT_FRACTION)
            .heightIn(max = SESSION_TYPE_PICKER_MAX_HEIGHT)
            .testTag(SESSION_TYPE_PICKER_CONTENT_TAG),
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .verticalScroll(scrollState)
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(top = PocketShellSpacing.lg, bottom = PocketShellSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        ) {
            Text(
                text = "New session",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "in $folderLabel",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyMono,
            )

            // Start folder — pre-filled, editable. Keep this inside the
            // scrollable sheet body so the autocomplete can request enough
            // space above the IME while the action row remains pinned.
            Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                SectionHeader(label = "Start folder")
                StartDirectoryAutocompleteField(
                    value = startDirectory,
                    onValueChange = { startDirectory = it },
                    modifier = Modifier
                        .fillMaxWidth(),
                    textFieldTestTag = SESSION_TYPE_PICKER_CWD_TAG,
                    autocompleteController = autocompleteController,
                    suggestionsMaxHeight = SESSION_TYPE_PICKER_SUGGESTIONS_MAX_HEIGHT,
                )
            }

            // Segmented control: Shell vs Agent.
            Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                SectionHeader(label = "Session type")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PICKER_SEGMENT_HEIGHT)
                        .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
                        .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
                        .selectableGroup(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SegmentButton(
                        label = "Shell",
                        selected = sessionType == SessionType.Shell,
                        onClick = { sessionType = SessionType.Shell },
                        testTag = SESSION_TYPE_PICKER_SHELL_TAG,
                        modifier = Modifier.weight(1f),
                    )
                    SegmentButton(
                        label = "Agent",
                        selected = sessionType == SessionType.Agent,
                        onClick = { sessionType = SessionType.Agent },
                        testTag = SESSION_TYPE_PICKER_AGENT_TAG,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Conditional agent CLI sub-picker.
            if (sessionType == SessionType.Agent) {
                Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                    SectionHeader(label = "Agent CLI")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PICKER_SEGMENT_HEIGHT)
                            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
                            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
                            .selectableGroup(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SegmentButton(
                            label = "claude",
                            selected = agentKind == AgentCli.Claude,
                            onClick = { agentKind = AgentCli.Claude },
                            testTag = SESSION_TYPE_PICKER_AGENT_CLAUDE_TAG,
                            modifier = Modifier.weight(1f),
                        )
                        SegmentButton(
                            label = "codex",
                            selected = agentKind == AgentCli.Codex,
                            onClick = { agentKind = AgentCli.Codex },
                            testTag = SESSION_TYPE_PICKER_AGENT_CODEX_TAG,
                            modifier = Modifier.weight(1f),
                        )
                        SegmentButton(
                            label = "opencode",
                            selected = agentKind == AgentCli.OpenCode,
                            onClick = { agentKind = AgentCli.OpenCode },
                            testTag = SESSION_TYPE_PICKER_AGENT_OPENCODE_TAG,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = "The CLI will auto-start in the new pane.",
                        color = PocketShellColors.TextMuted,
                        style = PocketShellType.labelMono,
                    )

                    // Skip-permissions toggle (issue #428). Hidden for
                    // OpenCode: its per-action permissions are config-driven
                    // in opencode.json, not a CLI flag, so the checkbox would
                    // be a no-op there. OpenCode is always launched
                    // env-stripped (subscription auth) regardless.
                    if (agentKind != AgentCli.OpenCode) {
                        SkipPermissionsRow(
                            checked = skipPermissions,
                            onToggle = { skipPermissions = !skipPermissions },
                        )
                    }

                    // Issue #627: Claude Code profile selector. Shown only
                    // when Claude is selected AND the host has more than one
                    // profile configured (default + at least one custom).
                    if (agentKind == AgentCli.Claude && claudeProfiles.size > 1) {
                        Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                            SectionHeader(label = "Profile")
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
                                    .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
                                    .selectableGroup()
                                    .testTag(SESSION_TYPE_PICKER_CLAUDE_PROFILE_TAG),
                            ) {
                                for (profile in claudeProfiles) {
                                    SegmentButton(
                                        label = profile.name,
                                        selected = claudeProfile == profile.name,
                                        onClick = { claudeProfile = profile.name },
                                        testTag = "$SESSION_TYPE_PICKER_CLAUDE_PROFILE_TAG:${profile.name}",
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.Surface)
                .border(width = 1.dp, color = PocketShellColors.BorderSoft)
                .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.md),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag(SESSION_TYPE_PICKER_CANCEL_TAG),
            ) {
                Text("Cancel", color = PocketShellColors.TextSecondary)
            }
            Spacer(modifier = Modifier.padding(end = 8.dp))
            Button(
                onClick = {
                    onCreate(
                        SessionTypeChoice(
                            type = sessionType,
                            agent = if (sessionType == SessionType.Agent) agentKind else null,
                            startDirectory = startDirectory.trim().ifBlank { folderPath },
                            skipPermissions = skipPermissions,
                            claudeProfileName = if (sessionType == SessionType.Agent && agentKind == AgentCli.Claude) {
                                claudeProfile
                            } else {
                                null
                            },
                        ),
                    )
                },
                enabled = startDirectory.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PocketShellColors.Accent,
                    contentColor = PocketShellColors.OnAccent,
                ),
                modifier = Modifier.testTag(SESSION_TYPE_PICKER_CREATE_TAG),
            ) {
                Text("Create")
            }
        }
    }
}

@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) PocketShellColors.AccentSoft else PocketShellColors.SurfaceElev
    val fg = if (selected) PocketShellColors.Accent else PocketShellColors.TextSecondary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PICKER_SEGMENT_HEIGHT)
            .padding(PocketShellSpacing.xs / 2)
            .background(bg, PocketShellShapes.small)
            .clickable(role = Role.Tab, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            style = PocketShellType.bodyDense,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SkipPermissionsRow(
    checked: Boolean,
    onToggle: () -> Unit,
) {
    ListRow(
        title = "Skip permissions",
        subtitle = "No per-action approval prompts.",
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SESSION_TYPE_PICKER_SKIP_PERMISSIONS_TAG),
        leading = {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = PocketShellColors.Accent,
                    uncheckedColor = PocketShellColors.TextSecondary,
                ),
            )
        },
        onClick = onToggle,
    )
}

/** What the picker emits when the user confirms. */
data class SessionTypeChoice(
    val type: SessionType,
    val agent: AgentCli?,
    val startDirectory: String,
    /**
     * Whether the agent CLI should launch with its per-action approval
     * prompts disabled (the maintainer's `csp` / `cy` aliases — issue
     * #428). Default `true`: the maintainer almost always wants the
     * agent to run without stopping for permission prompts. Ignored for
     * shell sessions and for OpenCode (whose per-action permissions are
     * config-driven in `opencode.json`, not a CLI flag).
     */
    val skipPermissions: Boolean = true,
    /**
     * The selected Claude Code profile name (issue #627). `null` means
     * the default profile (no `CLAUDE_CONFIG_DIR` override). Only
     * relevant when [agent] is [AgentCli.Claude]; ignored for other
     * agents and for shell sessions.
     */
    val claudeProfileName: String? = null,
) {
    /**
     * The start command to invoke inside the new tmux pane after
     * creation. `null` for plain shell sessions (which need no extra
     * command beyond the user's default shell). For agents it is the
     * full, self-contained invocation PocketShell types into the pane
     * (issue #428) — including the skip-permissions flag and, for
     * OpenCode, the provider-API-key env strip — so it does not depend
     * on the maintainer's shell aliases being sourced on the remote host.
     *
     * For Claude Code (issue #627), the command is env-stripped (same 71
     * provider API-key vars as OpenCode) and optionally prefixed with
     * `CLAUDE_CONFIG_DIR=<path>` when a non-default profile is selected.
     */
    fun startCommand(profiles: List<ClaudeProfile> = emptyList()): String? = when (type) {
        SessionType.Shell -> null
        SessionType.Agent -> agent?.launchCommand(skipPermissions, claudeProfileName, profiles)
    }
}

enum class SessionType { Shell, Agent }

enum class AgentCli(val command: String) {
    Claude("claude"),
    Codex("codex"),
    OpenCode("opencode"),
    ;

    /**
     * Build the full launch command typed into the new pane (issue
     * #428). PocketShell emits the explicit command itself (approach (b)
     * in the issue) rather than invoking a remote shell alias, so the
     * behaviour is identical on any host regardless of whether the
     * maintainer's dotfiles are sourced on the remote host.
     *
     * - Claude (alias `csp`): env-stripped (issue #627, same 71 provider
     *   API-key vars as OpenCode) and optionally `CLAUDE_CONFIG_DIR=<path>`
     *   when a non-default profile is selected. `--dangerously-skip-permissions`
     *   when [skipPermissions].
     * - Codex (alias `cy`): `codex --dangerously-bypass-approvals-and-sandbox`
     *   when [skipPermissions], else bare `codex`.
     * - OpenCode (function `oc`): ALWAYS env-stripped. [skipPermissions]
     *   is a no-op for OpenCode because its per-action permissions are
     *   config-driven in `opencode.json`, not a CLI flag. The env strip
     *   is about *billing*, not permissions: unsetting the provider
     *   API-key vars forces OpenCode onto the maintainer's subscription
     *   auth instead of a per-token env key.
     */
    fun launchCommand(
        skipPermissions: Boolean,
        claudeProfileName: String? = null,
        profiles: List<ClaudeProfile> = emptyList(),
    ): String = when (this) {
        Claude -> claudeLaunchCommand(skipPermissions, claudeProfileName, profiles)
        Codex -> if (skipPermissions) "codex --dangerously-bypass-approvals-and-sandbox" else "codex"
        OpenCode -> openCodeLaunchCommand()
    }

    companion object {
        /**
         * Provider API-key environment variables unset before launching
         * OpenCode (issue #428), mirroring the maintainer's `oc` shell
         * function. With these unset, OpenCode falls back to the
         * maintainer's *subscription* auth instead of an env API key,
         * which would otherwise bill per token.
         *
         * CANONICAL SOURCE: the maintainer's dotfiles at
         * `config/opencode/env_unset.txt` (installed as
         * `~/git/.claude/config/opencode/env_unset.txt`). This list is a
         * verbatim copy of that file (71 entries). When the dotfiles list
         * changes, update this copy to match. Keeping the list here lets
         * PocketShell be self-contained — it does not require the `oc`
         * function or `env_unset.txt` to be present on the remote host.
         */
        val OPENCODE_ENV_UNSET_VARS: List<String> = listOf(
            "AWS_ACCESS_KEY_ID",
            "AWS_SECRET_ACCESS_KEY",
            "AWS_SESSION_TOKEN",
            "AWS_PROFILE",
            "AWS_REGION",
            "AWS_BEARER_TOKEN_BEDROCK",
            "AWS_WEB_IDENTITY_TOKEN_FILE",
            "AWS_ROLE_ARN",
            "OPENAI_API_KEY",
            "OPENAI_BASE_URL",
            "OPENAI_ORG_ID",
            "OPENAI_PROJECT_ID",
            "ANTHROPIC_API_KEY",
            "ANTHROPIC_BASE_URL",
            "ANTHROPIC_AUTH_TOKEN",
            "GROQ_API_KEY",
            "GOOGLE_APPLICATION_CREDENTIALS",
            "GOOGLE_CLOUD_PROJECT",
            "GOOGLE_API_KEY",
            "VERTEX_LOCATION",
            "VERTEX_AI_PROJECT",
            "DEEPSEEK_API_KEY",
            "XAI_API_KEY",
            "FIREWORKS_API_KEY",
            "CEREBRAS_API_KEY",
            "OPENROUTER_API_KEY",
            "TOGETHER_API_KEY",
            "TOGETHER_AI_API_KEY",
            "AZURE_API_KEY",
            "AZURE_RESOURCE_NAME",
            "AZURE_COGNITIVE_SERVICES_RESOURCE_NAME",
            "AZURE_OPENAI_API_KEY",
            "AZURE_OPENAI_ENDPOINT",
            "CLOUDFLARE_API_TOKEN",
            "CLOUDFLARE_ACCOUNT_ID",
            "CLOUDFLARE_GATEWAY_ID",
            "CLOUDFLARE_API_KEY",
            "HUGGING_FACE_API_KEY",
            "HF_TOKEN",
            "HF_API_TOKEN",
            "MOONSHOT_API_KEY",
            "MOONSHOTAI_API_KEY",
            "MINIMAX_API_KEY",
            "NEBIUS_API_KEY",
            "DEEPINFRA_API_KEY",
            "BASETEN_API_KEY",
            "VENICE_API_KEY",
            "SCALEWAY_API_KEY",
            "OVH_API_KEY",
            "CORTECS_API_KEY",
            "IONET_API_KEY",
            "VERCEL_API_KEY",
            "ZENMUX_API_KEY",
            "ZAI_API_KEY",
            "HELICONE_API_KEY",
            "OPENCODE_API_KEY",
            "OPENCODE_ZEN_API_KEY",
            "GITLAB_TOKEN",
            "GITLAB_INSTANCE_URL",
            "GITLAB_AI_GATEWAY_URL",
            "GITLAB_OAUTH_CLIENT_ID",
            "AICORE_SERVICE_KEY",
            "AICORE_DEPLOYMENT_ID",
            "AICORE_RESOURCE_GROUP",
            "OPENAI_COMPATIBLE_API_KEY",
            "LMSTUDIO_API_KEY",
            "OLLAMA_API_KEY",
            "302AI_API_KEY",
            "FIRMWARE_API_KEY",
            "2AI_API_KEY",
            "GEMINI_API_KEY",
        )

        /**
         * `env -u VAR1 -u VAR2 ... opencode` — the env-stripped OpenCode
         * launch (issue #428). The variable names are validated to be
         * plain env identifiers, so no shell quoting is required; the
         * whole command is still single-quoted again when the gateway
         * passes it to `tmux send-keys`.
         */
        internal fun openCodeLaunchCommand(): String {
            val unsetArgs = OPENCODE_ENV_UNSET_VARS.joinToString(" ") { name ->
                require(name.matches(ENV_VAR_NAME_REGEX)) {
                    "Refusing to build OpenCode env strip: invalid env var name '$name'"
                }
                "-u $name"
            }
            return "env $unsetArgs opencode"
        }

        /**
         * Env-stripped Claude Code launch (issue #627). Same 71 provider
         * API-key vars as OpenCode, plus optional `CLAUDE_CONFIG_DIR=<path>`
         * when a non-default profile is selected. The env strip ensures
         * Claude Code uses its own credentials from the config directory,
         * not leaked host env vars.
         */
        internal fun claudeLaunchCommand(
            skipPermissions: Boolean,
            profileName: String? = null,
            profiles: List<ClaudeProfile> = emptyList(),
        ): String {
            val unsetArgs = OPENCODE_ENV_UNSET_VARS.joinToString(" ") { name ->
                require(name.matches(ENV_VAR_NAME_REGEX)) {
                    "Refusing to build Claude env strip: invalid env var name '$name'"
                }
                "-u $name"
            }
            val configDir = profiles.firstOrNull { it.name == profileName }?.configDir
                ?.trim()?.takeIf { it.isNotBlank() }
            val flag = if (skipPermissions) " --dangerously-skip-permissions" else ""
            return if (configDir != null) {
                // Shell-quote the config dir path to prevent injection.
                val quotedDir = "'${configDir.replace("'", "'\\''")}'"
                "env $unsetArgs CLAUDE_CONFIG_DIR=$quotedDir claude$flag"
            } else {
                "env $unsetArgs claude$flag"
            }
        }

        // Env var names per POSIX: letters, digits, underscore; we also
        // permit a leading digit because the maintainer's list includes
        // `302AI_API_KEY` and `2AI_API_KEY`.
        private val ENV_VAR_NAME_REGEX = Regex("^[A-Za-z0-9_]+$")
    }
}

private val PICKER_SEGMENT_HEIGHT = 48.dp
private const val SESSION_TYPE_PICKER_HEIGHT_FRACTION = 0.85f
private val SESSION_TYPE_PICKER_MAX_HEIGHT = 560.dp
private val SESSION_TYPE_PICKER_SUGGESTIONS_MAX_HEIGHT = 96.dp

// Test tags exposed for unit / connected tests.
const val SESSION_TYPE_PICKER_SHEET_TAG: String = "session-type-picker:sheet"
const val SESSION_TYPE_PICKER_CONTENT_TAG: String = "session-type-picker:content"
const val SESSION_TYPE_PICKER_SHELL_TAG: String = "session-type-picker:shell"
const val SESSION_TYPE_PICKER_AGENT_TAG: String = "session-type-picker:agent"
const val SESSION_TYPE_PICKER_AGENT_CLAUDE_TAG: String = "session-type-picker:agent:claude"
const val SESSION_TYPE_PICKER_AGENT_CODEX_TAG: String = "session-type-picker:agent:codex"
const val SESSION_TYPE_PICKER_AGENT_OPENCODE_TAG: String = "session-type-picker:agent:opencode"
const val SESSION_TYPE_PICKER_SKIP_PERMISSIONS_TAG: String = "session-type-picker:skip-permissions"
const val SESSION_TYPE_PICKER_CWD_TAG: String = "session-type-picker:cwd"
const val SESSION_TYPE_PICKER_CANCEL_TAG: String = "session-type-picker:cancel"
const val SESSION_TYPE_PICKER_CREATE_TAG: String = "session-type-picker:create"
const val SESSION_TYPE_PICKER_CLAUDE_PROFILE_TAG: String = "session-type-picker:claude-profile"

/**
 * A named Claude Code configuration profile (issue #627).
 *
 * Each profile maps to a `CLAUDE_CONFIG_DIR` on the remote host. The
 * default profile has an empty [configDir] (Claude Code uses its built-in
 * default `~/.claude`).
 *
 * Serialized as a JSON array in [HostEntity.claudeProfilesJson].
 */
data class ClaudeProfile(
    val name: String,
    /** Remote path for `CLAUDE_CONFIG_DIR`. Empty string = default (no override). */
    val configDir: String = "",
) {
    companion object {
        /**
         * Parse a JSON array of `{"name":"...","configDir":"..."}` objects.
         * Returns an empty list for null/blank input (the common case for
         * hosts with only the default profile).
         */
        fun fromJson(json: String?): List<ClaudeProfile> {
            if (json.isNullOrBlank()) return emptyList()
            val array = try { JSONArray(json) } catch (_: Throwable) { return emptyList() }
            return (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) return@mapNotNull null
                ClaudeProfile(
                    name = name,
                    configDir = obj.optString("configDir", "").trim(),
                )
            }
        }

        /** Serialize a list of profiles to a JSON array string. */
        fun toJson(profiles: List<ClaudeProfile>): String? {
            if (profiles.isEmpty()) return null
            val array = JSONArray()
            for (profile in profiles) {
                val obj = JSONObject()
                obj.put("name", profile.name)
                obj.put("configDir", profile.configDir)
                array.put(obj)
            }
            return array.toString()
        }
    }
}
