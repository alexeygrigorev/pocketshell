package com.pocketshell.app.projects

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.app.assistant.AssistantUiState
import com.pocketshell.app.assistant.FolderCandidate
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.settings.HostDetailViewMode
import com.pocketshell.app.share.FilenameSanitiser
import com.pocketshell.app.share.ShareUploader
import com.pocketshell.app.voice.AssistantCorrectionDictation
import com.pocketshell.app.voice.AssistantDictationTextEvent
import com.pocketshell.app.voice.AssistantStrip
import com.pocketshell.app.voice.DictateDotIcon
import com.pocketshell.app.voice.InlineDictationErrorStrip
import com.pocketshell.app.voice.appendDictationText
import com.pocketshell.app.voice.toMicButtonState
import com.pocketshell.uikit.components.MicButton
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.PocketShellColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Per-host folder list — issue #171.
 *
 * The default destination after a user taps a host card. The screen
 * renders a
 * folder header per discovered cwd (auto-discovered from
 * `pane_current_path` / `session_path` with the user's
 * `ProjectRootEntity` rows overlaid), with the folder's sessions
 * listed inline below as tappable `SessionRow`s — the session name is
 * visible in the list so callers can drill straight into a session by
 * its name.
 *
 * ## Interaction
 *
 *  - Tap a session row → fires `onOpenSession` with the session name +
 *    folder path so the navigator can route to `AppDestination.TmuxSession`.
 *  - Tap an empty (watched) folder row → opens the
 *    [SessionTypePickerSheet] pre-filled with that folder's cwd.
 *  - Tap the "+ New session in <folder>" button → opens the
 *    [SessionTypePickerSheet] pre-filled with that folder's cwd.
 *  - Tap the bottom-right FAB → opens the
 *    [SessionTypePickerSheet] without a pre-filled folder (the user
 *    types one). Per the refinement-comment AC: "+ New session"
 *    prompts for type (agent / shell) with an agent CLI sub-picker.
 *  - Tap the app-bar settings gear → opens workspace settings where
 *    roots and the default tree/flat mode are configured.
 */
