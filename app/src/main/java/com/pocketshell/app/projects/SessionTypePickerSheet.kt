package com.pocketshell.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.app.sessions.StartDirectoryAutocompleteController
import com.pocketshell.app.sessions.StartDirectoryAutocompleteField
import com.pocketshell.app.sessions.rememberStartDirectoryAutocompleteController
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

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
    codexProfiles: List<CodexProfile> = emptyList(),
    // Issue #678: the same picker also drives the in-session `+ window` flow,
    // which creates a new WINDOW rather than a new session. The only visible
    // difference is the heading, so the title is parameterised; everything else
    // (shell-vs-agent toggle, agent CLI sub-picker, profiles, skip-permissions)
    // is reused verbatim. Defaults to "New session" for the folder flow.
    title: String = "New session",
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
            codexProfiles = codexProfiles,
            title = title,
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
    codexProfiles: List<CodexProfile> = emptyList(),
    title: String = "New session",
) {
    var sessionType by remember { mutableStateOf(SessionType.Agent) }
    var agentKind by remember { mutableStateOf(AgentCli.Claude) }
    // Issue #428: default ON — the maintainer almost always wants the
    // agent launched without per-action approval prompts.
    var skipPermissions by remember { mutableStateOf(true) }
    var startDirectory by remember { mutableStateOf(folderPath) }
    // Issue #627: selected Claude profile. null = default (no config dir override).
    var claudeProfile by remember { mutableStateOf<String?>(null) }
    // Issue #631: selected Codex profile. null = default (no config dir override).
    var codexProfile by remember { mutableStateOf<String?>(null) }
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
                text = title,
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

            // Segmented control: Shell vs Agent. Uses the shared ui-kit
            // SegmentedToggle (the locked cyan-fill "pick one of N" control,
            // #479/#481) so the picker reads identically to every other
            // segmented switch in the app.
            Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                SectionHeader(label = "Session type")
                SegmentedToggle(
                    labels = SESSION_TYPE_LABELS,
                    selectedIndex = if (sessionType == SessionType.Shell) 0 else 1,
                    onSelected = {
                        sessionType = if (it == 0) SessionType.Shell else SessionType.Agent
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PICKER_SEGMENT_HEIGHT),
                    fillSegments = true,
                    segmentTag = { index ->
                        if (index == 0) SESSION_TYPE_PICKER_SHELL_TAG else SESSION_TYPE_PICKER_AGENT_TAG
                    },
                )
            }

            // Conditional agent CLI sub-picker.
            if (sessionType == SessionType.Agent) {
                Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                    SectionHeader(label = "Agent CLI")
                    SegmentedToggle(
                        labels = AGENT_CLI_LABELS,
                        selectedIndex = agentKind.ordinal,
                        onSelected = { agentKind = AgentCli.entries[it] },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = PICKER_SEGMENT_HEIGHT),
                        fillSegments = true,
                        segmentTag = { index -> AGENT_CLI_SEGMENT_TAGS[index] },
                    )
                    Text(
                        text = "The CLI will auto-start in the new pane.",
                        color = PocketShellColors.TextMuted,
                        style = PocketShellType.labelMono,
                    )

                    // Skip-permissions toggle (issue #428). Hidden for
                    // OpenCode: its per-action permissions are config-driven
                    // in opencode.json, not a CLI flag, so the checkbox would
                    // be a no-op there. OpenCode is always launched
                    // env-stripped (subscription auth) by the wrapper
                    // regardless (issue #703).
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
                            SegmentedToggle(
                                labels = claudeProfiles.map { it.name },
                                selectedIndex = claudeProfiles
                                    .indexOfFirst { it.name == claudeProfile }
                                    .coerceAtLeast(0),
                                onSelected = { claudeProfile = claudeProfiles[it].name },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = PICKER_SEGMENT_HEIGHT)
                                    .testTag(SESSION_TYPE_PICKER_CLAUDE_PROFILE_TAG),
                                fillSegments = true,
                                segmentTag = { index ->
                                    "$SESSION_TYPE_PICKER_CLAUDE_PROFILE_TAG:${claudeProfiles[index].name}"
                                },
                            )
                        }
                    }

                    // Issue #631: Codex profile selector. Shown only when
                    // Codex is selected AND the host has more than one
                    // profile configured.
                    if (agentKind == AgentCli.Codex && codexProfiles.size > 1) {
                        Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                            SectionHeader(label = "Profile")
                            SegmentedToggle(
                                labels = codexProfiles.map { it.name },
                                selectedIndex = codexProfiles
                                    .indexOfFirst { it.name == codexProfile }
                                    .coerceAtLeast(0),
                                onSelected = { codexProfile = codexProfiles[it].name },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = PICKER_SEGMENT_HEIGHT)
                                    .testTag(SESSION_TYPE_PICKER_CODEX_PROFILE_TAG),
                                fillSegments = true,
                                segmentTag = { index ->
                                    "$SESSION_TYPE_PICKER_CODEX_PROFILE_TAG:${codexProfiles[index].name}"
                                },
                            )
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
            PocketShellButton(
                text = "Cancel",
                onClick = onCancel,
                variant = ButtonVariant.Text,
                modifier = Modifier.testTag(SESSION_TYPE_PICKER_CANCEL_TAG),
            )
            Spacer(modifier = Modifier.padding(end = 8.dp))
            PocketShellButton(
                text = "Create",
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
                            codexProfileName = if (sessionType == SessionType.Agent && agentKind == AgentCli.Codex) {
                                codexProfile
                            } else {
                                null
                            },
                        ),
                    )
                },
                variant = ButtonVariant.Primary,
                enabled = startDirectory.isNotBlank(),
                modifier = Modifier.testTag(SESSION_TYPE_PICKER_CREATE_TAG),
            )
        }
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
    /**
     * The selected Codex profile name (issue #631). `null` means the
     * default profile (no `CODEX_HOME` override). Only relevant when
     * [agent] is [AgentCli.Codex]; ignored for other agents and shell
     * sessions.
     */
    val codexProfileName: String? = null,
) {
    /**
     * The start command to invoke inside the new tmux pane after
     * creation. `null` for plain shell sessions (which need no extra
     * command beyond the user's default shell).
     *
     * For agents this is the SHORT server-side wrapper invocation
     * (issue #703):
     *
     * ```
     * pocketshell agent <kind> --dir '<dir>' [--no-skip-permissions] [--config-dir '<path>']
     * ```
     *
     * The wrapper (`tools/pocketshell` `agent` subcommand) does everything
     * the old ~1500-char inline `env -u …(71)… <agent>` line did and more:
     * it merges the folder's `.env`/`.envrc`, strips the provider API-key
     * env vars ONLY for OpenCode (subscription billing — codex/claude pass
     * the env through, matching the maintainer's `csp`/`cy` aliases which
     * do not strip), suppresses each agent's first-run modal (codex update
     * check / claude folder-trust) so the agent is immediately usable, then
     * `execvpe`s the agent.
     *
     * Hard-cut (D22): the old inline env-strip builders and the
     * `eval "$(pocketshell env export …)"` prelude are gone — this is the
     * one and only launch path.
     *
     * - Skip-permissions defaults ON in the wrapper, so `--no-skip-permissions`
     *   is emitted only when the user turned it OFF (and never for OpenCode,
     *   where it is a no-op).
     * - The selected Claude / Codex profile (issue #718) is passed by NAME as
     *   `--profile '<name>'`; the host-side wrapper resolves the name to its
     *   `CLAUDE_CONFIG_DIR` / `CODEX_HOME` via the same discovery the picker
     *   was populated from. The default profile is omitted (the wrapper uses
     *   the engine's built-in config dir).
     */
    fun startCommand(
        claudeProfiles: List<ClaudeProfile> = emptyList(),
        codexProfiles: List<CodexProfile> = emptyList(),
    ): String? = when (type) {
        SessionType.Shell -> null
        SessionType.Agent -> agent?.launchCommand(
            startDirectory, skipPermissions, claudeProfileName, claudeProfiles,
            codexProfileName, codexProfiles,
        )
    }

    /**
     * Epic #821 Workstream A: the [SessionAgentKind] this choice will launch,
     * known synchronously at create time (no detection). Passed into
     * [FolderListViewModel.createSession] so the optimistically-inserted tree
     * node shows the real kind from the moment of creation. `null` for a shell
     * session (no agent kind to record) — the node then falls through to the
     * reconcile's `Shell` verdict as before.
     */
    val sessionAgentKind: SessionAgentKind?
        get() = when (type) {
            SessionType.Shell -> null
            SessionType.Agent -> agent?.toSessionAgentKind()
        }
}

