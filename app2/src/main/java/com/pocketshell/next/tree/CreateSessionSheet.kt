package com.pocketshell.next.tree

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pocketshell.core.hostapi.Backend
import com.pocketshell.core.hostapi.EngineInfo
import com.pocketshell.core.hostapi.ProfileInfo
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.components.SheetHeader
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
const val CREATE_SESSION_TYPE_AGENT_TAG: String = "create-session-type-agent"
const val CREATE_SESSION_ENGINE_TAG_PREFIX: String = "create-session-engine-"
const val CREATE_SESSION_PROFILE_TAG: String = "create-session-profile"
const val CREATE_SESSION_BACKEND_DEFAULT_TAG: String = "create-session-backend-default"
const val CREATE_SESSION_BACKEND_TMUX_TAG: String = "create-session-backend-tmux"
const val CREATE_SESSION_BACKEND_APLEXER_TAG: String = "create-session-backend-aplexer"
const val CREATE_SESSION_NO_ENGINES_TAG: String = "create-session-no-engines"

fun createSessionEngineTag(engineId: String): String = "$CREATE_SESSION_ENGINE_TAG_PREFIX$engineId"

fun createSessionProfileTag(profileName: String): String = "$CREATE_SESSION_PROFILE_TAG-$profileName"

internal const val CREATE_SESSION_TITLE = "New session"
internal const val CREATE_SESSION_FOLDER_LABEL = "Folder"
internal const val CREATE_SESSION_NAME_LABEL = "Session name"
internal const val CREATE_SESSION_SUBMIT_LABEL = "Create"
internal const val CREATE_SESSION_CANCEL_LABEL = "Cancel"
internal const val CREATE_SESSION_HINT =
    "The session starts detached in this folder. Leave the folder blank to use " +
        "the host's default."
internal const val CREATE_SESSION_TYPE_LABEL = "Session type"
internal const val CREATE_SESSION_ENGINE_LABEL = "Agent engine"
internal const val CREATE_SESSION_PROFILE_LABEL = "Profile"
internal const val CREATE_SESSION_BACKEND_LABEL = "Backend"
internal const val CREATE_SESSION_NO_ENGINES =
    "No agent engines are available on this host."
internal const val CREATE_SESSION_ENGINE_HINT =
    "The engine will auto-start in the new pane."
internal const val CREATE_SESSION_BACKEND_HINT =
    "Default uses the host's [backends] config."
internal const val CREATE_SESSION_LOADING_ENGINES = "Loading engines…"

private val CREATE_SESSION_TYPE_LABELS = listOf("Shell", "Agent")
private val CREATE_SESSION_BACKEND_LABELS = listOf("Default", "tmux", "aplexer")
private val PICKER_SEGMENT_HEIGHT = 48.dp
private const val CREATE_SESSION_HEIGHT_FRACTION = 0.85f
private val CREATE_SESSION_MAX_HEIGHT = 560.dp

/** Shell (plain pane) vs Agent (host starts an engine in the new session). */
enum class CreateSessionKind { Shell, Agent }

/**
 * Backend override for `sessions create --backend`.
 *
 * [HostDefault] omits the flag so the host's `[backends]` config stays in
 * charge — an explicit `tmux`/`aplexer` is what beats that config.
 */
enum class CreateSessionBackend {
    HostDefault,
    Tmux,
    Aplexer,
    ;

    val flag: String?
        get() = when (this) {
            HostDefault -> null
            Tmux -> Backend.WIRE_TMUX
            Aplexer -> Backend.WIRE_APLEXER
        }
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
    val backend: String? = null,
)

/**
 * The only rows the Agent engine picker may offer: enabled AND available AND
 * the host's own `available_for_create` verdict. Disabled, missing-harness,
 * and not-createable rows stay off the chips (issue #2439 / #2522). Aplexer's
 * `shell` engine is never an agent, even if a host listed it as createable.
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
 * `:` and `.` are rewritten to `-` because tmux rejects both in a session name
 * (`:` separates a session from its window, `.` a window from its pane), and a
 * folder called `agent.v2` would otherwise prefill a name the host must refuse.
 * Only the DERIVED default is rewritten; a name the user types is sent verbatim
 * and the host's own validation answers for it.
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
    return segment.map { char -> if (char == ':' || char == '.') '-' else char }
        .joinToString("")
        .trim('-')
}

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
 * Kind defaults to [CreateSessionKind.Shell] so a one-tap create (journey J04)
 * is still a plain shell session; Agent is an explicit pick.
 */
@Stable
class CreateSessionFormState(initialFolder: String = "") {

    var folder: String by mutableStateOf(initialFolder)
        private set

    var name: String by mutableStateOf(defaultSessionName(initialFolder))
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

    var backend: CreateSessionBackend by mutableStateOf(CreateSessionBackend.HostDefault)
        private set

    fun onFolderChange(value: String) {
        folder = value
        if (!nameEdited) name = defaultSessionName(value)
    }

    fun onNameChange(value: String) {
        name = value
        nameEdited = true
    }

    fun onKindChange(value: CreateSessionKind) {
        kind = value
    }

    fun onEngineChange(value: String?) {
        engineId = value
        profileName = null
    }

    fun onProfileChange(value: String?) {
        profileName = value
    }

    fun onBackendChange(value: CreateSessionBackend) {
        backend = value
    }

    /** The host requires a name, so a blank one can never be submitted. */
    val canSubmit: Boolean get() = name.isNotBlank()

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
            backend = backend.flag,
        )
    }
}

