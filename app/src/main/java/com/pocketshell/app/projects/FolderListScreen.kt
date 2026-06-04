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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.Dp
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
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.MicButton
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellType
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
                    flatSessions = s.flatSessions,
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
                    onFolderActions = { row ->
                        // Copy-source set = every real (non-untracked)
                        // discovered folder the user can see, so the env
                        // screen's picker stays inside the known set (D24).
                        // Captured here so the action sheet's "Env files"
                        // row can route straight to the env editor (#455).
                        val sources = s.folders
                            .filter { it.path != FolderListViewModel.UNTRACKED_PATH }
                            .map { it.path to it.label }
                        actionFolder = PickerTarget(
                            path = row.path,
                            label = row.label,
                            envSources = sources,
                        )
                    },
                    onCreateInRoot = { root ->
                        rootAddSheet = root
                    },
                    onRootActions = { root ->
                        // Roots have no `.env` of their own; the env row is
                        // suppressed for them (empty envSources → no row).
                        actionFolder = PickerTarget(path = root.path, label = root.label)
                    },
                    onToggleProjectExpanded = { row -> viewModel.toggleProjectExpanded(row.path) },
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
            // Env files folds into the sheet (#455). Suppressed for roots,
            // which have no `.env` of their own (empty envSources).
            onEnv = if (target.envSources.isNotEmpty()) {
                {
                    actionFolder = null
                    onEditEnv(target.path, target.label, target.envSources)
                }
            } else {
                null
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

private data class PickerTarget(
    val path: String,
    val label: String,
    // Copy-source set for the env screen, captured when the folder-actions
    // sheet is opened so its "Env files" row can route straight to the env
    // editor without re-deriving the folder list (#455). Empty for targets
    // that never reach the env action (root add-sheet, FAB default).
    val envSources: List<Pair<String, String>> = emptyList(),
)

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
    flatSessions: List<FolderSessionEntry>,
    expandedProjectPaths: Set<String>,
    portForwarding: HostPortForwardingSummary,
    showFlatFolderList: Boolean,
    actionStatus: FolderActionStatus,
    onDismissActionStatus: () -> Unit,
    onOpenPortForwarding: () -> Unit,
    onSessionClick: (folderPath: String, sessionName: String) -> Unit,
    onFolderActions: (FolderRow) -> Unit,
    onCreateInRoot: (FolderTreeRoot) -> Unit,
    onRootActions: (FolderTreeRoot) -> Unit,
    onToggleProjectExpanded: (FolderRow) -> Unit,
) {
    // session→folder map derived from the grouped `folders` set so a flat-view
    // tap still carries the session's cwd into the picker/attach path (#485).
    // Computed once per `folders` change, outside the LazyListScope which is not
    // a @Composable context.
    val sessionFolderPaths = remember(folders) {
        folders.flatMap { folder ->
            folder.sessions.map { it.sessionName to folder.path }
        }.toMap()
    }
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
        if (showFlatFolderList) {
            // Flat view (#485): a clean, ungrouped list of EVERY session on the
            // host — no folder headers, no tree connectors. Each row reuses the
            // shared design language so it reads identically to a tree session
            // row (#479): status dot + session name + agent-type badge. The
            // session→folder map is derived from the grouped `folders` set so a
            // tap still carries the session's cwd into the picker/attach path.
            if (flatSessions.isEmpty()) {
                item {
                    FlatEmptyState()
                }
            } else {
                items(flatSessions, key = { it.sessionName }) { session ->
                    FlatSessionRow(
                        session = session,
                        onClick = {
                            onSessionClick(
                                sessionFolderPaths[session.sessionName]
                                    ?: FolderListViewModel.UNTRACKED_PATH,
                                session.sessionName,
                            )
                        },
                    )
                }
            }
        } else if (treeRoots.isEmpty()) {
            item {
                EmptyState()
            }
        } else {
            items(treeRoots, key = { it.path }) { root ->
                FolderTreeRootGroup(
                    root = root,
                    expandedProjectPaths = expandedProjectPaths,
                    onSessionClick = onSessionClick,
                    onFolderActions = onFolderActions,
                    onCreateInRoot = onCreateInRoot,
                    onRootActions = onRootActions,
                    onToggleProjectExpanded = onToggleProjectExpanded,
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
    // Issue #456: the card is a summary + entry only — never a dump of raw
    // discovered-port rows. `discoveredCount` already reflects the
    // interesting-port filter (system/noise ports dropped, de-duped upstream),
    // so "N ports" is the user-facing count of forwardable ports.
    val statusText = when {
        summary.active -> "${summary.activeTunnelCount} active"
        summary.discoveredCount > 0 -> "${summary.discoveredCount} ports"
        else -> "Off"
    }
    val detailText = when {
        summary.active -> "Foreground forwarding service is running."
        summary.discoveredCount > 0 -> "Tap to view discovered ports and forward."
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

/**
 * One row in the flat host-detail view (#485). Renders a single tmux session as
 * a plain, ungrouped row using the shared [ListRow] + [Badge] + [StatusDot] so
 * it reads identically to a tree session row (#479 consistency principle) minus
 * the folder grouping / tree connectors. Tap routes through the same
 * `onOpenSession` handler the tree rows use.
 *
 *  - leading: [StatusDot] — green when attached or an agent is live, amber idle.
 *  - title: the session name; subtitle: extra windows (when present).
 *  - trailing: agent-type [Badge] — purple for Claude/Codex/OpenCode, grey for
 *    Shell.
 */
@Composable
private fun FlatSessionRow(
    session: FolderSessionEntry,
    onClick: () -> Unit,
) {
    val isAgent = session.agentKind.isAgent()
    ListRow(
        title = sessionDisplayTitle(session),
        subtitle = sessionSecondaryText(session),
        onClick = onClick,
        modifier = Modifier.testTag(folderListFlatRowTestTag(session.sessionName)),
        leading = {
            StatusDot(
                active = session.attached || isAgent,
                modifier = Modifier.testTag(folderListFlatRowStatusDotTestTag(session.sessionName)),
            )
        },
        trailing = {
            Badge(
                label = sessionBadgeLabel(session),
                role = if (isAgent) BadgeRole.Agent else BadgeRole.Shell,
                modifier = Modifier.testTag(folderListFlatRowBadgeTestTag(session.sessionName)),
            )
        },
    )
}

/**
 * Empty state for the flat view (#485) — shown when the host has zero tmux
 * sessions. Distinct test tag ([FOLDER_LIST_FLAT_EMPTY_TAG]) so the flat path is
 * independently assertable from the tree empty state.
 */
@Composable
private fun FlatEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(FOLDER_LIST_FLAT_EMPTY_TAG),
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
    onFolderActions: (FolderRow) -> Unit,
    onCreateInRoot: (FolderTreeRoot) -> Unit,
    onRootActions: (FolderTreeRoot) -> Unit,
    onToggleProjectExpanded: (FolderRow) -> Unit,
) {
    // Per-group list/grid view toggle (#478). Grid is an explicit non-goal —
    // the toggle is wired and flips the icon + shows a stub so the layout reads
    // like the target mockup, but the list remains the only real renderer.
    var gridView by remember(root.path) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(folderTreeRootTestTag(root.path)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FolderTreeRootHeader(
            root = root,
            gridView = gridView,
            onToggleView = { gridView = !gridView },
            onCreateInRoot = { onCreateInRoot(root) },
            onRootActions = { onRootActions(root) },
        )
        if (root.folders.isEmpty()) {
            EmptyRootHint(candidateCount = root.addSheetProjects.size, onCreate = { onCreateInRoot(root) })
        } else if (gridView) {
            GridViewStub(root = root)
        } else {
            Column(
                modifier = Modifier.padding(start = PocketShellDensity.treeIndent),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                root.folders.forEachIndexed { index, folder ->
                    FolderGroup(
                        folder = folder,
                        expanded = folder.path in expandedProjectPaths,
                        showTreeBranch = true,
                        lastInTree = index == root.folders.lastIndex,
                        onSessionClick = onSessionClick,
                        onFolderActions = onFolderActions,
                        onToggleExpanded = { onToggleProjectExpanded(folder) },
                    )
                }
            }
        }
    }
}

/**
 * Placeholder shown behind the list/grid toggle when grid is selected (#478).
 * Building the real grid is an explicit non-goal for this issue; the stub keeps
 * the toggle honest (it visibly changes the view) without shipping a half-grid.
 */
@Composable
private fun GridViewStub(root: FolderTreeRoot) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PocketShellDensity.treeIndent)
            .background(PocketShellColors.Surface.copy(alpha = 0.48f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .testTag(folderTreeRootGridStubTag(root.path)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Grid view is coming soon — tap the list icon to switch back.",
            color = PocketShellColors.TextMuted,
            style = PocketShellType.bodyDense,
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTreeRootHeader(
    root: FolderTreeRoot,
    gridView: Boolean,
    onToggleView: () -> Unit,
    onCreateInRoot: () -> Unit,
    onRootActions: () -> Unit,
) {
    val hasActions = root.path != FolderListViewModel.OTHER_ROOT_PATH
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Long-press the root band reaches the same actions sheet as the
            // overflow kebab, keeping the inline cluster to `+` plus the
            // kebab (#455).
            .then(
                if (hasActions) {
                    Modifier.combinedClickable(
                        role = Role.Button,
                        onClick = {},
                        onLongClick = onRootActions,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Group title — the mockup renders this larger than a project row
            // (the screen-level "git" heading above its org tree).
            Text(
                text = folderDisplayLabel(root.label, root.path),
                color = PocketShellColors.Text,
                fontSize = 18.sp,
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
        // List/grid view toggle (#478). Always available so every group reads
        // like the target mockup; grid itself is a stub (non-goal).
        ViewToggleButton(
            gridView = gridView,
            onClick = onToggleView,
            testTag = folderTreeRootViewToggleTag(root.path),
        )
        if (hasActions) {
            Spacer(modifier = Modifier.width(6.dp))
            CompactTreeIconButton(
                label = "⋮",
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

/**
 * List/grid view toggle for a group header — issue #478. Draws a compact
 * two-icon switch (list rows vs. grid cells) matching the target mockup. The
 * grid half is a stub; the toggle still flips state and the active half is
 * highlighted so the affordance reads correctly.
 */
@Composable
private fun ViewToggleButton(
    gridView: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Box(
        modifier = Modifier
            .defaultMinSize(
                minWidth = PocketShellDensity.tapTargetMin,
                minHeight = PocketShellDensity.tapTargetMin,
            )
            .semantics {
                contentDescription = if (gridView) "Switch to list view" else "Switch to grid view"
            }
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .background(PocketShellColors.SurfaceElev.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewToggleHalf(selected = !gridView) { color -> ListGlyph(color) }
            ViewToggleHalf(selected = gridView) { color -> GridGlyph(color) }
        }
    }
}

@Composable
private fun ViewToggleHalf(
    selected: Boolean,
    glyph: @Composable (Color) -> Unit,
) {
    val background = if (selected) PocketShellColors.AccentSoft else Color.Transparent
    val tint = if (selected) PocketShellColors.Accent else PocketShellColors.TextMuted
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(background, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        glyph(tint)
    }
}

@Composable
private fun ListGlyph(color: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val stroke = 1.6.dp.toPx()
        repeat(3) { row ->
            val y = size.height * (0.22f + row * 0.28f)
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun GridGlyph(color: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val cell = size.width * 0.4f
        val gap = size.width * 0.2f
        listOf(0f, cell + gap).forEach { x ->
            listOf(0f, cell + gap).forEach { y ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(cell, cell),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
                    style = Stroke(width = 1.4.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun RootCountText(root: FolderTreeRoot) {
    Text(
        text = rootCountSubtitle(root),
        color = PocketShellColors.TextMuted,
        style = PocketShellType.labelMono,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag(folderTreeRootCountTag(root.path)),
    )
}

/**
 * Group-header subtitle shown under the root title — issue #478. Mirrors the
 * maintainer's target mockup: `N orgs · M sessions` (e.g. "10 orgs ·
 * 14 sessions"). An "org" here is one project folder under the root (active or
 * inactive/scanned); "sessions" is the live tmux session count across those
 * projects. Degrades to just the org count when there are no live sessions.
 */
internal fun rootCountSubtitle(root: FolderTreeRoot): String {
    val orgCount = root.activeProjectCount + root.inactiveProjectCount
    val orgs = orgCount.countLabel("org")
    return if (root.sessionCount > 0) {
        "$orgs · ${root.sessionCount.countLabel("session")}"
    } else {
        orgs
    }
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
    onFolderActions: (FolderRow) -> Unit,
    onToggleExpanded: () -> Unit,
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
                onFolderActions = { onFolderActions(folder) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderHeader(
    folder: FolderRow,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onFolderActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasActions = folder.path != FolderListViewModel.UNTRACKED_PATH
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
            // Long-press the row to reach the consolidated folder-actions
            // sheet (new session, env files, import, clone, empty project) so
            // the trailing cluster stays down to just the overflow kebab and
            // the folder name keeps its width (#478 — the per-row inline `+`
            // was dropped to match the maintainer's mockup; "New session" now
            // lives in the kebab sheet). Untracked has no filesystem path to
            // manage, so it only toggles.
            .then(
                if (hasActions) {
                    Modifier.combinedClickable(
                        role = Role.Button,
                        onClick = onToggleExpanded,
                        onLongClick = onFolderActions,
                    )
                } else {
                    Modifier.clickable(role = Role.Button, onClick = onToggleExpanded)
                },
            )
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
        Spacer(modifier = Modifier.width(5.dp))
        // Status dot leads the name (mockup: `▼ ● cable-world · 3 agents`).
        // Green = active agents/attached, amber = idle.
        StatusDot(
            active = folder.isActive,
            modifier = Modifier.testTag(folderStatusDotTestTag(folder.path)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        // Name + inline count form a single flexible block that consumes the
        // row's leftover width via ONE `weight(1f)`, pushing the action cluster
        // hard right. The name itself fills that block (`weight(1f)`) so it
        // renders in full and only ellipsizes when the name + count genuinely
        // exceed the available width. (A second competing `weight(1f)` trailing
        // spacer used to split the slack 50/50 and clamp the name to ~half the
        // row — that's removed.)
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = folderDisplayLabel(folder.label, folder.path),
                color = PocketShellColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .testTag(folderHeaderLabelTag(folder.path))
                    .semantics { contentDescription = folder.path },
            )
            Spacer(modifier = Modifier.width(8.dp))
            // Inline count subtitle (`· 3 agents` / `· 1 session`) sits right
            // after the name; it keeps its intrinsic width so the name gets the
            // remaining space in the flexible block.
            Text(
                text = "· ${projectCountText(folder)}",
                color = PocketShellColors.TextMuted,
                style = PocketShellType.labelMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(folderCountPillTestTag(folder.path)),
            )
        }
        // Per-row actions — including "New session" — collapse behind a
        // single overflow kebab (and the row long-press) so the trailing
        // cluster is just the kebab and the name column keeps its width
        // (#478: the inline `+` was dropped to match the maintainer's
        // mockup). Only real folders have a filesystem path to manage; the
        // synthetic Untracked group has no kebab — its sessions are created
        // via the screen-level FAB.
        if (folder.path != FolderListViewModel.UNTRACKED_PATH) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactTreeIconButton(
                    label = "⋮",
                    contentDescription = "Project actions",
                    onClick = onFolderActions,
                    testTag = folderDetailActionsTestTag(folder.path),
                )
            }
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Compact paint via the density rung, but the interactive row keeps
            // the 48 dp a11y touch floor (#461 §6.1).
            .heightIn(min = PocketShellDensity.tapTargetMin)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = PocketShellDensity.rowPadV)
            .testTag(folderDetailRowTestTag(folderPath, session.sessionName)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(
            active = session.attached || session.agentKind.isAgent(),
            modifier = Modifier.testTag(folderSessionStatusDotTestTag(folderPath, session.sessionName)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sessionDisplayTitle(session),
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            sessionSecondaryText(session)?.let { secondary ->
                Text(
                    text = secondary,
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.labelMono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        AgentTypeBadge(
            session = session,
            modifier = Modifier.testTag(folderSessionBadgeTestTag(folderPath, session.sessionName)),
        )
    }
}

/**
 * Right-aligned agent-type pill on a session row — issue #478. Mockup colours:
 * Codex/Claude/OpenCode = purple (`agentAccent`), Shell = grey/neutral. The
 * short label is just the agent/shell name (no activity word — that lives in
 * the secondary line via [sessionKindLabel]).
 */
@Composable
private fun AgentTypeBadge(
    session: FolderSessionEntry,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalPocketShellSemantic.current
    val isAgent = session.agentKind.isAgent()
    val label = sessionBadgeLabel(session)
    val fg = if (isAgent) semantic.agentAccent else PocketShellColors.TextSecondary
    val bg = if (isAgent) {
        semantic.agentAccent.copy(alpha = 0.16f)
    } else {
        PocketShellColors.SurfaceElev.copy(alpha = 0.72f)
    }
    Box(
        modifier = modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = PocketShellDensity.chipPadH, vertical = PocketShellDensity.chipPadV),
    ) {
        Text(
            text = label,
            color = fg,
            style = PocketShellType.labelMono,
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
    size: Dp = 36.dp,
) {
    val background = if (accent) {
        PocketShellColors.AccentSoft
    } else {
        PocketShellColors.SurfaceElev.copy(alpha = 0.72f)
    }
    val foreground = if (accent) PocketShellColors.Accent else PocketShellColors.TextSecondary
    // Inner pill scales with the hit box but stays ~4 dp smaller so the
    // tap target meets the design-system minimum (§6.1) while reading as a
    // compact glyph. These tree icon buttons keep the deliberate #455 36 dp
    // hit box so the folder name column keeps its readable width; the larger
    // 48 dp a11y floor is reserved for the full-width interactive rows and the
    // view toggle (#478).
    val pillSize = (size.value - 4f).coerceAtLeast(24f).dp
    Box(
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription }
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(pillSize)
                .background(background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = foreground,
                fontSize = if (label == "+") 18.sp else 14.sp,
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
    // Filled chevron triangle — ▼ when expanded, ▶ when collapsed — matching
    // the maintainer's target mockup (#478).
    val color = PocketShellColors.TextSecondary
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val triangle = Path().apply {
            if (expanded) {
                // Pointing down (▼).
                moveTo(w * 0.22f, h * 0.34f)
                lineTo(w * 0.78f, h * 0.34f)
                lineTo(w * 0.5f, h * 0.66f)
            } else {
                // Pointing right (▶).
                moveTo(w * 0.34f, h * 0.22f)
                lineTo(w * 0.66f, h * 0.5f)
                lineTo(w * 0.34f, h * 0.78f)
            }
            close()
        }
        drawPath(path = triangle, color = color)
    }
}

@Composable
private fun StatusDot(active: Boolean, modifier: Modifier = Modifier) {
    val semantic = LocalPocketShellSemantic.current
    // Green = active agents/attached, amber = idle (#478). Colours come from
    // the semantic role vocabulary, not raw palette tokens.
    val color = if (active) semantic.statusActive else semantic.statusAttention
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color = color, shape = CircleShape),
    )
}

/**
 * A project folder is "active" when it has at least one attached session or a
 * live agent (Claude/Codex/OpenCode/probing/exited shell that just ran one) —
 * the green-dot condition in the mockup. Otherwise it reads idle (amber).
 */
private val FolderRow.isActive: Boolean
    get() = sessions.any { it.attached || it.agentKind.isAgent() }

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
/**
 * Short agent-type label for the right-aligned session badge — issue #478.
 * Unlike [sessionKindLabel] (which carries the activity state for the secondary
 * line), this is just the agent/shell identity the badge pill shows:
 *
 *  - Claude / Codex / OpenCode  -> the agent name.
 *  - Probing                    -> "Detecting" (identity not yet known).
 *  - Shell / Exited             -> "Shell".
 */
internal fun sessionBadgeLabel(session: FolderSessionEntry): String = when (session.agentKind) {
    SessionAgentKind.Claude -> "Claude"
    SessionAgentKind.Codex -> "Codex"
    SessionAgentKind.OpenCode -> "OpenCode"
    SessionAgentKind.Probing -> "Detecting"
    SessionAgentKind.Exited -> "Shell"
    SessionAgentKind.Shell -> "Shell"
}

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
fun folderListFlatRowStatusDotTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:status"
fun folderListFlatRowBadgeTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:badge"
fun folderDetailRowTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName"
fun folderDetailCreateTestTag(folderPath: String): String =
    "folder-list:detail:$folderPath:create"
fun folderDetailActionsTestTag(folderPath: String): String =
    "folder-list:detail:$folderPath:actions"
fun folderDetailDisclosureTestTag(folderPath: String): String =
    "folder-list:detail:$folderPath:disclosure"
fun folderStatusDotTestTag(folderPath: String): String =
    "folder-list:detail:$folderPath:status"
fun folderSessionStatusDotTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:status"
fun folderSessionBadgeTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:badge"
fun folderTreeRootTestTag(path: String): String = "folder-list:tree-root:$path"
fun folderTreeRootLabelTag(path: String): String = "folder-list:tree-root:$path:label"
fun folderTreeRootCountTag(path: String): String = "folder-list:tree-root:$path:count"
fun folderTreeRootCreateTestTag(path: String): String = "folder-list:tree-root:$path:create"
fun folderTreeRootActionsTestTag(path: String): String = "folder-list:tree-root:$path:actions"
fun folderTreeRootViewToggleTag(path: String): String = "folder-list:tree-root:$path:view-toggle"
fun folderTreeRootGridStubTag(path: String): String = "folder-list:tree-root:$path:grid-stub"
