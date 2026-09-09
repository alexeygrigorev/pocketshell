package com.pocketshell.next.tree

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pocketshell.core.hostapi.EngineInfo
import com.pocketshell.core.hostapi.ProfileInfo
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.workspaces.SessionKindMark
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Stable test tags for the create-session sheet. Instrumentation asserts on
 * these rather than on user-visible copy, so a wording change cannot silently
 * disarm the journey that proves the sheet works.
 */
const val CREATE_SESSION_SHEET_TAG: String = "create-session-sheet"
const val CREATE_SESSION_TITLE_TAG: String = "create-session-title"
const val CREATE_SESSION_FOLDER_TAG: String = "create-session-folder"
const val CREATE_SESSION_NAME_TAG: String = "create-session-name"
const val CREATE_SESSION_SUBMIT_TAG: String = "create-session-submit"
const val CREATE_SESSION_CANCEL_TAG: String = "create-session-cancel"
const val CREATE_SESSION_ERROR_TAG: String = "create-session-error"
const val CREATE_SESSION_TYPE_SHELL_TAG: String = "create-session-type-shell"
const val CREATE_SESSION_ENGINE_TAG_PREFIX: String = "create-session-engine-"
const val CREATE_SESSION_PROFILE_TAG: String = "create-session-profile"
const val CREATE_SESSION_OPTIONS_TAG: String = "create-session-options"
const val CREATE_SESSION_UNAVAILABLE_TAG: String = "create-session-unavailable"

fun createSessionEngineTag(engineId: String): String = "$CREATE_SESSION_ENGINE_TAG_PREFIX$engineId"

fun createSessionProfileTag(profileName: String): String = "$CREATE_SESSION_PROFILE_TAG-$profileName"

internal const val CREATE_SESSION_TITLE = "New session"
internal const val CREATE_SESSION_FOLDER_LABEL = "Folder"
internal const val CREATE_SESSION_NAME_LABEL = "Session name"
internal const val CREATE_SESSION_SUBMIT_LABEL = "Start"
internal const val CREATE_SESSION_PROFILE_LABEL = "Profile"

private val PICKER_SEGMENT_HEIGHT = 48.dp
private const val CREATE_SESSION_HEIGHT_FRACTION = 0.85f
private val CREATE_SESSION_MAX_HEIGHT = 560.dp

/** Shell (plain pane) vs Agent (host starts an engine in the new session). */
enum class CreateSessionKind { Shell, Agent }

/** The five programs exposed by the New session sheet. */
private data class DirectSessionProgram(
    val id: String,
    val label: String,
    val description: String,
    val engineIds: Set<String> = setOf(id),
)

private val DIRECT_SESSION_PROGRAMS = listOf(
    DirectSessionProgram(
        id = "shell",
        label = "Shell",
        description = "A plain terminal",
    ),
    DirectSessionProgram(
        id = "claude",
        label = "Claude Code",
        description = "Ready on this host",
    ),
    DirectSessionProgram(
        id = "codex",
        label = "Codex",
        description = "Ready on this host",
    ),
    DirectSessionProgram(
        id = "opencode",
        label = "OpenCode",
        description = "Ready on this host",
        engineIds = setOf("opencode", "open_code", "open-code"),
    ),
    DirectSessionProgram(
        id = "grok",
        label = "Grok",
        description = "Ready on this host",
        engineIds = setOf("grok", "grok-build"),
    ),
)

private fun directProgramId(engineId: String): String? =
    DIRECT_SESSION_PROGRAMS.firstOrNull { engineId.trim().lowercase() in it.engineIds }?.id

private fun engineForProgram(
    program: DirectSessionProgram,
    engines: List<EngineInfo>,
): EngineInfo? = engines.firstOrNull { it.id.trim().lowercase() in program.engineIds }

private fun isCreateable(engine: EngineInfo?): Boolean =
    engine != null && availableEnginesForCreate(listOf(engine)).isNotEmpty()

private fun selectedDirectProgram(
    form: CreateSessionFormState,
    resolvedEngineId: String? = form.engineId,
): DirectSessionProgram =
    if (form.kind == CreateSessionKind.Shell) {
        DIRECT_SESSION_PROGRAMS.first()
    } else {
        DIRECT_SESSION_PROGRAMS.firstOrNull { it.id == resolvedEngineId?.let(::directProgramId) }
            ?: DIRECT_SESSION_PROGRAMS.first()
    }