enum class SessionType { Shell, Agent }

enum class AgentCli(val command: String) {
    Claude("claude"),
    Codex("codex"),
    OpenCode("opencode"),
    ;

    /**
     * Epic #821 Workstream A: map the picked CLI to the [SessionAgentKind] the
     * tree renders, so the chosen kind can be recorded on the new session node
     * at create time. This is the SAME kind the `pocketshell agent <command>`
     * wrapper records host-side as `@ps_agent_kind` ([command]), so the
     * optimistic node and the host read-back agree.
     */
    fun toSessionAgentKind(): SessionAgentKind = when (this) {
        Claude -> SessionAgentKind.Claude
        Codex -> SessionAgentKind.Codex
        OpenCode -> SessionAgentKind.OpenCode
    }

    /**
     * Build the SHORT `pocketshell agent <kind> --dir <dir> …` line typed
     * into the new pane (issue #703). The server-side wrapper owns the env
     * merge, the OpenCode-only env strip, the per-agent first-run-prompt
     * suppression, and the `execvpe`. The app's only job is to assemble the
     * short, shell-safe argv.
     *
     * - [directory] → `--dir '<dir>'` (shell-quoted; the wrapper validates
     *   and `cd`s into it).
     * - [skipPermissions] defaults ON in the wrapper, so the app emits
     *   `--no-skip-permissions` only when it is OFF. OpenCode never gets a
     *   skip flag (it is a no-op there — permissions are config-driven).
     * - A non-default Claude / Codex profile (issue #718) → `--profile
     *   '<name>'`; the wrapper resolves the name to `CLAUDE_CONFIG_DIR` /
     *   `CODEX_HOME` host-side. The default profile (and OpenCode, which has
     *   no profiles) emits no `--profile`.
     */
    fun launchCommand(
        directory: String,
        skipPermissions: Boolean,
        claudeProfileName: String? = null,
        claudeProfiles: List<ClaudeProfile> = emptyList(),
        codexProfileName: String? = null,
        codexProfiles: List<CodexProfile> = emptyList(),
    ): String {
        // Resolve the selected profile and only pass it by NAME when it is a
        // real, non-default profile. The default profile means "use the
        // engine's built-in config dir", so no `--profile` is emitted.
        val profileName: String? = when (this) {
            Claude -> claudeProfiles.firstOrNull { it.name == claudeProfileName }
                ?.takeUnless { it.default }?.name
            Codex -> codexProfiles.firstOrNull { it.name == codexProfileName }
                ?.takeUnless { it.default }?.name
            OpenCode -> null
        }?.trim()?.takeIf { it.isNotBlank() }

        // OpenCode's skip-permissions checkbox is a no-op, so never emit the
        // flag for it; for claude/codex the wrapper defaults ON, so we only
        // speak up to turn it OFF.
        val emitNoSkip = this != OpenCode && !skipPermissions

        return buildAgentCommand(
            kind = command,
            directory = directory,
            noSkipPermissions = emitNoSkip,
            profileName = profileName,
        )
    }