@Composable
fun FolderListScreen(
    hostId: Long,
    hostName: String,
    hostname: String,
    port: Int,
    username: String,
    keyPath: String,
    passphrase: CharArray?,
    onBack: () -> Unit,
    onOpenSession: (sessionName: String, startDirectory: String?) -> Unit,
    /**
     * Fired after the SessionTypePickerSheet successfully created a
     * session on the remote. The caller (MainActivity) routes to
     * `AppDestination.TmuxSession` with the resolved session name and
     * cwd. The startCommand is non-null when the user picked "Agent" —
     * by the time this fires the agent CLI has already been
     * `send-keys`d into the new pane by the gateway, so the caller
     * only needs to attach.
     */
    onSessionCreated: (sessionName: String, cwd: String) -> Unit,
    /**
     * Issue #230: open the GitHub repos browser for this host. The
     * caller (MainActivity) routes to `AppDestination.RepoBrowser` with
     * the same SSH credentials this screen already holds.
     */
    onBrowseRepos: (cloneRoot: String?) -> Unit,
    onOpenPortForwarding: () -> Unit = {},
    onOpenWorkspaceSettings: () -> Unit = {},
    /**
     * Issue #264: open the per-folder `.env` / `.envrc` key manager.
     * Fired with the tapped folder's canonical path + label so the
     * caller (MainActivity) can route to `AppDestination.EnvFiles`. The
     * second argument is the full discovered folder set (path → label)
     * so the env screen's "Copy from another folder" picker is sourced
     * from the same data the user saw here (D24).
     */
    onEditEnv: (path: String, label: String, allFolders: List<Pair<String, String>>) -> Unit,
    onAssistantNavigate: (AppDestination) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: FolderListViewModel = hiltViewModel(),
    assistantDictationViewModel: InlineDictationViewModel? = null,
    suggestStartDirectories: (suspend (String) -> List<String>)? = null,
    hostDetailViewMode: HostDetailViewMode = HostDetailViewMode.Tree,
) {
    LaunchedEffect(hostId, hostname, port, username, keyPath) {
        viewModel.bind(
            hostId = hostId,
            hostName = hostName,
            hostname = hostname,
            port = port,
            username = username,
            keyPath = keyPath,
            passphrase = passphrase,
        )
    }
    // Issue #171 round 2: pause the gateway poll when the screen
    // leaves the tree (e.g. the user navigates onward to a tmux
    // session). The poll is a per-host SSH `tmux list-sessions /
    // list-panes / agent detection` round-trip and would otherwise
    // race the TmuxSessionScreen's own SSH attach on the same host,
    // making the attach feel sluggish on slower emulators.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopPolling() }
    }
    val state by viewModel.state.collectAsState()
    val actionStatus by viewModel.actionStatus.collectAsState()
    val assistantState by viewModel.assistantState.collectAsState()
    val assistantDictationUiState = remember(assistantDictationViewModel) {
        assistantDictationViewModel?.uiState
            ?: MutableStateFlow(InlineDictationViewModel.UiState())
    }
    val assistantDictationState by assistantDictationUiState.collectAsState()
    val context = LocalContext.current
    val showFlatFolderList = hostDetailViewMode == HostDetailViewMode.Flat
    var pickerFolder by remember { mutableStateOf<PickerTarget?>(null) }
    var actionFolder by remember { mutableStateOf<PickerTarget?>(null) }
    var emptyProjectFolder by remember { mutableStateOf<PickerTarget?>(null) }
    var importFolder by remember { mutableStateOf<PickerTarget?>(null) }
    var rootAddSheet by remember { mutableStateOf<FolderTreeRoot?>(null) }
    var showAssistant by remember { mutableStateOf(false) }
    var dictationTarget by remember { mutableStateOf(AssistantDictationTarget.Prompt) }
    var dictationEventId by remember { mutableStateOf(0L) }
    var promptDictationEvent by remember { mutableStateOf<AssistantDictationTextEvent?>(null) }
    var correctionDictationEvent by remember { mutableStateOf<AssistantDictationTextEvent?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            assistantDictationViewModel?.onMicTap()
        } else {
            assistantDictationViewModel?.surfacePermissionDenied()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.assistantNavRequests.collect { onAssistantNavigate(it) }
    }
    LaunchedEffect(assistantDictationViewModel) {
        assistantDictationViewModel?.transcriptions?.collect { text ->
            val event = AssistantDictationTextEvent(id = ++dictationEventId, text = text)
            when (dictationTarget) {
                AssistantDictationTarget.Prompt -> promptDictationEvent = event
                AssistantDictationTarget.Correction -> correctionDictationEvent = event
            }
        }
    }
    val onAssistantMicTap: (AssistantDictationTarget) -> Unit = onAssistantMicTap@ { target ->
        val dictationViewModel = assistantDictationViewModel ?: return@onAssistantMicTap
        dictationTarget = target
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            dictationViewModel.onMicTap()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val target = importFolder
        importFolder = null
        if (uri != null && target != null) {
            viewModel.importFileIntoFolder(
                folderPath = target.path,
                payload = folderImportPayload(
                    resolver = context.contentResolver,
                    uri = uri,
                ),
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(FOLDER_LIST_SCREEN_TAG),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FolderListAppBar(
                hostName = hostName,
                onBack = onBack,
                onBrowseRepos = { onBrowseRepos(null) },
                onOpenWorkspaceSettings = onOpenWorkspaceSettings,
                onOpenAssistant = { showAssistant = true },
            )
            when (val s = state) {
                FolderListUiState.Loading -> LoadingPanel()
                is FolderListUiState.Failed -> ErrorPanel(message = s.message, onRetry = viewModel::refresh)
                is FolderListUiState.ConnectError -> ErrorPanel(
                    message = s.message.ifBlank { "Couldn't reach $hostName." },
                    onRetry = viewModel::refresh,
                )
                FolderListUiState.ToolUnavailable -> ErrorPanel(
                    message = "tmux is not installed on $hostName.",
                    onRetry = viewModel::refresh,
                )
                is FolderListUiState.Ready -> FolderListContent(
                    folders = s.folders,
                    treeRoots = s.treeRoots,
                    expandedProjectPaths = s.expandedProjectPaths,
                    portForwarding = s.portForwarding,
                    showFlatFolderList = showFlatFolderList,
                    actionStatus = actionStatus,
                    onDismissActionStatus = viewModel::clearActionStatus,
                    onOpenPortForwarding = onOpenPortForwarding,
                    onSessionClick = { folderPath, sessionName ->
                        onOpenSession(
                            sessionName,
                            folderPath.takeUnless { it == FolderListViewModel.UNTRACKED_PATH },
                        )
                    },
                    onCreateInFolder = { row ->
                        pickerFolder = PickerTarget(path = row.path, label = row.label)
                    },
                    onFolderActions = { row ->
                        actionFolder = PickerTarget(path = row.path, label = row.label)
                    },
                    onCreateInRoot = { root ->
                        rootAddSheet = root
                    },
                    onRootActions = { root ->
                        actionFolder = PickerTarget(path = root.path, label = root.label)
                    },
                    onToggleProjectExpanded = { row -> viewModel.toggleProjectExpanded(row.path) },
                    onEditEnv = { row ->
                        // Copy-source set = every real (non-untracked)
                        // discovered folder the user can see, so the env
                        // screen's picker stays inside the known set (D24).
                        val sources = s.folders
                            .filter { it.path != FolderListViewModel.UNTRACKED_PATH }
                            .map { it.path to it.label }
                        onEditEnv(row.path, row.label, sources)
                    },
                )
            }
        }

        FloatingActionButton(
            onClick = {
                // FAB tap without a folder context lands the picker on
                // the user's home directory by default; the user can
                // edit before confirming.
                pickerFolder = PickerTarget(path = "~", label = "home")
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
                .size(56.dp)
                .testTag(FOLDER_LIST_NEW_SESSION_FAB_TAG),
            shape = CircleShape,
            containerColor = PocketShellColors.Accent,
            contentColor = PocketShellColors.OnAccent,
        ) {
            Text(text = "+", fontSize = 28.sp, fontWeight = FontWeight.Medium)
        }

        if (showAssistant || assistantState !is AssistantUiState.Idle) {
            HostDetailAssistantPanel(
                state = assistantState,
                onSubmit = viewModel::startAssistant,
                onConfirm = viewModel::confirmAssistantAction,
                onCorrect = viewModel::correctAssistantAction,
                onCancel = viewModel::cancelAssistantAction,
                onRetry = viewModel::retryAssistantAction,
                onDismiss = {
                    viewModel.dismissAssistant()
                    showAssistant = false
                },
                onChoose = viewModel::chooseAssistantFolder,
                onCancelChoice = viewModel::cancelAssistantChoice,
                dictationState = assistantDictationState,
                promptDictation = promptDictationEvent,
                onPromptDictationConsumed = { promptDictationEvent = null },
                correctionDictation = AssistantCorrectionDictation(
                    recording = assistantDictationState.recording,
                    dictatedText = correctionDictationEvent,
                    onDictatedTextConsumed = { correctionDictationEvent = null },
                    onMicTap = { onAssistantMicTap(AssistantDictationTarget.Correction) },
                ),
                onPromptMicTap = { onAssistantMicTap(AssistantDictationTarget.Prompt) },
                onDismissDictationError = { assistantDictationViewModel?.clearError() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 88.dp),
            )
        }
    }

    pickerFolder?.let { target ->
        SessionTypePickerSheet(
            folderPath = target.path,
            folderLabel = target.label,
            onDismiss = { pickerFolder = null },
            suggestStartDirectories = suggestStartDirectories,
            onCreate = { choice ->
                pickerFolder = null
                val newName = derivedSessionName(
                    choice = choice,
                    // Best-effort remote $HOME so directories under home
                    // collapse to their tmuxctl home-relative form. The
                    // authoritative $HOME lives on the remote and isn't
                    // plumbed into this screen yet (#430/#438 own the
                    // gateway/viewmodel), so we infer the conventional
                    // path from the connecting username — correct for the
                    // maintainer's hosts (`/home/<user>`, `/root`).
                    homeDirectory = conventionalRemoteHome(username),
                    // Disambiguate against the session names already
                    // discovered for this host so a genuinely new second
                    // session in the same directory doesn't collide.
                    existingNames = knownSessionNames(state),
                )
                viewModel.createSession(
                    sessionName = newName,
                    cwd = choice.startDirectory,
                    startCommand = choice.startCommand(),
                    onResolved = { resolved ->
                        onSessionCreated(resolved, choice.startDirectory)
                    },
                )
            },
        )
    }

    actionFolder?.let { target ->
        FolderContextActionSheet(
            folderLabel = target.label,
            folderPath = target.path,
            onDismiss = { actionFolder = null },
            onNewSession = {
                actionFolder = null
                pickerFolder = target
            },
            onImport = {
                actionFolder = null
                importFolder = target
                importLauncher.launch("*/*")
            },
            onCloneGitProject = {
                actionFolder = null
                onBrowseRepos(target.path)
            },
            onEmptyProject = {
                actionFolder = null
                emptyProjectFolder = target
            },
        )
    }

    emptyProjectFolder?.let { target ->
        EmptyProjectDialog(
            folderLabel = target.label,
            onDismiss = { emptyProjectFolder = null },
            onCreate = { name ->
                emptyProjectFolder = null
                viewModel.createEmptyProject(parentPath = target.path, folderName = name)
            },
        )
    }

    rootAddSheet?.let { root ->
        RootProjectAddSheet(
            root = root,
            onDismiss = { rootAddSheet = null },
            onStartSession = { project ->
                rootAddSheet = null
                pickerFolder = PickerTarget(path = project.path, label = project.label)
            },
            onCreateEmptyProject = {
                rootAddSheet = null
                emptyProjectFolder = PickerTarget(path = root.path, label = root.label)
            },
            onCloneGitProject = {
                rootAddSheet = null
                onBrowseRepos(root.path)
            },
        )
    }
}

private data class PickerTarget(val path: String, val label: String)

private enum class AssistantDictationTarget { Prompt, Correction }

private fun folderImportPayload(resolver: ContentResolver, uri: Uri): FolderImportPayload {
    val displayName = ShareUploader.queryUriDisplayName(resolver, uri)
        ?: uri.lastPathSegment
        ?: "imported"
    val mime = resolver.getType(uri)
    val sanitised = FilenameSanitiser.sanitise(
        input = displayName,
        defaultExtension = ShareUploader.extensionForMimeType(mime),
    )
    return FolderImportPayload(
        remoteName = sanitised.render(),
        length = queryUriSize(resolver, uri),
        openStream = { resolver.openInputStream(uri) },
    )
}

private fun queryUriSize(resolver: ContentResolver, uri: Uri): Long? = try {
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index < 0) return@use null
        cursor.getLong(index).takeIf { it > 0L }
    }
} catch (_: Throwable) {
    null
}