/**
 * What the sheet asks `sessions create` to do. Optional flags are `null`
 * rather than blank so [com.pocketshell.core.hostapi.HostCliClient.createSession]
 * omits them and the host's own defaults apply.
 */
data class CreateSessionRequest(
    val name: String,
    val cwd: String?,
    val engine: String? = null,
    val profile: String? = null,
)

/**
 * The only provider rows that may be started: enabled AND available AND the
 * host's own `available_for_create` verdict. Disabled, missing-harness, and
 * not-createable rows remain visible as unavailable recovery choices, while
 * the host's verdict still gates the request (issue #2439 / #2522).
 */
fun availableEnginesForCreate(engines: List<EngineInfo>): List<EngineInfo> =
    engines.filter { engine ->
        engine.availableForCreate &&
            engine.enabled &&
            engine.available &&
            engine.id != "shell"
    }

/** Profiles belonging to [engineId], in host order. */
fun profilesForEngine(profiles: List<ProfileInfo>, engineId: String?): List<ProfileInfo> {
    if (engineId == null) return emptyList()
    return profiles.filter { it.engine == engineId }
}

/**
 * The session name derived from a folder path — the LAST path segment.
 *
 * The host CLI's `sessions create NAME` takes the name as a REQUIRED positional
 * argument and derives nothing from `--cwd` (see
 * [com.pocketshell.core.hostapi.HostCliClient.createSession]), so a blank name
 * is a usage error on the host, not a generated one. Rather than making the
 * user type a name they almost always want to be "the folder", the sheet
 * prefills this and lets them overwrite it — the create button stays disabled
 * while the field is blank, so the host can never be asked to do the deriving.
 *
 * Pure: no Android, no Compose, no clock.
 */
fun defaultSessionName(folder: String): String {
    val trimmed = folder.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    val segment = trimmed.substringAfterLast('/').trim()
    // `~`, `.` and `..` name a location, not a project; there is nothing
    // useful to derive from them.
    if (segment == "~" || segment == "." || segment == "..") return ""
    return segment
}

/** Returns the derived name plus a stable numeric suffix when it is occupied. */
fun collisionSafeSessionName(folder: String, existingNames: Collection<String>): String {
    val base = defaultSessionName(folder)
    if (base.isBlank()) return base
    val occupied = existingNames.map { it.trim().lowercase() }.toSet()
    if (base.lowercase() !in occupied) return base
    var suffix = 2
    while ("$base $suffix".lowercase() in occupied) suffix += 1
    return "$base $suffix"
}

/**
 * The identities a new session's derived name must avoid, taken from the rows
 * the host currently lists. A create collides on the TAG — the host CLI is
 * idempotent per workspace+tag and answers `created=false` for a repeat — so
 * schema-3 display names (`<workspace>:<tag>`) must not be compared verbatim:
 * against them every bare tag looks free and the second default session
 * silently no-ops. Rows the host gave no tag for fall back to their raw name.
 */
fun existingSessionTags(rows: List<SessionRow>): List<String> =
    rows.map { row -> row.tag?.takeIf(String::isNotBlank) ?: row.name }

/**
 * The sheet's editable form, hoisted out of the composition.
 *
 * A plain state holder rather than `remember { mutableStateOf(...) }` pairs
 * inside the composable, for two reasons: the name-follows-folder rule is real
 * behaviour that deserves a test of its own (it is unit-tested directly, with
 * no composition at all), and a test of the sheet can hand in a pre-filled form
 * instead of driving an `OutlinedTextField`, whose focused cursor animation
 * wedges Robolectric's idle wait.
 *
 * [name] tracks [folder] only until the user edits the name themselves; after
 * that the field is theirs and a later folder edit leaves it alone. Silently
 * overwriting a typed name would be the worse behaviour of the two.
 *
 * Kind defaults to [CreateSessionKind.Shell] until host capabilities arrive.
 * The sheet promotes the first createable direct program (Claude Code when
 * present) after that read; an explicit user choice always wins.
 */