/**
 * The create-session bottom sheet (rewrite task U-6, journey J04, issue #2522).
 *
 * Folder + name, plus the v0.4.47 create interaction restored: Shell vs Agent,
 * an engine/profile picker fed by the host registry, and tmux vs aplexer.
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
    form: CreateSessionFormState = remember(defaultFolder) {
        CreateSessionFormState(defaultFolder)
    },
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
    val selectedEngine = if (form.kind == CreateSessionKind.Agent) {
        available.firstOrNull { it.id == form.engineId } ?: available.firstOrNull()
    } else {
        null
    }
    val engineProfiles = profilesForEngine(state.profiles, selectedEngine?.id)
    val selectedProfileIndex = engineProfiles
        .indexOfFirst { it.name == form.profileName }
        .let { index -> if (index >= 0) index else engineProfiles.indexOfFirst { it.isDefault } }
        .coerceAtLeast(0)

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
                title = CREATE_SESSION_TITLE,
                subtitle = CREATE_SESSION_HINT,
                titleTestTag = CREATE_SESSION_TITLE_TAG,
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

            Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                SectionHeader(label = CREATE_SESSION_TYPE_LABEL)
                SegmentedToggle(
                    labels = CREATE_SESSION_TYPE_LABELS,
                    selectedIndex = if (form.kind == CreateSessionKind.Shell) 0 else 1,
                    onSelected = { index ->
                        form.onKindChange(
                            if (index == 0) CreateSessionKind.Shell else CreateSessionKind.Agent,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PICKER_SEGMENT_HEIGHT),
                    fillSegments = true,
                    segmentTag = { index ->
                        if (index == 0) CREATE_SESSION_TYPE_SHELL_TAG else CREATE_SESSION_TYPE_AGENT_TAG
                    },
                )
            }

            if (form.kind == CreateSessionKind.Agent) {
                AgentEnginePicker(
                    available = available,
                    selectedEngineId = selectedEngine?.id,
                    loading = state.enginesLoading && available.isEmpty(),
                    failure = state.enginesFailure,
                    enabled = !state.submitting,
                    onSelect = form::onEngineChange,
                )
                if (engineProfiles.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                        SectionHeader(label = CREATE_SESSION_PROFILE_LABEL)
                        SegmentedToggle(
                            labels = engineProfiles.map { it.name },
                            selectedIndex = selectedProfileIndex,
                            onSelected = { index ->
                                form.onProfileChange(engineProfiles[index].name)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = PICKER_SEGMENT_HEIGHT)
                                .testTag(CREATE_SESSION_PROFILE_TAG),
                            fillSegments = true,
                            segmentTag = { index ->
                                createSessionProfileTag(engineProfiles[index].name)
                            },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                SectionHeader(label = CREATE_SESSION_BACKEND_LABEL)
                SegmentedToggle(
                    labels = CREATE_SESSION_BACKEND_LABELS,
                    selectedIndex = form.backend.ordinal,
                    onSelected = { index ->
                        form.onBackendChange(CreateSessionBackend.entries[index])
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PICKER_SEGMENT_HEIGHT),
                    fillSegments = true,
                    segmentTag = { index ->
                        when (index) {
                            0 -> CREATE_SESSION_BACKEND_DEFAULT_TAG
                            1 -> CREATE_SESSION_BACKEND_TMUX_TAG
                            else -> CREATE_SESSION_BACKEND_APLEXER_TAG
                        }
                    },
                )
                Text(
                    text = CREATE_SESSION_BACKEND_HINT,
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.labelMono,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(bottom = PocketShellSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(
                space = PocketShellSpacing.sm,
                alignment = Alignment.End,
            ),
        ) {
            PocketShellButton(
                text = CREATE_SESSION_CANCEL_LABEL,
                onClick = onCancel,
                variant = ButtonVariant.Text,
                enabled = !state.submitting,
                modifier = Modifier.testTag(CREATE_SESSION_CANCEL_TAG),
            )
            PocketShellButton(
                text = if (state.submitting) "Creating…" else CREATE_SESSION_SUBMIT_LABEL,
                onClick = { onSubmit(form.toRequest(state.engines, state.profiles)) },
                variant = ButtonVariant.Primary,
                enabled = form.canSubmitWith(available) && !state.submitting,
                modifier = Modifier.testTag(CREATE_SESSION_SUBMIT_TAG),
            )
        }
    }
}

@Composable
private fun AgentEnginePicker(
    available: List<EngineInfo>,
    selectedEngineId: String?,
    loading: Boolean,
    failure: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
        when {
            loading -> Text(
                text = CREATE_SESSION_LOADING_ENGINES,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.labelMono,
            )
            available.isEmpty() -> Text(
                text = failure ?: CREATE_SESSION_NO_ENGINES,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.labelMono,
                modifier = Modifier.testTag(CREATE_SESSION_NO_ENGINES_TAG),
            )
            else -> {
                SectionHeader(label = CREATE_SESSION_ENGINE_LABEL)
                SegmentedToggle(
                    labels = available.map { it.label },
                    selectedIndex = available
                        .indexOfFirst { it.id == selectedEngineId }
                        .coerceAtLeast(0),
                    onSelected = { index ->
                        if (enabled) onSelect(available[index].id)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PICKER_SEGMENT_HEIGHT),
                    fillSegments = true,
                    segmentTag = { index -> createSessionEngineTag(available[index].id) },
                )
                Text(
                    text = CREATE_SESSION_ENGINE_HINT,
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.labelMono,
                )
            }
        }
    }
}