/**
 * Derive a tmux session name from the user's picker choice — issue #429.
 *
 * Mirrors the `tmuxctl` (`t`) convention the maintainer already uses on
 * the server: the name encodes the directory (relative to `$HOME` when
 * possible) rather than the old cryptic `<basename>-<6-digit-timestamp>`.
 * See [SessionNameDerivation] for the full convention.
 *
 *  - `~/git/pocketshell` (agent) → `claude-git-pocketshell`
 *  - `/var/log` (shell)          → `var-log`
 *  - `$HOME` itself              → `home-<homeBasename>`
 *
 * @param homeDirectory the remote `$HOME` if known, so paths under home
 *   collapse to their home-relative form (and `~` is recognised). May be
 *   `null` when home is unknown, in which case absolute paths are named
 *   from their full components.
 * @param existingNames session names already present on the host. A
 *   genuinely different second session in the same directory gets a
 *   deterministic `-2`, `-3`, … suffix instead of colliding; an exact
 *   re-pick still attaches via the gateway's `tmux new-session -A`.
 */
internal fun derivedSessionName(
    choice: SessionTypeChoice,
    homeDirectory: String? = null,
    existingNames: Set<String> = emptySet(),
): String = SessionNameDerivation.derive(
    startDirectory = choice.startDirectory,
    homeDirectory = homeDirectory,
    agentCommand = when (choice.type) {
        SessionType.Shell -> null
        SessionType.Agent -> choice.agent?.command
    },
    existingNames = existingNames,
)

/**
 * Conventional remote `$HOME` inferred from the SSH [username] — issue
 * #429. The remote home is what `tmuxctl` keys its naming off, but the
 * authoritative value lives on the remote and is not plumbed into this
 * screen yet (#430/#438 own the gateway/viewmodel that would carry it).
 * Until then this gives the correct home for the maintainer's hosts:
 * `root` → `/root`, anything else → `/home/<user>`. Returns `null` for a
 * blank username so the deriver falls back to absolute-path naming.
 */
internal fun conventionalRemoteHome(username: String): String? {
    val user = username.trim()
    return when {
        user.isEmpty() -> null
        user == "root" -> "/root"
        else -> "/home/$user"
    }
}

/**
 * Session names already discovered for this host, used so a genuinely new
 * second session in the same directory gets a deterministic `-2`/`-3`
 * suffix rather than colliding (issue #429).
 */
internal fun knownSessionNames(state: FolderListUiState): Set<String> =
    when (state) {
        is FolderListUiState.Ready ->
            state.folders.flatMap { it.sessions }.map { it.sessionName }.toSet()
        else -> emptySet()
    }