@Stable
class CreateSessionFormState(
    initialFolder: String = "",
    private val existingSessionNames: Collection<String> = emptyList(),
) {

    var folder: String by mutableStateOf(initialFolder)
        private set

    var name: String by mutableStateOf(
        collisionSafeSessionName(initialFolder, existingSessionNames),
    )
        private set

    /** True once the user typed in the name field, which freezes the tracking. */
    var nameEdited: Boolean by mutableStateOf(false)
        private set

    var kind: CreateSessionKind by mutableStateOf(CreateSessionKind.Shell)
        private set

    var engineId: String? by mutableStateOf(null)
        private set

    var profileName: String? by mutableStateOf(null)
        private set

    /** True once the user has chosen a program in the direct picker. */
    private var programEdited: Boolean = false

    fun onFolderChange(value: String) {
        folder = value
        if (!nameEdited) name = collisionSafeSessionName(value, existingSessionNames)
    }

    fun onNameChange(value: String) {
        name = value
        nameEdited = true
    }

    fun onKindChange(value: CreateSessionKind) {
        programEdited = true
        kind = value
    }

    fun onEngineChange(value: String?) {
        programEdited = true
        engineId = value
        profileName = null
    }

    fun onProfileChange(value: String?) {
        profileName = value
    }

    /** The host requires a name, so a blank one can never be submitted. */
    val canSubmit: Boolean get() = name.isNotBlank()

    /**
     * Selects the design-kit default once host capabilities are known. This is
     * deliberately a no-op after an explicit tap, so a refresh cannot replace
     * the user's program choice underneath them.
     */
    fun selectDefaultProgram(available: List<EngineInfo>) {
        if (programEdited || kind != CreateSessionKind.Shell || engineId != null) return
        val preferred = available.firstOrNull { directProgramId(it.id) == "claude" }
            ?: available.firstOrNull { directProgramId(it.id) != null }
            ?: return
        kind = CreateSessionKind.Agent
        engineId = preferred.id
        profileName = null
    }

    /** What `sessions create` is asked for: `NAME`. */
    val submittedName: String get() = name.trim()

    /** `--cwd`, or `null` so the host's own default working directory applies. */
    val submittedCwd: String? get() = folder.trim().ifBlank { null }

    /**
     * Agent create needs a createable engine. Shell never does. [available]
     * is the already-filtered picker list so this does not re-derive the
     * #2439 hide rule.
     */
    fun canSubmitWith(available: List<EngineInfo>): Boolean =
        canSubmit && (kind == CreateSessionKind.Shell || available.isNotEmpty())

    /** Flags the host CLI will see; omitted ones stay `null`. */
    fun toRequest(
        engines: List<EngineInfo>,
        profiles: List<ProfileInfo>,
    ): CreateSessionRequest {
        val available = availableEnginesForCreate(engines)
        val engine = if (kind == CreateSessionKind.Agent) {
            available.firstOrNull { it.id == engineId } ?: available.firstOrNull()
        } else {
            null
        }
        val engineProfiles = profilesForEngine(profiles, engine?.id)
        val profile = engine?.let {
            val chosen = profileName ?: return@let null
            engineProfiles.firstOrNull { profile -> profile.name == chosen }?.name
        }
        return CreateSessionRequest(
            name = submittedName,
            cwd = submittedCwd,
            engine = engine?.id,
            profile = profile,
        )
    }
}

/**
 * The create-session bottom sheet (rewrite task U-6, journey J04, issue #2522).
 *
 * Direct program choices from the design-kit, with advanced folder/name/profile
 * fields behind More options. Provider rows are fed by the host registry; the
 * request sent to the host remains [CreateSessionRequest].
 *
 * Dismissing the sheet (scrim tap / back / drag-down) routes to [onCancel],
 * so backing out can never be mistaken for a create.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSessionSheet(
    state: CreateSessionState,
    defaultFolder: String,
    onSubmit: (CreateSessionRequest) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    existingSessionNames: Collection<String> = emptyList(),
    onRefreshEngines: () -> Unit = {},
    // `skipPartiallyExpanded` is load-bearing, not a style choice: a modal sheet
    // otherwise opens at its PARTIAL detent (about half the screen) and anything
    // below that line is off-screen. With the keyboard up — which is the normal
    // state of this sheet, since it is two text fields — the Create button lands
    // exactly there and the user cannot reach it (observed on the emulator in
    // J04). Opening fully expanded, plus the `imePadding` below, keeps the
    // action row above the IME.
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        shape = PocketShellShapes.large,
    ) {
        CreateSessionSheetContent(
            state = state,
            defaultFolder = defaultFolder,
            onSubmit = onSubmit,
            onCancel = onCancel,
            existingSessionNames = existingSessionNames,
            onRefreshEngines = onRefreshEngines,
        )
    }
}

/**
 * The sheet's body, split out from the [ModalBottomSheet] container so it can
 * be composed directly by a host-JVM test (and a design render) without the
 * sheet's window/animation machinery — the same split [
 * com.pocketshell.next.connect.TrustPromptSheet] uses.
 */