    companion object {
        /**
         * Assemble `pocketshell agent <kind> --dir '<dir>' [--no-skip-permissions]
         * [--profile '<name>']` (issue #703 / #718). Paths and names are
         * single-quoted so spaces or shell metacharacters cannot break out of
         * the argument or inject a command. The whole line is single-quoted
         * again by the gateway when it is passed to `tmux send-keys`.
         */
        internal fun buildAgentCommand(
            kind: String,
            directory: String,
            noSkipPermissions: Boolean,
            profileName: String?,
        ): String {
            val parts = StringBuilder("pocketshell agent ")
            parts.append(kind)
            parts.append(" --dir ").append(shellQuote(directory))
            if (noSkipPermissions) {
                parts.append(" --no-skip-permissions")
            }
            if (profileName != null) {
                parts.append(" --profile ").append(shellQuote(profileName))
            }
            return parts.toString()
        }

        /** Single-quote a value for safe inclusion in a shell command. */
        private fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
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
const val SESSION_TYPE_PICKER_CODEX_PROFILE_TAG: String = "session-type-picker:codex-profile"

// Segment labels and per-segment tags for the shared SegmentedToggle controls.
// AGENT_CLI_* lists are ordered to match AgentCli.entries (Claude, Codex,
// OpenCode), so the segment index maps straight onto the enum ordinal.
private val SESSION_TYPE_LABELS = listOf("Shell", "Agent")
private val AGENT_CLI_LABELS = listOf("claude", "codex", "opencode")
private val AGENT_CLI_SEGMENT_TAGS = listOf(
    SESSION_TYPE_PICKER_AGENT_CLAUDE_TAG,
    SESSION_TYPE_PICKER_AGENT_CODEX_TAG,
    SESSION_TYPE_PICKER_AGENT_OPENCODE_TAG,
)

/**
 * A named Claude Code configuration profile shown in the picker — issue #718.
 *
 * Profiles are now DISCOVERED on the host (`pocketshell profiles list`) and
 * fetched by [ProfilesGateway], not stored on the phone (the #627
 * client-stored JSON model was hard-cut per D22). The selected profile name
 * is passed to the server as `pocketshell agent claude --profile <name>`,
 * which resolves it to `CLAUDE_CONFIG_DIR` host-side.
 *
 * @property default whether this is the engine's built-in default profile
 *   (no `--profile` flag is emitted for the default — the wrapper uses the
 *   engine's own config dir).
 */
data class ClaudeProfile(
    val name: String,
    val default: Boolean = false,
)

/**
 * A named Codex configuration profile shown in the picker — issue #718.
 *
 * Mirrors [ClaudeProfile] for Codex. Discovered host-side and fetched by
 * [ProfilesGateway]; the selected name is passed as
 * `pocketshell agent codex --profile <name>` (resolved to `CODEX_HOME`
 * host-side).
 */
data class CodexProfile(
    val name: String,
    val default: Boolean = false,
)