@Composable
private fun FolderListAppBar(
    hostName: String,
    onBack: () -> Unit,
    onBrowseRepos: () -> Unit,
    onOpenWorkspaceSettings: () -> Unit,
    onOpenAssistant: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(PocketShellColors.Background)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(role = Role.Button, onClick = onBack)
                .testTag(FOLDER_LIST_BACK_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "‹",
                color = PocketShellColors.TextSecondary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = hostName,
            color = PocketShellColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 4.dp)
                .weight(1f)
                .testTag(FOLDER_LIST_TITLE_TAG),
        )
        TopBarIconButton(
            contentDescription = "Browse repos",
            testTag = FOLDER_LIST_BROWSE_REPOS_TAG,
            onClick = onBrowseRepos,
        ) {
            ReposIcon()
        }
        Spacer(modifier = Modifier.size(4.dp))
        TopBarIconButton(
            contentDescription = "Host assistant",
            testTag = FOLDER_LIST_ASSISTANT_TAG,
            onClick = onOpenAssistant,
        ) {
            AssistantMicSparkIcon()
        }
        Spacer(modifier = Modifier.size(4.dp))
        TopBarIconButton(
            contentDescription = "Workspace settings",
            testTag = FOLDER_LIST_WORKSPACE_SETTINGS_TAG,
            onClick = onOpenWorkspaceSettings,
        ) {
            SettingsGearIcon()
        }
    }
}

@Composable
private fun ReposIcon() {
    Canvas(modifier = Modifier.size(20.dp)) {
        val color = PocketShellColors.TextSecondary
        val stroke = 1.7.dp.toPx()
        val x = size.width * 0.34f
        drawLine(
            color = color,
            start = Offset(x, size.height * 0.18f),
            end = Offset(x, size.height * 0.76f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(x, size.height * 0.46f),
            end = Offset(size.width * 0.68f, size.height * 0.32f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.68f, size.height * 0.32f),
            end = Offset(size.width * 0.68f, size.height * 0.74f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color = color, radius = 2.4.dp.toPx(), center = Offset(x, size.height * 0.18f))
        drawCircle(color = color, radius = 2.4.dp.toPx(), center = Offset(x, size.height * 0.78f))
        drawCircle(color = color, radius = 2.4.dp.toPx(), center = Offset(size.width * 0.68f, size.height * 0.32f))
    }
}

@Composable
private fun HostDetailAssistantPanel(
    state: AssistantUiState,
    onSubmit: (String) -> Unit,
    onConfirm: () -> Unit,
    onCorrect: (String) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onChoose: (FolderCandidate) -> Unit,
    onCancelChoice: () -> Unit,
    dictationState: InlineDictationViewModel.UiState,
    promptDictation: AssistantDictationTextEvent?,
    onPromptDictationConsumed: () -> Unit,
    correctionDictation: AssistantCorrectionDictation,
    onPromptMicTap: () -> Unit,
    onDismissDictationError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var prompt by remember { mutableStateOf("") }
    LaunchedEffect(promptDictation?.id) {
        val event = promptDictation ?: return@LaunchedEffect
        prompt = appendDictationText(prompt, event.text)
        onPromptDictationConsumed()
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, PocketShellColors.AccentDim, RoundedCornerShape(12.dp))
            .padding(10.dp)
            .testTag(FOLDER_LIST_ASSISTANT_PANEL_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Assistant",
                color = PocketShellColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(FOLDER_LIST_ASSISTANT_CLOSE_TAG),
            ) {
                Text("Close", color = PocketShellColors.TextSecondary, fontSize = 12.sp)
            }
        }
        if (state !is AssistantUiState.Idle) {
            AssistantStrip(
                state = state,
                onConfirm = onConfirm,
                onCorrect = onCorrect,
                onCancel = onCancel,
                onDismiss = onDismiss,
                onRetry = onRetry,
                onChoose = onChoose,
                onCancelChoice = onCancelChoice,
                correctionDictation = correctionDictation,
            )
        }
        dictationState.error?.let { msg ->
            InlineDictationErrorStrip(message = msg, onDismiss = onDismissDictationError)
        }
        if (state is AssistantUiState.Idle || state is AssistantUiState.Done || state is AssistantUiState.Error) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(FOLDER_LIST_ASSISTANT_PROMPT_TAG),
                    placeholder = {
                        Text("Create a project or start a session", color = PocketShellColors.TextSecondary)
                    },
                    keyboardActions = KeyboardActions(onDone = {
                        val text = prompt.trim()
                        if (text.isNotEmpty()) {
                            prompt = ""
                            onSubmit(text)
                        }
                    }),
                    singleLine = true,
                )
                MicButton(
                    state = dictationState.recording.toMicButtonState(),
                    onClick = onPromptMicTap,
                    modifier = Modifier.testTag(FOLDER_LIST_ASSISTANT_PROMPT_MIC_TAG),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        val text = prompt.trim()
                        if (text.isNotEmpty()) {
                            prompt = ""
                            onSubmit(text)
                        }
                    },
                    modifier = Modifier.testTag(FOLDER_LIST_ASSISTANT_SUBMIT_TAG),
                ) {
                    Text("Ask", color = PocketShellColors.Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun AssistantMicSparkIcon() {
    Box(
        modifier = Modifier
            .size(22.dp)
            .testTag(FOLDER_LIST_ASSISTANT_ICON_TAG),
    ) {
        Icon(
            imageVector = DictateDotIcon,
            contentDescription = null,
            tint = PocketShellColors.TextSecondary,
            modifier = Modifier
                .size(18.dp)
                .align(Alignment.CenterStart),
        )
        Canvas(
            modifier = Modifier
                .size(9.dp)
                .align(Alignment.TopEnd),
        ) {
            val diamond = Path().apply {
                moveTo(size.width * 0.5f, 0f)
                lineTo(size.width, size.height * 0.5f)
                lineTo(size.width * 0.5f, size.height)
                lineTo(0f, size.height * 0.5f)
                close()
            }
            drawPath(path = diamond, color = PocketShellColors.Accent)
            drawCircle(
                color = PocketShellColors.Accent,
                radius = size.minDimension * 0.12f,
                center = Offset(size.width * 0.08f, size.height * 0.12f),
            )
        }
    }
}

@Composable
private fun TopBarIconButton(
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color = PocketShellColors.SurfaceElev, shape = CircleShape)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun SettingsGearIcon() {
    val color = PocketShellColors.TextSecondary
    Canvas(modifier = Modifier.size(20.dp)) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val minSide = min(size.width, size.height)
        val toothRadius = minSide * 0.055f
        val toothDistance = minSide * 0.37f
        repeat(8) { index ->
            val angle = index * PI.toFloat() / 4f
            drawCircle(
                color = color,
                radius = toothRadius,
                center = androidx.compose.ui.geometry.Offset(
                    x = center.x + cos(angle) * toothDistance,
                    y = center.y + sin(angle) * toothDistance,
                ),
            )
        }
        drawCircle(
            color = color,
            radius = minSide * 0.28f,
            center = center,
            style = Stroke(width = minSide * 0.12f),
        )
        drawCircle(
            color = color,
            radius = minSide * 0.075f,
            center = center,
        )
    }
}

@Composable
private fun LoadingPanel() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag(FOLDER_LIST_LOADING_TAG),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = PocketShellColors.Accent)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Loading workspace tree",
            color = PocketShellColors.TextSecondary,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .testTag(FOLDER_LIST_ERROR_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            color = PocketShellColors.Text,
            fontSize = 14.sp,
        )
        TextButton(onClick = onRetry, modifier = Modifier.testTag(FOLDER_LIST_RETRY_TAG)) {
            Text("Retry", color = PocketShellColors.Accent)
        }
    }
}

