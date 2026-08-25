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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
import com.pocketshell.app.sessions.StartDirectoryAutocompleteUiState
import com.pocketshell.app.sessions.rememberStartDirectoryAutocompleteController
import com.pocketshell.core.ssh.shellSingleQuote
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Picker for "new session" type — issue #171 round 2.
 *
 * The maintainer's refinement comment requires that "+ New session"
 * prompts the user for the SESSION TYPE (agent vs shell) and, when
 * "Agent" is chosen, a sub-picker for the host registry's available engines.
 * The folder is the explicit `cwd` for the new session.
 *
 * The sheet is presented from [FolderListScreen] when the user taps the
 * FAB or an empty-folder row. Confirming the sheet fires
 * [onCreate] with the chosen kind + cwd + (optional) registry engine; the
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
    engines: List<RemoteEngine> = emptyList(),
    claudeProfiles: List<ClaudeProfile> = emptyList(),
    codexProfiles: List<CodexProfile> = emptyList(),
    creating: Boolean = false,
    allowMissingStartDirectoryCreation: Boolean = false,
    // Issue #678: the same picker also drives the in-session `+ window` flow,
    // which creates a new WINDOW rather than a new session. The only visible
    // difference is the heading, so the title is parameterised; everything else
    // (shell-vs-agent toggle, agent CLI sub-picker, profiles, skip-permissions)
    // is reused verbatim. Defaults to "New session" for the folder flow.
    title: String = "New session",
    // Issue #1184: derive the DEFAULT session label to prefill the editable
    // "Session name" field from the currently-chosen start folder. The caller
    // wires in the host's `$HOME` so the default matches the directory-derived
    // convention (#429/#642). Defaults to the folder's trailing segment when a
    // caller doesn't supply the deriver.
    deriveDefaultName: (startDirectory: String) -> String = { it.trimEnd('/').substringAfterLast('/') },
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
            engines = engines,
            claudeProfiles = claudeProfiles,
            codexProfiles = codexProfiles,
            creating = creating,
            allowMissingStartDirectoryCreation = allowMissingStartDirectoryCreation,
            title = title,
            deriveDefaultName = deriveDefaultName,
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
    engines: List<RemoteEngine> = emptyList(),
    claudeProfiles: List<ClaudeProfile> = emptyList(),
    codexProfiles: List<CodexProfile> = emptyList(),
    creating: Boolean = false,
    allowMissingStartDirectoryCreation: Boolean = false,
    title: String = "New session",
    deriveDefaultName: (startDirectory: String) -> String = { it.trimEnd('/').substringAfterLast('/') },
) {
    val availableEngines = availableEnginesForCreate(engines)
    val availableEngineIds = availableEngines.map { it.id }
    var sessionType by remember(availableEngineIds) {
        mutableStateOf(
            if (availableEngines.isEmpty()) SessionType.Shell else SessionType.Agent,
        )
    }
    var selectedEngineId by remember(availableEngineIds) {
        mutableStateOf(availableEngines.firstOrNull()?.id)
    }
    val selectedEngine = availableEngines.firstOrNull { it.id == selectedEngineId }
        ?: availableEngines.firstOrNull()
    // Issue #428: default ON — the maintainer almost always wants the
    // agent launched without per-action approval prompts.
    var skipPermissions by remember { mutableStateOf(true) }
    var startDirectory by remember { mutableStateOf(folderPath) }
    // Issue #1184: editable custom session label, prefilled with the
    // directory-derived default so the common case stays one tap. Until the
    // user types their own label ([nameManuallyEdited]), the field tracks the
    // chosen start folder; once edited, it is left alone.
    var sessionName by remember { mutableStateOf(deriveDefaultName(folderPath)) }
    var nameManuallyEdited by remember { mutableStateOf(false) }
    LaunchedEffect(startDirectory) {
        if (!nameManuallyEdited) {
            sessionName = deriveDefaultName(startDirectory)
        }
    }
    // The existing profile discoveries remain family-specific, but the
    // selected picker row is an open registry engine id.
    var profileName by remember { mutableStateOf<String?>(null) }
    val profiles = pickerProfilesForEngine(selectedEngine, claudeProfiles, codexProfiles)
    val scrollState = rememberScrollState()
    val fallbackAutocompleteState = remember { MutableStateFlow(StartDirectoryAutocompleteUiState()) }
    val autocompleteState by (autocompleteController?.state ?: fallbackAutocompleteState).collectAsState()
    val missingFolderOffer = if (allowMissingStartDirectoryCreation) {
        missingStartDirectoryCreation(
            baseFolderPath = folderPath,
            typedStartDirectory = startDirectory,
            suggestions = autocompleteState.suggestions,
            loading = autocompleteState.loading,
        )
    } else {
        null
    }

    fun emitCreateChoice(createStartDirectory: MissingStartDirectoryCreation?) {
        val resolvedStartDirectory = createStartDirectory?.path
            ?: startDirectory.trim().ifBlank { folderPath }
        onCreate(
            SessionTypeChoice(
                type = sessionType,
                engine = if (sessionType == SessionType.Agent) selectedEngine else null,
                startDirectory = resolvedStartDirectory,
                skipPermissions = skipPermissions,
                profileName = if (sessionType == SessionType.Agent) profileName else null,
                // Issue #1184: carry the user's custom label. Blank falls
                // back to the derived default at create time.
                customName = sessionName.trim().ifBlank { null },
                createStartDirectory = createStartDirectory,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Issue #1821 — LOAD-BEARING, do not delete by analogy with #1812.
            // Inside [SessionTypePickerSheet]'s `ModalBottomSheet` these two are
            // redundant (Material3 consumes both insets before the content is
            // composed — measured byte-identical geometry with and without).
            // But this content is ALSO composed STANDALONE by four sibling
            // tests (`SessionTypePickerNameFieldUiTest`,
            // `SessionTypePickerSkipPermissionsUiTest`,
            // `SessionTypeProfilePickerUiTest`, `RepoBrowserSessionPickerTest`),
            // and there nothing has consumed them, so they are the only thing
            // keeping the Cancel/Create row clear of the keyboard and the nav
            // bar. `StandaloneContentImePaddingLivenessTest` goes RED if EITHER
            // is removed, and it takes BOTH keyboard states to see that:
            // dropping `imePadding()` collapses the keyboard-UP lift
            // 648px -> 0px; dropping `navigationBarsPadding()` drops that row
            // 126px under the nav bar in the keyboard-DOWN state (invisible
            // with the keyboard up, where the ime inset subsumes the nav bar).
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
            SheetHeader(
                title = title,
                subtitle = "in $folderLabel",
                subtitleStyle = PocketShellType.bodyMono,
            )

            // Session name — editable custom label (issue #1184), prefilled
            // with the directory-derived default. Accepting it unchanged
            // reproduces the derived-name behaviour; a blank field falls back
            // to the derived default at create time.
            Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                SectionHeader(label = "Session name")
                OutlinedTextField(
                    value = sessionName,
                    onValueChange = {
                        sessionName = it
                        nameManuallyEdited = true
                    },
                    singleLine = true,
                    placeholder = { Text(deriveDefaultName(startDirectory)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SESSION_TYPE_PICKER_NAME_TAG),
                )
            }

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
                missingFolderOffer?.let { offer ->
                    ListRow(
                        title = "Create folder",
                        subtitle = "${offer.path} - start the new session there.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SESSION_TYPE_PICKER_CREATE_MISSING_FOLDER_TAG),
                        onClick = { emitCreateChoice(offer) },
                    )
                }
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

            // Conditional registry-engine sub-picker. The host owns the
            // labels, ordering, enabled/available state, and raw launch ids.
            if (sessionType == SessionType.Agent) {
                Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                    if (availableEngines.isEmpty()) {
                        Text(
                            text = "No agent engines are available on this host.",
                            color = PocketShellColors.TextMuted,
                            style = PocketShellType.labelMono,
                        )
                    } else {
                        SectionHeader(label = "Agent engine")
                        SegmentedToggle(
                            labels = availableEngines.map { it.label },
                            selectedIndex = availableEngines
                                .indexOfFirst { it.id == selectedEngine?.id }
                                .coerceAtLeast(0),
                            onSelected = {
                                selectedEngineId = availableEngines[it].id
                                profileName = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = PICKER_SEGMENT_HEIGHT),
                            fillSegments = true,
                            segmentTag = { index ->
                                sessionTypePickerAgentEngineTag(availableEngines[index].id)
                            },
                        )
                        Text(
                            text = "The engine will auto-start in the new pane.",
                            color = PocketShellColors.TextMuted,
                            style = PocketShellType.labelMono,
                        )
                    }

                    // The wrapper's generic flag is useful only when the
                    // selected registry row declares skip-permissions support.
                    if (selectedEngine?.launch?.supportsSkipPermissions == true) {
                        SkipPermissionsRow(
                            checked = skipPermissions,
                            onToggle = { skipPermissions = !skipPermissions },
                        )
                    }

                    if (profiles.size > 1) {
                        Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                            SectionHeader(label = "Profile")
                            SegmentedToggle(
                                labels = profiles.map { it.name },
                                selectedIndex = profiles
                                    .indexOfFirst { it.name == profileName }
                                    .coerceAtLeast(0),
                                onSelected = { profileName = profiles[it].name },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = PICKER_SEGMENT_HEIGHT)
                                    .testTag(SESSION_TYPE_PICKER_PROFILE_TAG),
                                fillSegments = true,
                                segmentTag = { index ->
                                    "$SESSION_TYPE_PICKER_PROFILE_TAG:${profiles[index].name}"
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
                onClick = {
                    if (!creating) emitCreateChoice(missingFolderOffer)
                },
                variant = ButtonVariant.Primary,
                enabled = startDirectory.isNotBlank() &&
                    !creating &&
                    (sessionType == SessionType.Shell || selectedEngine != null),
                modifier = Modifier.testTag(SESSION_TYPE_PICKER_CREATE_TAG),
            ) {
                if (creating) {
                    LoadingIndicator.Spinner(size = SpinnerSize.Small, onAccent = true)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Creating", fontWeight = FontWeight.SemiBold)
                } else {
                    Text("Create", fontWeight = FontWeight.SemiBold)
                }
            }
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
    /** The host-registry row selected for an agent session. */
    val engine: RemoteEngine?,
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
    /** The selected profile name, or null for the engine default. */
    val profileName: String? = null,
    /**
     * The user-entered custom session label (issue #1184). `null`/blank means
     * "use the directory-derived default" (#429/#642). When present it is
     * sanitised to a tmux-safe BASE name by
     * [SessionNameDerivation.resolveSessionName] at create time. Issue #1820,
     * D22 hard cut: it is NOT disambiguated against a client-side set of known
     * session names any more (that set could be stale or empty) — collision
     * handling belongs to the host, which resolves the final unique name under
     * [SessionNamePolicy.UniqueOnHost].
     */
    val customName: String? = null,
    /**
     * Missing start folder the picker should create before creating the
     * session. `null` means the typed [startDirectory] already exists or
     * should be used as-is.
     */
    val createStartDirectory: MissingStartDirectoryCreation? = null,
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
     * pocketshell agent <registry-id> --dir '<dir>' [--no-skip-permissions]
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
     * - The selected profile (issue #718) is passed by NAME as `--profile
     *   '<name>'`; the host-side wrapper resolves it through the same
     *   discovery the picker was populated from. The default profile is
     *   omitted.
     */
    fun startCommand(
        claudeProfiles: List<ClaudeProfile> = emptyList(),
        codexProfiles: List<CodexProfile> = emptyList(),
    ): String? {
        if (type == SessionType.Shell) return null
        val selectedEngine = engine ?: return null
        val selectedProfile = pickerProfilesForEngine(
            engine = selectedEngine,
            claudeProfiles = claudeProfiles,
            codexProfiles = codexProfiles,
        ).firstOrNull { it.name == profileName }
            ?.takeUnless { it.default }
            ?.name
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return buildRegistryAgentCommand(
            engineId = selectedEngine.rawId,
            directory = startDirectory,
            noSkipPermissions = !skipPermissions && selectedEngine.launch.supportsSkipPermissions,
            profileName = selectedProfile,
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
            SessionType.Agent -> engine?.family
        }

    /** Open-ended host identity used for the wrapper and durable tmux option. */
    val engineId: String?
        get() = engine?.rawId
}

data class MissingStartDirectoryCreation(
    val parentPath: String,
    val folderName: String,
    val path: String,
)

internal fun missingStartDirectoryCreation(
    baseFolderPath: String,
    typedStartDirectory: String,
    suggestions: List<String>,
    loading: Boolean,
): MissingStartDirectoryCreation? {
    if (loading) return null
    val typed = typedStartDirectory.trim().trimEnd('/')
    if (typed.isBlank()) return null
    if (typed == "~" || typed == "\$HOME") return null
    if (typed == baseFolderPath.trim().trimEnd('/')) return null
    val exactMatch = suggestions.any { suggestion ->
        suggestion.trim().trimEnd('/') == typed
    }
    if (exactMatch) return null

    val normalised = typed.replace('\\', '/')
    val slashIndex = normalised.lastIndexOf('/')
    val rawParent = when {
        slashIndex < 0 -> baseFolderPath
        slashIndex == 0 -> "/"
        else -> normalised.substring(0, slashIndex)
    }.ifBlank { baseFolderPath }
    if (slashIndex >= 0 && !isRootedRemoteParent(rawParent)) return null
    if (rawParent.split('/').any { it == ".." }) return null
    val rawName = when {
        slashIndex < 0 -> normalised
        else -> normalised.substring(slashIndex + 1)
    }
    val safeName = SshFolderListGateway.normaliseProjectFolderName(rawName) ?: return null
    if (safeName != rawName.trim().trim('/')) return null
    val parent = rawParent.trim().trimEnd('/').ifBlank { "~" }
    return MissingStartDirectoryCreation(
        parentPath = parent,
        folderName = safeName,
        path = SshFolderListGateway.childPath(parent, safeName),
    )
}

private fun isRootedRemoteParent(parent: String): Boolean =
    parent == "/" ||
        parent == "~" ||
        parent == "\$HOME" ||
        parent.startsWith("/") ||
        parent.startsWith("~/") ||
        parent.startsWith("\$HOME/")

enum class SessionType { Shell, Agent }

/**
 * Assemble the short wrapper invocation for one open-ended registry id.
 * Registry ids are validated by the host registry; directory/profile values
 * still need shell quoting because they are user/host data.
 */
internal fun buildRegistryAgentCommand(
    engineId: String,
    directory: String,
    noSkipPermissions: Boolean,
    profileName: String?,
): String {
    val parts = StringBuilder("pocketshell agent ")
    parts.append(engineId)
    parts.append(" --dir ").append(shellSingleQuote(directory))
    if (noSkipPermissions) {
        parts.append(" --no-skip-permissions")
    }
    if (profileName != null) {
        parts.append(" --profile ").append(shellSingleQuote(profileName))
    }
    return parts.toString()
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
const val SESSION_TYPE_PICKER_AGENT_ENGINE_TAG_PREFIX: String = "session-type-picker:agent:engine:"
const val SESSION_TYPE_PICKER_SKIP_PERMISSIONS_TAG: String = "session-type-picker:skip-permissions"
const val SESSION_TYPE_PICKER_CWD_TAG: String = "session-type-picker:cwd"
const val SESSION_TYPE_PICKER_NAME_TAG: String = "session-type-picker:name"
const val SESSION_TYPE_PICKER_CREATE_MISSING_FOLDER_TAG: String =
    "session-type-picker:create-missing-folder"
const val SESSION_TYPE_PICKER_CANCEL_TAG: String = "session-type-picker:cancel"
const val SESSION_TYPE_PICKER_CREATE_TAG: String = "session-type-picker:create"
const val SESSION_TYPE_PICKER_PROFILE_TAG: String = "session-type-picker:profile"

private val SESSION_TYPE_LABELS = listOf("Shell", "Agent")

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