@Composable
fun CreateSessionSheetContent(
    state: CreateSessionState,
    defaultFolder: String,
    onSubmit: (CreateSessionRequest) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    existingSessionNames: Collection<String> = emptyList(),
    form: CreateSessionFormState = remember(defaultFolder, existingSessionNames) {
        CreateSessionFormState(defaultFolder, existingSessionNames)
    },
    onRefreshEngines: () -> Unit = {},
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = PocketShellColors.Text,
        unfocusedTextColor = PocketShellColors.Text,
        focusedBorderColor = PocketShellColors.Accent,
        unfocusedBorderColor = PocketShellColors.BorderSoft,
        focusedLabelColor = PocketShellColors.Accent,
        unfocusedLabelColor = PocketShellColors.TextSecondary,
        cursorColor = PocketShellColors.Accent,
    )
    val available = availableEnginesForCreate(state.engines)
    LaunchedEffect(available.map { it.id }) {
        form.selectDefaultProgram(available)
    }
    var unavailableProgram by remember { mutableStateOf<DirectSessionProgram?>(null) }
    val enginesByProgram = remember(state.engines) {
        DIRECT_SESSION_PROGRAMS.associateWith { program ->
            engineForProgram(program, state.engines)
        }
    }
    val selectedEngine = if (form.kind == CreateSessionKind.Agent) {
        available.firstOrNull { it.id == form.engineId }
    } else {
        null
    }
    val selectedProgram = selectedDirectProgram(form, selectedEngine?.id)
    val engineProfiles = profilesForEngine(state.profiles, selectedEngine?.id)
    val selectedProfileIndex = engineProfiles
        .indexOfFirst { it.name == form.profileName }
        .let { index -> if (index >= 0) index else engineProfiles.indexOfFirst { it.isDefault } }
        .coerceAtLeast(0)
    var optionsOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(CREATE_SESSION_HEIGHT_FRACTION)
            .heightIn(max = CREATE_SESSION_MAX_HEIGHT)
            .testTag(CREATE_SESSION_SHEET_TAG)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(top = PocketShellSpacing.lg, bottom = PocketShellSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        ) {
            SheetHeader(
                title = unavailableProgram?.let { "${it.label} is not available" }
                    ?: if (optionsOpen) "Session options" else CREATE_SESSION_TITLE,
                titleTestTag = CREATE_SESSION_TITLE_TAG,
                closeTestTag = CREATE_SESSION_CANCEL_TAG,
                onClose = {
                    if (!state.submitting) {
                        when {
                            unavailableProgram != null -> unavailableProgram = null
                            optionsOpen -> optionsOpen = false
                            else -> onCancel()
                        }
                    }
                },
            )

            // A failed create keeps the sheet open with the user's own text still
            // in the fields: the fix for "that folder does not exist" is an edit,
            // and a sheet that closed on failure would throw the edit away.
            state.failure?.let { failure ->
                Banner(
                    text = failure,
                    role = BannerRole.Error,
                    maxLines = 4,
                    modifier = Modifier.testTag(CREATE_SESSION_ERROR_TAG),
                )
            }

            if (unavailableProgram != null) {
                val program = unavailableProgram!!
                val engine = enginesByProgram[program]
                Text(
                    text = "PocketShell could not find a usable ${program.label} installation on the host.",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.body,
                    modifier = Modifier.testTag(CREATE_SESSION_UNAVAILABLE_TAG),
                )
                PocketShellButton(
                    text = "Choose another program",
                    onClick = {
                        unavailableProgram = null
                    },
                    variant = ButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                PocketShellButton(
                    text = if (state.enginesLoading) "Checking…" else "Re-check",
                    onClick = {
                        unavailableProgram = null
                        onRefreshEngines()
                    },
                    variant = ButtonVariant.Secondary,
                    enabled = !state.enginesLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                var technicalDetailsOpen by remember(program.id) { mutableStateOf(false) }
                ListRow(
                    title = "Technical details",
                    subtitle = if (technicalDetailsOpen) "Hide details" else "Show host capability details",
                    onClick = { technicalDetailsOpen = !technicalDetailsOpen },
                )
                if (technicalDetailsOpen) {
                    Text(
                        text = "Executable unavailable\nHost engine: ${engine?.id ?: program.id}\n" +
                            (engine?.unavailableReason
                                ?: "The host did not report a usable installation."),
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.bodyMono,
                    )
                }
            } else if (optionsOpen) {
                OutlinedTextField(
                    value = form.folder,
                    onValueChange = form::onFolderChange,
                    singleLine = true,
                    enabled = !state.submitting,
                    label = { Text(CREATE_SESSION_FOLDER_LABEL) },
                    placeholder = { Text("/home/you/git/project") },
                    colors = fieldColors,
                    textStyle = PocketShellType.bodyMono,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CREATE_SESSION_FOLDER_TAG),
                )

                OutlinedTextField(
                    value = form.name,
                    onValueChange = form::onNameChange,
                    singleLine = true,
                    enabled = !state.submitting,
                    label = { Text(CREATE_SESSION_NAME_LABEL) },
                    colors = fieldColors,
                    textStyle = PocketShellType.bodyMono,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CREATE_SESSION_NAME_TAG),
                )

                if (engineProfiles.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                        SectionHeader(label = CREATE_SESSION_PROFILE_LABEL)
                        SegmentedToggle(
                            labels = engineProfiles.map { it.name },
                            selectedIndex = selectedProfileIndex,
                            onSelected = { index -> form.onProfileChange(engineProfiles[index].name) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = PICKER_SEGMENT_HEIGHT)
                                .testTag(CREATE_SESSION_PROFILE_TAG),
                            fillSegments = true,
                            segmentTag = { index -> createSessionProfileTag(engineProfiles[index].name) },
                        )
                    }
                }

                Text(
                    text = "These options only affect the session you are about to start.",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.metadata,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                    Text(
                        text = "In ${defaultSessionName(form.folder).ifBlank { "current workspace" }}",
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.bodyDense,
                    )
                    DIRECT_SESSION_PROGRAMS.forEach { program ->
                        val engine = enginesByProgram[program]
                        val createable = program.id == "shell" || isCreateable(engine)
                        val checking = program.id != "shell" &&
                            state.enginesLoading && engine == null
                        val blockedByFailure = program.id != "shell" &&
                            state.enginesFailure != null && engine == null
                        val selected = selectedProgram.id == program.id
                        val subtitle = when {
                            program.id == "shell" -> program.description
                            checking -> "Checking availability…"
                            blockedByFailure -> "Availability could not be checked"
                            createable -> program.description
                            else -> "Not available"
                        }
                        ListRow(
                            title = program.label,
                            subtitle = subtitle,
                            leading = {
                                SessionKindMark(
                                    agent = program.id,
                                    showShell = true,
                                )
                            },
                            trailing = if (selected) {
                                {
                                    Icon(
                                        imageVector = PocketShellIcons.Check,
                                        contentDescription = "Selected",
                                        tint = PocketShellColors.Accent,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = when {
                                state.submitting || checking || blockedByFailure -> null
                                program.id == "shell" -> {
                                    { form.onKindChange(CreateSessionKind.Shell) }
                                }
                                createable && engine != null -> {
                                    {
                                        form.onKindChange(CreateSessionKind.Agent)
                                        form.onEngineChange(engine.id)
                                    }
                                }
                                else -> { { unavailableProgram = program } }
                            },
                            modifier = Modifier.testTag(
                                if (program.id == "shell") {
                                    CREATE_SESSION_TYPE_SHELL_TAG
                                } else {
                                    createSessionEngineTag(program.id)
                                },
                            ),
                        )
                    }
                    ListRow(
                        title = "More options",
                        subtitle = "Name and profile",
                        onClick = { if (!state.submitting) optionsOpen = true },
                        modifier = Modifier.testTag(CREATE_SESSION_OPTIONS_TAG),
                    )
                }
            }

        }

        if (unavailableProgram == null) {
            PocketShellButton(
                text = when {
                    state.submitting -> "Starting…"
                    optionsOpen -> "Apply options"
                    else -> "$CREATE_SESSION_SUBMIT_LABEL ${selectedProgram.label}"
                },
                onClick = { onSubmit(form.toRequest(state.engines, state.profiles)) },
                variant = ButtonVariant.Primary,
                enabled = unavailableProgram == null &&
                    form.canSubmitWith(available) &&
                    !state.submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PocketShellSpacing.lg)
                    .padding(bottom = PocketShellSpacing.lg)
                    .testTag(CREATE_SESSION_SUBMIT_TAG),
            )
        }
    }
}