@Composable
private fun FolderListContent(
    folders: List<FolderRow>,
    treeRoots: List<FolderTreeRoot>,
    expandedProjectPaths: Set<String>,
    portForwarding: HostPortForwardingSummary,
    showFlatFolderList: Boolean,
    actionStatus: FolderActionStatus,
    onDismissActionStatus: () -> Unit,
    onOpenPortForwarding: () -> Unit,
    onSessionClick: (folderPath: String, sessionName: String) -> Unit,
    onCreateInFolder: (FolderRow) -> Unit,
    onFolderActions: (FolderRow) -> Unit,
    onCreateInRoot: (FolderTreeRoot) -> Unit,
    onRootActions: (FolderTreeRoot) -> Unit,
    onToggleProjectExpanded: (FolderRow) -> Unit,
    onEditEnv: (FolderRow) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(FOLDER_LIST_CONTENT_TAG),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = 12.dp,
            end = 12.dp,
            bottom = FolderListFabContentClearance,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (actionStatus !is FolderActionStatus.Idle) {
            item {
                FolderActionStatusBanner(
                    status = actionStatus,
                    onDismiss = onDismissActionStatus,
                )
            }
        }
        if (!showFlatFolderList && treeRoots.isEmpty()) {
            item {
                EmptyState()
            }
        } else if (!showFlatFolderList) {
            items(treeRoots, key = { it.path }) { root ->
                FolderTreeRootGroup(
                    root = root,
                    expandedProjectPaths = expandedProjectPaths,
                    onSessionClick = onSessionClick,
                    onCreateInFolder = onCreateInFolder,
                    onFolderActions = onFolderActions,
                    onCreateInRoot = onCreateInRoot,
                    onRootActions = onRootActions,
                    onToggleProjectExpanded = onToggleProjectExpanded,
                    onEditEnv = onEditEnv,
                )
            }
        } else if (folders.isEmpty()) {
            item {
                EmptyState()
            }
        } else {
            items(folders, key = { it.path }) { folder ->
                FolderGroup(
                    folder = folder,
                    expanded = true,
                    onSessionClick = onSessionClick,
                    onCreateInFolder = onCreateInFolder,
                    onFolderActions = onFolderActions,
                    onToggleExpanded = {},
                    onEditEnv = onEditEnv,
                )
            }
        }
        if (portForwarding.shouldShowSummary) {
            item {
                PortForwardingSummaryCard(
                    summary = portForwarding,
                    onOpen = onOpenPortForwarding,
                )
            }
        }
        item {
            Spacer(
                modifier = Modifier
                    .height(8.dp)
                    .testTag(FOLDER_LIST_BOTTOM_SPACER_TAG),
            )
        }
    }
}

private val HostPortForwardingSummary.shouldShowSummary: Boolean
    get() = active || activeTunnelCount > 0 || discoveredPorts.isNotEmpty()

@Composable
private fun PortForwardingSummaryCard(
    summary: HostPortForwardingSummary,
    onOpen: () -> Unit,
) {
    val statusText = when {
        summary.active -> "${summary.activeTunnelCount} active"
        summary.discoveredCount > 0 -> "${summary.discoveredCount} discovered"
        else -> "Off"
    }
    val detailText = when {
        summary.active -> "Foreground forwarding service is running."
        summary.discoveredCount > 0 -> "Ports are discovered only; no local tunnels are running."
        else -> "Auto-forward is off by default."
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(FOLDER_LIST_PORT_FORWARDING_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Port forwarding",
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = detailText,
                    color = PocketShellColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
            Text(
                text = statusText,
                color = if (summary.active) PocketShellColors.Green else PocketShellColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        summary.discoveredPorts.take(3).forEach { port ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = port.remotePort.toString(),
                    color = PocketShellColors.Text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(56.dp),
                )
                Text(
                    text = port.process.ifBlank { "unknown process" },
                    color = PocketShellColors.TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when (port.status) {
                        HostPortForwardingPortStatus.FORWARDING -> "Forwarding"
                        HostPortForwardingPortStatus.DISCOVERED -> "Discovered"
                    },
                    color = when (port.status) {
                        HostPortForwardingPortStatus.FORWARDING -> PocketShellColors.Green
                        HostPortForwardingPortStatus.DISCOVERED -> PocketShellColors.TextMuted
                    },
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun FolderActionStatusBanner(
    status: FolderActionStatus,
    onDismiss: () -> Unit,
) {
    val message = when (status) {
        FolderActionStatus.Idle -> return
        is FolderActionStatus.Running -> status.label
        is FolderActionStatus.Succeeded -> status.message
        is FolderActionStatus.Failed -> status.message
    }
    val color = when (status) {
        is FolderActionStatus.Failed -> PocketShellColors.Red
        else -> PocketShellColors.Accent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(FOLDER_LIST_ACTION_STATUS_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = PocketShellColors.Text,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (status !is FolderActionStatus.Running) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(FOLDER_LIST_ACTION_STATUS_DISMISS_TAG),
            ) {
                Text("Dismiss", color = color, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(FOLDER_LIST_EMPTY_TAG),
    ) {
        Text(
            text = "No active sessions",
            color = PocketShellColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Tap + to start a new session here.",
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun FolderTreeRootGroup(
    root: FolderTreeRoot,
    expandedProjectPaths: Set<String>,
    onSessionClick: (folderPath: String, sessionName: String) -> Unit,
    onCreateInFolder: (FolderRow) -> Unit,
    onFolderActions: (FolderRow) -> Unit,
    onCreateInRoot: (FolderTreeRoot) -> Unit,
    onRootActions: (FolderTreeRoot) -> Unit,
    onToggleProjectExpanded: (FolderRow) -> Unit,
    onEditEnv: (FolderRow) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(folderTreeRootTestTag(root.path)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FolderTreeRootHeader(
            root = root,
            onCreateInRoot = { onCreateInRoot(root) },
            onRootActions = { onRootActions(root) },
        )
        if (root.folders.isEmpty()) {
            EmptyRootHint(candidateCount = root.addSheetProjects.size, onCreate = { onCreateInRoot(root) })
        } else {
            Column(
                modifier = Modifier.padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                root.folders.forEachIndexed { index, folder ->
                    FolderGroup(
                        folder = folder,
                        expanded = folder.path in expandedProjectPaths,
                        showTreeBranch = true,
                        lastInTree = index == root.folders.lastIndex,
                        onSessionClick = onSessionClick,
                        onCreateInFolder = onCreateInFolder,
                        onFolderActions = onFolderActions,
                        onToggleExpanded = { onToggleProjectExpanded(folder) },
                        onEditEnv = onEditEnv,
                    )
                }
            }
        }
    }
}

/**
 * Belt-and-suspenders UI fallback (#438): the view-model's
 * [FolderListViewModel.defaultLabelForPath] already guarantees a
 * non-blank, meaningful label, but the header composables defend
 * against any future model regression so the tree can never paint a
 * blank or lone-`/` title. Prefers the supplied label, then a label
 * derived from the path, then "Untracked".
 */
private fun folderDisplayLabel(label: String, path: String): String =
    label.ifBlank { FolderListViewModel.defaultLabelForPath(path) }

@Composable
private fun FolderTreeRootHeader(
    root: FolderTreeRoot,
    onCreateInRoot: () -> Unit,
    onRootActions: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folderDisplayLabel(root.label, root.path),
                color = PocketShellColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .testTag(folderTreeRootLabelTag(root.path))
                    .semantics { contentDescription = root.path },
            )
            RootCountText(root = root)
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (root.path != FolderListViewModel.OTHER_ROOT_PATH) {
            CompactTreeIconButton(
                label = "...",
                contentDescription = "Root actions",
                onClick = onRootActions,
                testTag = folderTreeRootActionsTestTag(root.path),
            )
            Spacer(modifier = Modifier.width(6.dp))
            CompactTreeIconButton(
                label = "+",
                contentDescription = "Add project",
                onClick = onCreateInRoot,
                testTag = folderTreeRootCreateTestTag(root.path),
                accent = true,
            )
        }
    }
}

@Composable
private fun RootCountText(root: FolderTreeRoot) {
    val text = when {
        root.sessionCount > 0 && root.inactiveProjectCount > 0 ->
            "${root.activeProjectCount} active · ${root.sessionCount} sessions"
        root.sessionCount > 0 -> "${root.activeProjectCount} active"
        root.inactiveProjectCount > 0 -> "${root.inactiveProjectCount} inactive"
        else -> "empty"
    }
    Text(
        text = text,
        color = PocketShellColors.TextMuted,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EmptyRootHint(candidateCount: Int, onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp)
            .background(PocketShellColors.Surface.copy(alpha = 0.48f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = if (candidateCount > 0) {
                "$candidateCount inactive project folders available."
            } else {
                "No project folders found under this watched root."
            },
            color = PocketShellColors.Text,
            fontSize = 13.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        CompactTreeIconButton(
            label = "+",
            contentDescription = "Add project",
            onClick = onCreate,
            accent = true,
        )
    }
}

/**
 * Active project row plus optional session children.
 *
 * The project row reads as one compact native row: subtle active/idle
 * dot, folder label, path, counts, and scoped actions. The session list
 * appears only after expansion in tree mode so configured roots stay
 * scannable on a phone-sized host-detail screen.
 */
@Composable
private fun FolderGroup(
    folder: FolderRow,
    expanded: Boolean,
    showTreeBranch: Boolean = false,
    lastInTree: Boolean = true,
    onSessionClick: (folderPath: String, sessionName: String) -> Unit,
    onCreateInFolder: (FolderRow) -> Unit,
    onFolderActions: (FolderRow) -> Unit,
    onToggleExpanded: () -> Unit,
    onEditEnv: (FolderRow) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggleExpanded)
            .testTag(folderRowTestTag(folder.path)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            if (showTreeBranch) {
                TreeBranchConnector(
                    last = lastInTree && !expanded,
                    modifier = Modifier
                        .width(22.dp)
                        .fillMaxHeight(),
                )
            }
            FolderHeader(
                folder = folder,
                expanded = expanded,
                onToggleExpanded = onToggleExpanded,
                onCreateInFolder = { onCreateInFolder(folder) },
                onFolderActions = { onFolderActions(folder) },
                onEditEnv = { onEditEnv(folder) },
                modifier = Modifier.weight(1f),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = if (showTreeBranch) 22.dp else 26.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                folder.sessions.forEachIndexed { index, session ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                    ) {
                        if (showTreeBranch) {
                            TreeBranchConnector(
                                last = index == folder.sessions.lastIndex,
                                modifier = Modifier
                                    .width(22.dp)
                                    .fillMaxHeight(),
                            )
                        }
                        WorkspaceSessionRow(
                            folderPath = folder.path,
                            session = session,
                            onClick = { onSessionClick(folder.path, session.sessionName) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderHeader(
    folder: FolderRow,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCreateInFolder: () -> Unit,
    onFolderActions: () -> Unit,
    onEditEnv: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onToggleExpanded)
            .testTag(folderHeaderClickTestTag(folder.path))
            .padding(start = 4.dp, end = 0.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DisclosureIndicator(
            expanded = expanded,
            modifier = Modifier
                .size(16.dp)
                .testTag(folderDetailDisclosureTestTag(folder.path)),
        )
        Spacer(modifier = Modifier.width(7.dp))
        val countText = projectCountText(folder)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = folderDisplayLabel(folder.label, folder.path),
                color = PocketShellColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .testTag(folderHeaderLabelTag(folder.path))
                    .semantics { contentDescription = folder.path },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    active = folder.sessions.any { it.attached || it.agentKind.isAgent() },
                    modifier = Modifier.testTag(folderStatusDotTestTag(folder.path)),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (folder.sessions.any { it.attached || it.agentKind.isAgent() }) {
                        "active · $countText"
                    } else {
                        "idle · $countText"
                    },
                    color = PocketShellColors.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(folderCountPillTestTag(folder.path)),
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Issue #264: "Env" entry point — only on real folders (the
            // synthetic Untracked group has no filesystem path to manage).
            if (folder.path != FolderListViewModel.UNTRACKED_PATH) {
                CompactTreeIconButton(
                    label = "...",
                    contentDescription = "Project actions",
                    onClick = onFolderActions,
                    testTag = folderDetailActionsTestTag(folder.path),
                )
                CompactTreeIconButton(
                    label = "E",
                    contentDescription = "Environment files",
                    onClick = onEditEnv,
                    testTag = folderDetailEnvTestTag(folder.path),
                )
            }
            CompactTreeIconButton(
                label = "+",
                contentDescription = "New session",
                onClick = onCreateInFolder,
                testTag = folderDetailCreateTestTag(folder.path),
                accent = true,
            )
        }
    }
}

@Composable
private fun WorkspaceSessionRow(
    folderPath: String,
    session: FolderSessionEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAgent = session.agentKind.isAgent()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (isAgent) PocketShellColors.Purple.copy(alpha = 0.10f) else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .testTag(folderDetailRowTestTag(folderPath, session.sessionName)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(26.dp)
                .background(if (isAgent) PocketShellColors.Purple else PocketShellColors.BorderSoft),
        )
        Spacer(modifier = Modifier.width(8.dp))
        StatusDot(
            active = session.attached || session.agentKind.isAgent(),
            modifier = Modifier.testTag(folderSessionStatusDotTestTag(folderPath, session.sessionName)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sessionDisplayTitle(session),
                color = PocketShellColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            sessionSecondaryText(session)?.let { secondary ->
                Text(
                    text = secondary,
                    color = PocketShellColors.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = sessionKindLabel(session),
            color = if (isAgent) PocketShellColors.Purple else PocketShellColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TreeBranchConnector(
    last: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val color = PocketShellColors.Border
        val stroke = 1.dp.toPx()
        val x = 10.dp.toPx()
        val midY = size.height * 0.5f
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, if (last) midY else size.height),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(x, midY),
            end = Offset(size.width, midY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun CompactTreeIconButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    accent: Boolean = false,
) {
    val background = if (accent) {
        PocketShellColors.AccentSoft
    } else {
        PocketShellColors.SurfaceElev.copy(alpha = 0.72f)
    }
    val foreground = if (accent) PocketShellColors.Accent else PocketShellColors.TextSecondary
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = foreground,
                fontSize = if (label == "+") 18.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DisclosureIndicator(
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val color = PocketShellColors.TextSecondary
        val stroke = 2.dp.toPx()
        drawLine(
            color = color,
            start = Offset(size.width * 0.25f, size.height * 0.5f),
            end = Offset(size.width * 0.75f, size.height * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        if (!expanded) {
            drawLine(
                color = color,
                start = Offset(size.width * 0.5f, size.height * 0.25f),
                end = Offset(size.width * 0.5f, size.height * 0.75f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun StatusDot(active: Boolean, modifier: Modifier = Modifier) {
    val color = if (active) PocketShellColors.Green else PocketShellColors.Amber
    Box(
        modifier = modifier
            .size(7.dp)
            .background(color = color.copy(alpha = 0.82f), shape = CircleShape),
    )
}

internal fun projectCountText(folder: FolderRow): String {
    val sessions = folder.sessions.size
    val agents = folder.sessions.count { it.agentKind.isAgent() }
    return when {
        agents > 0 && agents == sessions -> agents.countLabel("agent")
        agents > 0 -> "${sessions.countLabel("session")} · ${agents.countLabel("agent")}"
        else -> sessions.countLabel("session")
    }
}

private fun Int.countLabel(noun: String): String =
    if (this == 1) "$this $noun" else "$this ${noun}s"

/**
 * Folder-tree session label. Issue #431: type/agent and activity state are two
 * orthogonal facets, so the label never collapses to a bare "Idle".
 *
 *  - Shell session  -> "Shell" (no agent activity state).
 *  - Agent session  -> "<agent> · <state>", e.g. "Codex · Idle", so the agent
 *    identity always travels with the state word.
 *  - Probing        -> "Detecting" (agent identity not yet known; no state word).
 *  - Exited         -> "Shell" (the agent process is gone; it is a plain shell).
 *
 * State signal: `FolderSessionEntry` carries no per-turn working/idle flag
 * today, so the agent state defaults to "Idle" (agent waiting for input / not
 * actively working) — the literal definition the maintainer gave in #431. A
 * richer working/idle signal (live turn detection feeding a per-session state)
 * is a follow-up once #430/#438 land; this rendering is correct for the data
 * available now and avoids the conflated bare-"Idle" label.
 */
internal fun sessionKindLabel(session: FolderSessionEntry): String = when (session.agentKind) {
    SessionAgentKind.Claude -> "Claude · ${agentStateLabel(session)}"
    SessionAgentKind.Codex -> "Codex · ${agentStateLabel(session)}"
    SessionAgentKind.OpenCode -> "OpenCode · ${agentStateLabel(session)}"
    SessionAgentKind.Probing -> "Detecting"
    SessionAgentKind.Exited -> "Shell"
    SessionAgentKind.Shell -> "Shell"
}

/**
 * Agent activity state for #431. "Idle" = agent waiting for input / not actively
 * working. No per-session working/idle signal exists on [FolderSessionEntry]
 * yet, so this defaults to "Idle"; promote to "Working" here once a live turn
 * signal is surfaced (#430/#438 follow-up).
 */
private fun agentStateLabel(@Suppress("UNUSED_PARAMETER") session: FolderSessionEntry): String = "Idle"

private fun sessionDisplayTitle(session: FolderSessionEntry): String {
    val raw = session.sessionName.trim()
    if (raw.isBlank()) return "Tmux session"
    return raw
}

private fun sessionSecondaryText(session: FolderSessionEntry): String? {
    val windows = session.windows
        .sortedWith(compareBy<FolderSessionWindowEntry> { it.index ?: Int.MAX_VALUE }.thenBy { it.name.orEmpty() })
    if (windows.size <= 1) return null
    val visible = windows.take(3).joinToString(" · ") { window ->
        val identity = window.index?.let { "w$it" } ?: "window"
        val hint = when {
            window.agentKind.isAgent() -> sessionKindLabel(
                session.copy(agentKind = window.agentKind, attached = false, windows = emptyList()),
            )
            !window.command.isNullOrBlank() -> window.command
            !window.name.isNullOrBlank() -> window.name
            window.active -> "active"
            else -> null
        }
        if (hint == null) identity else "$identity $hint"
    }
    val remaining = windows.size - 3
    return if (remaining > 0) "$visible · +$remaining" else visible
}

@Composable
private fun WatchedPin() {
    Box(
        modifier = Modifier
            .background(
                color = PocketShellColors.Purple.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "Watched",
            color = PocketShellColors.Purple,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun SessionAgentKind.isAgent(): Boolean = when (this) {
    SessionAgentKind.Claude,
    SessionAgentKind.Codex,
    SessionAgentKind.OpenCode,
    SessionAgentKind.Probing,
    SessionAgentKind.Exited,
    -> true
    SessionAgentKind.Shell -> false
}

private val FolderListFabContentClearance = 112.dp

// Test tags exposed for the unit / connected E2E suite.
const val FOLDER_LIST_SCREEN_TAG: String = "folder-list:screen"
const val FOLDER_LIST_CONTENT_TAG: String = "folder-list:content"
const val FOLDER_LIST_PORT_FORWARDING_TAG: String = "folder-list:port-forwarding"
const val FOLDER_LIST_BOTTOM_SPACER_TAG: String = "folder-list:bottom-spacer"
const val FOLDER_LIST_BACK_TAG: String = "folder-list:back"
const val FOLDER_LIST_TITLE_TAG: String = "folder-list:title"
const val FOLDER_LIST_LOADING_TAG: String = "folder-list:loading"
const val FOLDER_LIST_ERROR_TAG: String = "folder-list:error"
const val FOLDER_LIST_RETRY_TAG: String = "folder-list:retry"
const val FOLDER_LIST_EMPTY_TAG: String = "folder-list:empty"
const val FOLDER_LIST_SHOW_ALL_TAG: String = "folder-list:show-all"
const val FOLDER_LIST_FLAT_EMPTY_TAG: String = "folder-list:flat:empty"
const val FOLDER_LIST_NEW_SESSION_FAB_TAG: String = "folder-list:new-session-fab"
const val FOLDER_LIST_BROWSE_REPOS_TAG: String = "folder-list:browse-repos"
const val FOLDER_LIST_VIEW_TOGGLE_TAG: String = "folder-list:view-toggle"
const val FOLDER_LIST_WORKSPACE_SETTINGS_TAG: String = "folder-list:workspace-settings"
const val FOLDER_LIST_ASSISTANT_TAG: String = "folder-list:assistant"
const val FOLDER_LIST_ASSISTANT_ICON_TAG: String = "folder-list:assistant:icon"
const val FOLDER_LIST_ASSISTANT_PANEL_TAG: String = "folder-list:assistant:panel"
const val FOLDER_LIST_ASSISTANT_PROMPT_TAG: String = "folder-list:assistant:prompt"
const val FOLDER_LIST_ASSISTANT_PROMPT_MIC_TAG: String = "folder-list:assistant:prompt-mic"
const val FOLDER_LIST_ASSISTANT_SUBMIT_TAG: String = "folder-list:assistant:submit"
const val FOLDER_LIST_ASSISTANT_CLOSE_TAG: String = "folder-list:assistant:close"
const val FOLDER_LIST_ACTION_STATUS_TAG: String = "folder-list:action-status"
const val FOLDER_LIST_ACTION_STATUS_DISMISS_TAG: String = "folder-list:action-status:dismiss"

fun folderRowTestTag(path: String): String = "folder-list:row:$path"
fun folderHeaderClickTestTag(path: String): String = "folder-list:header-click:$path"
fun folderHeaderLabelTag(path: String): String = "folder-list:header:$path"
fun folderCountPillTestTag(path: String): String = "folder-list:count:$path"
fun folderListFlatRowTestTag(sessionName: String): String = "folder-list:flat-row:$sessionName"
fun folderDetailRowTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName"
fun folderDetailCreateTestTag(folderPath: String): String =
    "folder-list:detail:$folderPath:create"
fun folderDetailActionsTestTag(folderPath: String): String =
    "folder-list:detail:$folderPath:actions"
fun folderDetailEnvTestTag(folderPath: String): String =
    "folder-list:detail:$folderPath:env"
fun folderDetailDisclosureTestTag(folderPath: String): String =
    "folder-list:detail:$folderPath:disclosure"
fun folderStatusDotTestTag(folderPath: String): String =
    "folder-list:detail:$folderPath:status"
fun folderSessionStatusDotTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:status"
fun folderTreeRootTestTag(path: String): String = "folder-list:tree-root:$path"
fun folderTreeRootLabelTag(path: String): String = "folder-list:tree-root:$path:label"
fun folderTreeRootCreateTestTag(path: String): String = "folder-list:tree-root:$path:create"
fun folderTreeRootActionsTestTag(path: String): String = "folder-list:tree-root:$path:actions"
