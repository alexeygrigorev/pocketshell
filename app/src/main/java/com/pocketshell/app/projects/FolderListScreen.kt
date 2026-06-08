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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.pocketshell.app.voice.InlineDictationErrorStrip
import com.pocketshell.app.voice.appendDictationText
import com.pocketshell.app.voice.toMicButtonState
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.MicButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
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
 *  - Tap "Workspace settings" in the app-bar overflow → opens workspace
 *    settings where roots and the default tree/flat mode are configured.
 *  - Tap "Settings" in the app-bar overflow → opens the global app settings.
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
    onOpenSessionWindow: (sessionName: String, startDirectory: String?, windowIndex: Int?) -> Unit =
        { sessionName, startDirectory, _ -> onOpenSession(sessionName, startDirectory) },
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
    onOpenSettings: () -> Unit = {},
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
    onOpenUsage: () -> Unit = {},
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
    // Issue #518: the session pending a "Stop session" confirmation. Non-null
    // means the confirm dialog is up; confirming kills it, Cancel clears it.
    var stopSessionTarget by remember { mutableStateOf<String?>(null) }
    var renameSessionTarget by remember { mutableStateOf<String?>(null) }
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
    val onCreateTopLevelSession = {
        // The host-level action uses the user's home directory by default; the
        // picker still lets them edit before confirming.
        pickerFolder = PickerTarget(path = "~", label = "home")
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
            // Host-level Active/Idle/total summary shown as the single header
            // subtitle directly under the host name (#522 item 1), mirroring the
            // maintainer's mockup #489. Derived from the Ready state's full
            // session set so the same `N active · M idle · K sessions` line reads
            // identically in both tree and flat views — the per-view list no
            // longer repeats the host name in a second band.
            val headerGroups = (state as? FolderListUiState.Ready)
                ?.let { FlatSessionGroups.from(it.flatSessions) }
            FolderListAppBar(
                hostName = hostName,
                headerGroups = headerGroups,
                onBack = onBack,
                onBrowseRepos = { onBrowseRepos(null) },
                onRefreshSessions = viewModel::refreshSessions,
                onOpenSettings = onOpenSettings,
                onOpenWorkspaceSettings = onOpenWorkspaceSettings,
                onOpenUsage = onOpenUsage,
                onOpenAssistant = { showAssistant = true },
            )
            when (val s = state) {
                is FolderListUiState.Loading -> LoadingPanel()
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
                    hostName = hostName,
                    folders = s.folders,
                    treeRoots = s.treeRoots,
                    flatSessions = s.flatSessions,
                    expandedProjectPaths = s.expandedProjectPaths,
                    portForwarding = s.portForwarding,
                    showFlatFolderList = showFlatFolderList,
                    actionStatus = actionStatus,
                    onDismissActionStatus = viewModel::clearActionStatus,
                    onOpenPortForwarding = onOpenPortForwarding,
                    onCreateTopLevelSession = onCreateTopLevelSession,
                    onSessionClick = { folderPath, sessionName, windowIndex ->
                        onOpenSessionWindow(
                            sessionName,
                            folderPath.takeUnless { it == FolderListViewModel.UNTRACKED_PATH },
                            windowIndex,
                        )
                    },
                    onRenameSession = { sessionName -> renameSessionTarget = sessionName },
                    onStopSession = { sessionName -> stopSessionTarget = sessionName },
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

    stopSessionTarget?.let { sessionName ->
        StopSessionDialog(
            sessionName = sessionName,
            onDismiss = { stopSessionTarget = null },
            onConfirm = {
                stopSessionTarget = null
                viewModel.killSession(sessionName)
            },
        )
    }

    renameSessionTarget?.let { sessionName ->
        RenameSessionDialog(
            sessionName = sessionName,
            onDismiss = { renameSessionTarget = null },
            onConfirm = { newName ->
                renameSessionTarget = null
                viewModel.renameSession(sessionName, newName)
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

/**
 * Host-detail header (#522 items 1 + 2). Mockup #489 shows the host name ONCE,
 * with the `N active · M idle · K sessions` count line directly beneath it and a
 * single `⋮` kebab on the right — not the old three cramped circular action
 * buttons and not a second host-name band in the list. Host assistant, Browse
 * repos, global Settings, and Workspace settings are items in the kebab
 * overflow menu (same affordance pattern as the host-list card kebab), and the
 * count subtitle ([headerGroups]) is the single host-level summary the screen
 * carries.
 */
@Composable
private fun FolderListAppBar(
    hostName: String,
    headerGroups: FlatSessionGroups?,
    onBack: () -> Unit,
    onBrowseRepos: () -> Unit,
    onRefreshSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorkspaceSettings: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenAssistant: () -> Unit,
) {
    // #479 Slice A: the reference screen's hand-rolled bar is reconciled onto the
    // shared [ScreenHeader] so the folder tree is truly canonical for the header
    // pattern. The back chevron + active dot ride the leading slot; the single
    // host name is the title; the `N active · M idle · K sessions` line is the
    // subtitle (#522 item 1); the `⋮` overflow rides the trailing slot. All of
    // the previous test tags are preserved so existing instrumentation keeps
    // resolving the back button, header column, title, dot, and count subtitle.
    ScreenHeader(
        title = hostName,
        subtitle = headerGroups?.let { flatHostCountText(it) },
        titleTestTag = FOLDER_LIST_TITLE_TAG,
        subtitleTestTag = FOLDER_LIST_FLAT_HEADER_COUNTS_TAG,
        modifier = if (headerGroups != null) {
            Modifier.testTag(FOLDER_LIST_FLAT_HEADER_TAG)
        } else {
            Modifier
        },
        leading = {
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
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            if (headerGroups != null) {
                Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
                StatusDot(
                    active = headerGroups.activeCount > 0,
                    modifier = Modifier.testTag(FOLDER_LIST_FLAT_HEADER_DOT_TAG),
                )
            }
        },
        trailing = {
            FolderListOverflowMenu(
                onBrowseRepos = onBrowseRepos,
                onRefreshSessions = onRefreshSessions,
                onOpenAssistant = onOpenAssistant,
                onOpenSettings = onOpenSettings,
                onOpenWorkspaceSettings = onOpenWorkspaceSettings,
                onOpenUsage = onOpenUsage,
            )
        },
    )
}

/**
 * Single `⋮` kebab overflow for the host-detail header (#522 item 2). Consolidates
 * the former Browse repos / Host assistant / Workspace settings circular buttons
 * into one menu. Renders the shared [Kebab] component (#461 design-system
 * consolidation) so the trigger glyph + menu chrome match every other overflow
 * across the app. Each former button's `contentDescription` + test tag move onto
 * its [KebabItem] so existing instrumentation (which located the action by tag /
 * description) keeps working once the menu is open.
 */
@Composable
private fun FolderListOverflowMenu(
    onBrowseRepos: () -> Unit,
    onRefreshSessions: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorkspaceSettings: () -> Unit,
    onOpenUsage: () -> Unit,
) {
    Kebab(
        triggerTestTag = FOLDER_LIST_OVERFLOW_TAG,
        items = listOf(
            KebabItem(
                label = "Host assistant",
                onClick = onOpenAssistant,
                contentDescription = "Host assistant",
                testTag = FOLDER_LIST_ASSISTANT_TAG,
            ),
            KebabItem(
                label = "Browse repos",
                onClick = onBrowseRepos,
                contentDescription = "Browse repos",
                testTag = FOLDER_LIST_BROWSE_REPOS_TAG,
            ),
            KebabItem(
                label = "Refresh sessions",
                onClick = onRefreshSessions,
                contentDescription = "Refresh sessions",
                testTag = FOLDER_LIST_REFRESH_SESSIONS_TAG,
            ),
            KebabItem(
                label = "Usage",
                onClick = onOpenUsage,
                contentDescription = "Usage",
                testTag = FOLDER_LIST_USAGE_TAG,
            ),
            KebabItem(
                label = "Settings",
                onClick = onOpenSettings,
                contentDescription = "Settings",
                testTag = FOLDER_LIST_SETTINGS_TAG,
            ),
            KebabItem(
                label = "Workspace settings",
                onClick = onOpenWorkspaceSettings,
                contentDescription = "Workspace settings",
                testTag = FOLDER_LIST_WORKSPACE_SETTINGS_TAG,
            ),
        ),
    )
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
private fun LoadingPanel(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag(FOLDER_LIST_LOADING_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.testTag(FOLDER_LIST_LOADING_BODY_TAG),
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
    hostName: String,
    folders: List<FolderRow>,
    treeRoots: List<FolderTreeRoot>,
    flatSessions: List<FolderSessionEntry>,
    expandedProjectPaths: Set<String>,
    portForwarding: HostPortForwardingSummary,
    showFlatFolderList: Boolean,
    actionStatus: FolderActionStatus,
    onDismissActionStatus: () -> Unit,
    onOpenPortForwarding: () -> Unit,
    onCreateTopLevelSession: () -> Unit,
    onSessionClick: (folderPath: String, sessionName: String, windowIndex: Int?) -> Unit,
    onRenameSession: (sessionName: String) -> Unit,
    onStopSession: (sessionName: String) -> Unit,
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
    // session→folder label map for the flat-view subtitle (#489). The subtitle is
    // `<folder>` (mono muted) so a flat row carries its directory context that the
    // tree grouping otherwise provides. The agent type stays on the trailing
    // [Badge] pill — the SAME treatment the tree session rows use — so the two
    // views read as one design language rather than diverging on where the agent
    // type lives.
    val sessionFolderLabels = remember(folders) {
        folders.associate { folder ->
            folder.path to folderDisplayLabel(folder.label, folder.path)
        }
    }
    // Active/Idle partition for the flat view (#489). Derived purely from the
    // already-sorted `flatSessions` (no re-sort) so each section preserves the
    // upstream order and a row's section always agrees with its status-dot colour.
    val flatGroups = remember(flatSessions) { FlatSessionGroups.from(flatSessions) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(FOLDER_LIST_CONTENT_TAG),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = 12.dp,
            end = 12.dp,
            bottom = FolderListBottomContentPadding,
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
            // Flat view (#485 render fix, #489 visual polish): EVERY session on the
            // host, grouped by status into ACTIVE and IDLE sections instead of by
            // folder. Each section is a shared [SectionHeader] with its count; each
            // row reuses the shared [ListRow] + [StatusDot] + [Badge] so it reads
            // identically to a tree session row (#479 consistency). A header band
            // carries the host name + `N active · M idle · K sessions`. The
            // session→folder map is derived from the grouped `folders` set so a tap
            // still carries the session's cwd into the picker/attach path.
            if (flatSessions.isEmpty()) {
                item {
                    FlatEmptyState()
                }
            } else {
                // #522 item 1: the host name + count summary no longer repeats in
                // a second in-list band — it lives once in the header app bar
                // (single title + count subtitle). The list goes straight to the
                // Active / Idle sections.
                if (flatGroups.active.isNotEmpty()) {
                    item(key = FLAT_ACTIVE_SECTION_KEY) {
                        SectionHeader(
                            label = "Active",
                            count = flatGroups.activeCount,
                            modifier = Modifier.testTag(FOLDER_LIST_FLAT_ACTIVE_SECTION_TAG),
                        )
                    }
                    items(flatGroups.active, key = { "active:${it.sessionName}" }) { session ->
                        FlatSessionRow(
                            session = session,
                            folderLabel = flatSessionFolderLabel(
                                session = session,
                                sessionFolderPaths = sessionFolderPaths,
                                sessionFolderLabels = sessionFolderLabels,
                            ),
                            onClick = {
                                onSessionClick(
                                    sessionFolderPaths[session.sessionName]
                                        ?: FolderListViewModel.UNTRACKED_PATH,
                                    session.sessionName,
                                    null,
                                )
                            },
                            onOpen = {
                                onSessionClick(
                                    sessionFolderPaths[session.sessionName]
                                        ?: FolderListViewModel.UNTRACKED_PATH,
                                    session.sessionName,
                                    null,
                                )
                            },
                            onRename = { onRenameSession(session.sessionName) },
                            onStop = { onStopSession(session.sessionName) },
                        )
                    }
                }
                if (flatGroups.idle.isNotEmpty()) {
                    item(key = FLAT_IDLE_SECTION_KEY) {
                        SectionHeader(
                            label = "Idle",
                            count = flatGroups.idleCount,
                            modifier = Modifier.testTag(FOLDER_LIST_FLAT_IDLE_SECTION_TAG),
                        )
                    }
                    items(flatGroups.idle, key = { "idle:${it.sessionName}" }) { session ->
                        FlatSessionRow(
                            session = session,
                            folderLabel = flatSessionFolderLabel(
                                session = session,
                                sessionFolderPaths = sessionFolderPaths,
                                sessionFolderLabels = sessionFolderLabels,
                            ),
                            onClick = {
                                onSessionClick(
                                    sessionFolderPaths[session.sessionName]
                                        ?: FolderListViewModel.UNTRACKED_PATH,
                                    session.sessionName,
                                    null,
                                )
                            },
                            onOpen = {
                                onSessionClick(
                                    sessionFolderPaths[session.sessionName]
                                        ?: FolderListViewModel.UNTRACKED_PATH,
                                    session.sessionName,
                                    null,
                                )
                            },
                            onRename = { onRenameSession(session.sessionName) },
                            onStop = { onStopSession(session.sessionName) },
                        )
                    }
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
                    onRenameSession = onRenameSession,
                    onStopSession = onStopSession,
                    onFolderActions = onFolderActions,
                    onCreateInRoot = onCreateInRoot,
                    onRootActions = onRootActions,
                    onToggleProjectExpanded = onToggleProjectExpanded,
                )
            }
        }
        item {
            NewSessionSummaryRow(onClick = onCreateTopLevelSession)
        }
        if (portForwarding.shouldShowSummary) {
            item {
                PortForwardingSummaryRow(
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
    get() = entryAvailable || active || activeTunnelCount > 0 || discoveredPorts.isNotEmpty()

@Composable
private fun NewSessionSummaryRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListRow(
        title = "New session",
        subtitle = "Start in home or choose a folder.",
        leading = {
            Text(
                text = "+",
                color = PocketShellColors.Accent,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        trailing = {
            Text(
                text = "Create",
                color = PocketShellColors.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        },
        onClick = onClick,
        modifier = modifier
            .background(PocketShellColors.Surface.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
            .testTag(FOLDER_LIST_NEW_SESSION_FAB_TAG),
    )
}

@Composable
private fun PortForwardingSummaryRow(
    summary: HostPortForwardingSummary,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Issue #456/#603: this is a compact summary + entry only — never a dump of raw
    // discovered-port rows. `discoveredCount` already reflects the
    // interesting-port filter (system/noise ports dropped, de-duped upstream),
    // so "N ports" is the user-facing count of forwardable ports.
    val statusText = when {
        summary.discoveryLoading -> "Scanning"
        summary.active -> "${summary.activeTunnelCount} active"
        summary.discoveredCount > 0 -> "${summary.discoveredCount} ports"
        else -> "Off"
    }
    val detailText = when {
        summary.discoveryLoading -> "Checking remote ports."
        summary.active -> "Foreground forwarding service is running."
        summary.discoveredCount > 0 -> "Tap to view discovered ports and forward."
        else -> "Auto-forward is off by default."
    }
    ListRow(
        title = "Port forwarding",
        subtitle = detailText,
        leading = {
            StatusDot(active = summary.active)
        },
        trailing = {
            Text(
                text = statusText,
                color = if (summary.active) PocketShellColors.Green else PocketShellColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        },
        onClick = onOpen,
        modifier = modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
            .testTag(FOLDER_LIST_PORT_FORWARDING_TAG),
    )
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
 * `N active · M idle · K sessions` count summary for the flat-view header (#489).
 * Always shows all three facets so the active/idle/total split is legible even
 * when one section is empty (e.g. `0 active · 4 idle · 4 sessions`).
 */
internal fun flatHostCountText(groups: FlatSessionGroups): String {
    val total = groups.totalCount
    return "${groups.activeCount} active · ${groups.idleCount} idle · " +
        if (total == 1) "1 session" else "$total sessions"
}

/**
 * Folder label shown as the flat-row subtitle (#489) — the directory context the
 * tree grouping otherwise carries. Resolves the session's folder path via the
 * `folders`-derived maps, then falls back to a path-derived label or the
 * untracked label so the subtitle is never blank.
 */
internal fun flatSessionFolderLabel(
    session: FolderSessionEntry,
    sessionFolderPaths: Map<String, String>,
    sessionFolderLabels: Map<String, String>,
): String {
    val path = sessionFolderPaths[session.sessionName] ?: FolderListViewModel.UNTRACKED_PATH
    return sessionFolderLabels[path] ?: FolderListViewModel.defaultLabelForPath(path)
}

/**
 * One row in the flat host-detail view (#485, polished in #489). Renders a single
 * tmux session as a row using the shared [ListRow] + [Badge] + [StatusDot] so it
 * reads identically to a tree session row (#479 consistency principle). The
 * folder context lives in the subtitle (the tree provides it via grouping); the
 * agent type lives on the trailing [Badge] pill — the SAME treatment the tree
 * session rows use — so the two views never diverge on where the agent type sits.
 *
 *  - leading: [StatusDot] — green when attached or an agent is live, amber idle.
 *  - title: the session name.
 *  - subtitle: the session's folder label (`bodyMono` muted, via [ListRow]).
 *  - trailing: agent-type [Badge] — purple for Claude/Codex/OpenCode, grey for
 *    Shell.
 */
@Composable
private fun FlatSessionRow(
    session: FolderSessionEntry,
    folderLabel: String,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onStop: () -> Unit,
) {
    val isAgent = session.agentKind.isAgent()
    ListRow(
        title = sessionDisplayTitle(session),
        subtitle = folderLabel,
        onClick = onClick,
        modifier = Modifier.testTag(folderListFlatRowTestTag(session.sessionName)),
        leading = {
            // Mockup #489 leads each row with the status dot AND a terminal tile
            // glyph (#522 item 3); the dot keeps its active/idle signal.
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    active = session.attached || isAgent,
                    modifier = Modifier.testTag(folderListFlatRowStatusDotTestTag(session.sessionName)),
                )
                Spacer(modifier = Modifier.width(6.dp))
                SessionTileGlyph(
                    modifier = Modifier.testTag(folderListFlatRowTileTestTag(session.sessionName)),
                )
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentTypeBadge(
                    session = session,
                    modifier = Modifier.testTag(folderListFlatRowBadgeTestTag(session.sessionName)),
                )
                Spacer(modifier = Modifier.width(4.dp))
                // Session actions live behind an overflow menu: destructive
                // Stop is a menu item first, then the existing confirmation.
                SessionActionsKebab(
                    sessionName = session.sessionName,
                    triggerTestTag = folderListFlatRowActionsTestTag(session.sessionName),
                    openItemTestTag = folderListFlatRowOpenMenuItemTestTag(session.sessionName),
                    renameItemTestTag = folderListFlatRowRenameMenuItemTestTag(session.sessionName),
                    stopItemTestTag = folderListFlatRowStopMenuItemTestTag(session.sessionName),
                    onOpen = onOpen,
                    onRename = onRename,
                    onStop = onStop,
                )
            }
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
    onSessionClick: (folderPath: String, sessionName: String, windowIndex: Int?) -> Unit,
    onRenameSession: (sessionName: String) -> Unit,
    onStopSession: (sessionName: String) -> Unit,
    onFolderActions: (FolderRow) -> Unit,
    onCreateInRoot: (FolderTreeRoot) -> Unit,
    onRootActions: (FolderTreeRoot) -> Unit,
    onToggleProjectExpanded: (FolderRow) -> Unit,
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
            EmptyRootHint(
                rootPath = root.path,
                candidateCount = root.addSheetProjects.size,
                onCreate = { onCreateInRoot(root) },
            )
        } else {
            Column(
                modifier = Modifier.padding(start = treeProjectIndent),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                root.folders.forEach { folder ->
                    FolderGroup(
                        folder = folder,
                        expanded = folder.path in expandedProjectPaths,
                        onSessionClick = onSessionClick,
                        onRenameSession = onRenameSession,
                        onStopSession = onStopSession,
                        onFolderActions = onFolderActions,
                        onToggleExpanded = { onToggleProjectExpanded(folder) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTreeRootHeader(
    root: FolderTreeRoot,
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
            // (the screen-level "git" heading above its project tree).
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
 * project-list grouping summary: `N projects · M sessions` (e.g. "10 projects ·
 * 14 sessions"). A project here is one project folder under the root (active or
 * inactive/scanned); "sessions" is the live tmux session count across those
 * projects. Degrades to just the project count when there are no live sessions.
 */
internal fun rootCountSubtitle(root: FolderTreeRoot): String {
    val projectCount = root.activeProjectCount + root.inactiveProjectCount
    val projects = projectCount.countLabel("project")
    return if (root.sessionCount > 0) {
        "$projects · ${root.sessionCount.countLabel("session")}"
    } else {
        projects
    }
}

@Composable
private fun EmptyRootHint(rootPath: String, candidateCount: Int, onCreate: () -> Unit) {
    val title = if (candidateCount > 0) {
        "$candidateCount inactive project folders"
    } else {
        "No project folders found"
    }
    val subtitle = if (candidateCount > 0) {
        "Review folders detected under this root."
    } else {
        "Add a folder under this root."
    }
    val actionLabel = if (candidateCount > 0) "Review" else "Add"
    val actionDescription = if (candidateCount > 0) {
        "Review inactive project folders"
    } else {
        "Add project folder"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = treeProjectIndent, end = 2.dp)
            .testTag(FOLDER_LIST_EMPTY_ROOT_HINT_TAG),
    ) {
        ListRow(
            title = title,
            subtitle = subtitle,
            leading = {
                StatusDot(active = false)
            },
            trailing = {
                Text(
                    text = actionLabel,
                    color = PocketShellColors.Accent,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(folderTreeRootEmptyHintActionLabelTestTag(rootPath)),
                )
            },
            onClick = onCreate,
            modifier = Modifier
                .background(PocketShellColors.Surface.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
                .semantics { contentDescription = actionDescription }
                .testTag(folderTreeRootEmptyHintAddTestTag(rootPath)),
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
    onSessionClick: (folderPath: String, sessionName: String, windowIndex: Int?) -> Unit,
    onRenameSession: (sessionName: String) -> Unit,
    onStopSession: (sessionName: String) -> Unit,
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
        // The project row reads as the mockup's group header: chevron + status
        // dot + name lead it directly, with NO left connector spine of its own
        // (#503). Only the session children below get the `├─/└─` tree spine.
        FolderHeader(
            folder = folder,
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            onFolderActions = { onFolderActions(folder) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (expanded && folder.sessions.isNotEmpty()) {
            // Session children hang off ONE continuous vertical spine (#503).
            // The spine + per-row `├─/└─` stub is drawn by [TreeChildRow]; the
            // column carries NO inter-row gap so adjacent connector canvases
            // abut pixel-to-pixel and the spine never seams. Visual breathing
            // room lives inside each row's own vertical padding instead.
            Column(
                modifier = Modifier.padding(start = treeChildIndent),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                val childRows = folderTreeSessionChildRows(folder.sessions)
                childRows.forEachIndexed { index, row ->
                    TreeChildRow(
                        last = index == childRows.lastIndex,
                        connectorTestTag = row.connectorTestTag(folder.path),
                    ) {
                        when (row) {
                            is FolderTreeSessionChildRow.Session -> WorkspaceSessionRow(
                                folderPath = folder.path,
                                session = row.session,
                                onClick = { onSessionClick(folder.path, row.session.sessionName, null) },
                                onRename = { onRenameSession(row.session.sessionName) },
                                onStop = { onStopSession(row.session.sessionName) },
                                modifier = Modifier.weight(1f),
                            )
                            is FolderTreeSessionChildRow.Window -> WorkspaceSessionWindowRow(
                                folderPath = folder.path,
                                sessionName = row.sessionName,
                                window = row.window,
                                onClick = {
                                    onSessionClick(folder.path, row.sessionName, row.window.index)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
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

internal sealed interface FolderTreeSessionChildRow {
    data class Session(val session: FolderSessionEntry) : FolderTreeSessionChildRow
    data class Window(
        val sessionName: String,
        val window: FolderSessionWindowEntry,
    ) : FolderTreeSessionChildRow
}

internal fun folderTreeSessionChildRows(
    sessions: List<FolderSessionEntry>,
): List<FolderTreeSessionChildRow> =
    sessions.flatMap { session ->
        val windows = sortedSessionWindows(session)
        if (windows.size <= 1) {
            listOf(FolderTreeSessionChildRow.Session(session))
        } else {
            listOf(FolderTreeSessionChildRow.Session(session)) +
                windows.map { window ->
                    FolderTreeSessionChildRow.Window(
                        sessionName = session.sessionName,
                        window = window,
                    )
                }
        }
    }

private fun FolderTreeSessionChildRow.connectorTestTag(folderPath: String): String =
    when (this) {
        is FolderTreeSessionChildRow.Session -> folderSessionConnectorTestTag(
            folderPath,
            session.sessionName,
        )
        is FolderTreeSessionChildRow.Window -> folderSessionWindowConnectorTestTag(
            folderPath,
            sessionName,
            window.index,
            window.name,
        )
    }

@Composable
private fun WorkspaceSessionRow(
    folderPath: String,
    session: FolderSessionEntry,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onStop: () -> Unit,
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
        Spacer(modifier = Modifier.width(6.dp))
        // Terminal tile glyph leads the name alongside the status dot, matching
        // the flat-view rows and mockup #489 (#522 item 3).
        SessionTileGlyph(
            modifier = Modifier.testTag(folderSessionTileTestTag(folderPath, session.sessionName)),
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
        Spacer(modifier = Modifier.width(4.dp))
        // Session actions live behind an overflow menu: destructive Stop is a
        // menu item first, then the existing confirmation dialog.
        SessionActionsKebab(
            sessionName = session.sessionName,
            triggerTestTag = folderSessionActionsTestTag(folderPath, session.sessionName),
            openItemTestTag = folderSessionOpenMenuItemTestTag(folderPath, session.sessionName),
            renameItemTestTag = folderSessionRenameMenuItemTestTag(folderPath, session.sessionName),
            stopItemTestTag = folderSessionStopMenuItemTestTag(folderPath, session.sessionName),
            onOpen = onClick,
            onRename = onRename,
            onStop = onStop,
        )
    }
}

@Composable
private fun SessionActionsKebab(
    sessionName: String,
    triggerTestTag: String,
    openItemTestTag: String,
    renameItemTestTag: String,
    stopItemTestTag: String,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onStop: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(PocketShellDensity.tapTargetMin)
            .clickable(role = Role.Button, onClick = { expanded = true })
            .semantics { contentDescription = "Session actions $sessionName" }
            .testTag(triggerTestTag),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Kebab(
            contentDescription = "Session actions $sessionName",
            triggerTestTag = "$triggerTestTag:visual",
            expanded = expanded,
            onExpandedChange = { expanded = it },
            items = listOf(
                KebabItem(
                    label = "Open session",
                    onClick = onOpen,
                    testTag = openItemTestTag,
                ),
                KebabItem(
                    label = "Rename session",
                    onClick = onRename,
                    testTag = renameItemTestTag,
                ),
                KebabItem(
                    label = "Stop session",
                    onClick = onStop,
                    testTag = stopItemTestTag,
                ),
            ),
        )
    }
}

@Composable
private fun WorkspaceSessionWindowRow(
    folderPath: String,
    sessionName: String,
    window: FolderSessionWindowEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAgent = window.agentKind.isAgent()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PocketShellDensity.tapTargetMin)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = PocketShellDensity.rowPadV)
            .testTag(folderSessionWindowRowTestTag(folderPath, sessionName, window.index, window.name)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(treeWindowChildIndent))
        StatusDot(
            active = window.active || isAgent,
            modifier = Modifier.testTag(
                folderSessionWindowStatusDotTestTag(folderPath, sessionName, window.index, window.name),
            ),
        )
        Spacer(modifier = Modifier.width(6.dp))
        SessionTileGlyph(
            modifier = Modifier.testTag(
                folderSessionWindowTileTestTag(folderPath, sessionName, window.index, window.name),
            ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = windowDisplayTitle(window),
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        AgentTypeBadge(
            label = sessionBadgeLabel(
                FolderSessionEntry(
                    sessionName = sessionName,
                    lastActivity = null,
                    attached = false,
                    agentKind = window.agentKind,
                ),
            ),
            isAgent = isAgent,
            modifier = Modifier.testTag(
                folderSessionWindowBadgeTestTag(folderPath, sessionName, window.index, window.name),
            ),
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
    AgentTypeBadge(
        label = sessionBadgeLabel(session),
        isAgent = session.agentKind.isAgent(),
        modifier = modifier,
    )
}

@Composable
private fun AgentTypeBadge(
    label: String,
    isAgent: Boolean,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalPocketShellSemantic.current
    val fg = if (isAgent) semantic.agentAccent else PocketShellColors.TextSecondary
    val bg = if (isAgent) {
        semantic.agentAccent.copy(alpha = 0.16f)
    } else {
        PocketShellColors.SurfaceElev.copy(alpha = 0.72f)
    }
    Box(
        modifier = modifier
            .widthIn(max = SessionBadgeMaxWidth)
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = PocketShellDensity.chipPadH, vertical = PocketShellDensity.chipPadV),
    ) {
        Text(
            text = label,
            color = fg,
            style = PocketShellType.labelMono,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val SessionBadgeMaxWidth = 84.dp

/**
 * Compact host-detail tree gutter. The shared density token keeps the default
 * 16 dp workspace nesting step for generic tree rows, but this screen follows
 * the tighter terminal-style host-detail mockup (#565): project rows advance by
 * only one 8 dp rung and session connectors use a narrow 16 dp cell.
 */
private val treeProjectIndent = PocketShellSpacing.sm

/**
 * Indent applied to the session-children column under an expanded project so the
 * `├─/└─` spine sits just under the project's compact chevron/dot lead (#503,
 * #565). The connector cell ([treeConnectorCellWidth]) lives inside this column
 * and the spine's vertical x ([treeSpineX]) is the visual left edge of the child
 * sub-tree.
 */
private val treeChildIndent = PocketShellSpacing.sm

/** Extra offset that makes window rows read as children of a session row. */
private val treeWindowChildIndent = PocketShellSpacing.lg

/** Width of the per-row connector cell that carries the spine + horizontal stub. */
private val treeConnectorCellWidth = PocketShellSpacing.lg

/** Horizontal position of the vertical spine inside the connector cell. */
private val treeSpineX = PocketShellSpacing.xs

/**
 * One session row hung off the project's tree spine (#503).
 *
 * The connector is split into a leading fixed-width [Canvas] cell plus the row
 * content so the whole child block reads as ONE continuous vertical spine with a
 * clean `├─` per row and a `└─` terminating the last child:
 *
 *  - **Continuous spine:** every non-last row draws the vertical from the very
 *    top edge (`y = 0`) to the very bottom edge (`y = height`) with a butt cap,
 *    and the child [Column] carries no inter-row gap, so adjacent cells abut
 *    pixel-to-pixel and the spine never seams. The last row draws the vertical
 *    only down to the stub's `y` (the `└` corner), so the spine stops exactly at
 *    the last child rather than dangling below it.
 *  - **No stray stripes:** the spine x and stub y are snapped to whole device
 *    pixels so a 1 dp hairline lands on a single column/row of pixels instead of
 *    smearing across two and reading as a broken/double line.
 *  - **Clean stub:** the horizontal stub runs from the spine to the cell's right
 *    edge (the session row's leading edge) at the row's vertical centre, where
 *    the session status dot sits.
 */
@Composable
private fun TreeChildRow(
    last: Boolean,
    connectorTestTag: String,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        val connectorColor = PocketShellColors.Border
        Canvas(
            modifier = Modifier
                .width(treeConnectorCellWidth)
                .fillMaxHeight()
                .testTag(connectorTestTag),
        ) {
            val stroke = 1.dp.toPx()
            // Snap the spine x and stub y to whole device pixels so the 1 dp
            // hairline paints on a single pixel column/row — sub-pixel placement
            // is what made the spine read as discontinuous between rows (#503).
            val x = snapToPixel(treeSpineX.toPx())
            val stubY = snapToPixel(size.height * 0.5f) + 0.5f
            val verticalEnd = if (last) stubY else size.height
            drawLine(
                color = connectorColor,
                start = Offset(x, 0f),
                end = Offset(x, verticalEnd),
                strokeWidth = stroke,
                cap = StrokeCap.Butt,
            )
            drawLine(
                color = connectorColor,
                start = Offset(x, stubY),
                end = Offset(size.width, stubY),
                strokeWidth = stroke,
                cap = StrokeCap.Butt,
            )
        }
        content()
    }
}

/** Round a px value to the nearest whole device pixel (for crisp 1 dp hairlines). */
private fun snapToPixel(px: Float): Float = kotlin.math.round(px)

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
 * Leading terminal tile glyph on a session row (#522 item 3). Mockup #489 leads
 * every session row with a rounded `>_` terminal tile before the project name —
 * the rows previously led with only the [StatusDot]. The tile sits alongside (and
 * after) the status dot, so the row reads as `● >_ <name>`, keeping the dot's
 * active/idle signal while adding the terminal affordance the mockup shows.
 */
@Composable
private fun SessionTileGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .background(
                color = PocketShellColors.SurfaceElev.copy(alpha = 0.72f),
                shape = RoundedCornerShape(4.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(4.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ">_",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.labelMono,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
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
    val windows = sortedSessionWindows(session)
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

private fun sortedSessionWindows(session: FolderSessionEntry): List<FolderSessionWindowEntry> =
    session.windows.sortedWith(
        compareBy<FolderSessionWindowEntry> { it.index ?: Int.MAX_VALUE }
            .thenBy { it.name.orEmpty() },
    )

internal fun windowDisplayTitle(window: FolderSessionWindowEntry): String {
    val identity = window.index?.let { "w$it" } ?: "window"
    val hint = window.command?.trim()?.takeIf { it.isNotEmpty() }
        ?: window.name?.trim()?.takeIf { it.isNotEmpty() }
    return if (hint == null) identity else "$identity $hint"
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

@Composable
private fun RenameSessionDialog(
    sessionName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var newName by remember(sessionName) { mutableStateOf(sessionName) }
    val trimmed = newName.trim()
    val canRename = trimmed.isNotEmpty() && trimmed != sessionName
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PocketShellColors.Surface,
        title = {
            Text(
                text = "Rename session",
                color = PocketShellColors.Text,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Rename $sessionName on this host.",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("Session name") },
                    keyboardActions = KeyboardActions(onDone = {
                        if (canRename) onConfirm(trimmed)
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(RENAME_SESSION_FIELD_TAG),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trimmed) },
                enabled = canRename,
                modifier = Modifier.testTag(RENAME_SESSION_CONFIRM_TAG),
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(RENAME_SESSION_CANCEL_TAG)) {
                Text("Cancel", color = PocketShellColors.TextSecondary)
            }
        },
        modifier = Modifier.testTag(RENAME_SESSION_DIALOG_TAG),
    )
}

/**
 * Confirmation dialog for the host-detail "Stop session" action (#518).
 *
 * Stopping a session ends its tmux session on the host — a destructive,
 * non-undoable action — so it is gated behind an explicit confirm. Cancel /
 * tapping outside does nothing; only the Stop button kills the session.
 */
@Composable
private fun StopSessionDialog(
    sessionName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PocketShellColors.Surface,
        title = {
            Text(
                text = "Stop this session?",
                color = PocketShellColors.Text,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Text(
                text = "This ends the tmux session “$sessionName” on the host.",
                color = PocketShellColors.TextSecondary,
                fontSize = 13.sp,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PocketShellColors.Red,
                    contentColor = PocketShellColors.OnAccent,
                ),
                modifier = Modifier.testTag(STOP_SESSION_CONFIRM_TAG),
            ) {
                Text("Stop")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(STOP_SESSION_CANCEL_TAG)) {
                Text("Cancel", color = PocketShellColors.TextSecondary)
            }
        },
        modifier = Modifier.testTag(STOP_SESSION_DIALOG_TAG),
    )
}

private val FolderListBottomContentPadding = 12.dp

// Test tags exposed for the unit / connected E2E suite.
const val FOLDER_LIST_SCREEN_TAG: String = "folder-list:screen"
const val FOLDER_LIST_CONTENT_TAG: String = "folder-list:content"
const val FOLDER_LIST_PORT_FORWARDING_TAG: String = "folder-list:port-forwarding"
const val FOLDER_LIST_BOTTOM_SPACER_TAG: String = "folder-list:bottom-spacer"
const val FOLDER_LIST_BACK_TAG: String = "folder-list:back"
const val FOLDER_LIST_TITLE_TAG: String = "folder-list:title"
const val FOLDER_LIST_LOADING_TAG: String = "folder-list:loading"
const val FOLDER_LIST_LOADING_BODY_TAG: String = "folder-list:loading:body"
const val FOLDER_LIST_ERROR_TAG: String = "folder-list:error"
const val FOLDER_LIST_RETRY_TAG: String = "folder-list:retry"
const val FOLDER_LIST_EMPTY_TAG: String = "folder-list:empty"
const val FOLDER_LIST_EMPTY_ROOT_HINT_TAG: String = "folder-list:root:empty-hint"
const val FOLDER_LIST_SHOW_ALL_TAG: String = "folder-list:show-all"
const val FOLDER_LIST_FLAT_EMPTY_TAG: String = "folder-list:flat:empty"
const val FOLDER_LIST_FLAT_HEADER_TAG: String = "folder-list:flat:header"
const val FOLDER_LIST_FLAT_HEADER_DOT_TAG: String = "folder-list:flat:header:dot"
const val FOLDER_LIST_FLAT_HEADER_COUNTS_TAG: String = "folder-list:flat:header:counts"
const val FOLDER_LIST_FLAT_ACTIVE_SECTION_TAG: String = "folder-list:flat:section:active"
const val FOLDER_LIST_FLAT_IDLE_SECTION_TAG: String = "folder-list:flat:section:idle"

// Stable LazyColumn item keys for the flat-view section rows (#489). The host
// header band moved into the app bar (#522 item 1), so there is no in-list
// header item key any more.
private const val FLAT_ACTIVE_SECTION_KEY: String = "flat-section-active"
private const val FLAT_IDLE_SECTION_KEY: String = "flat-section-idle"
const val FOLDER_LIST_NEW_SESSION_FAB_TAG: String = "folder-list:new-session-fab"
const val FOLDER_LIST_BROWSE_REPOS_TAG: String = "folder-list:browse-repos"
const val FOLDER_LIST_REFRESH_SESSIONS_TAG: String = "folder-list:refresh-sessions"
const val FOLDER_LIST_USAGE_TAG: String = "folder-list:usage"
const val FOLDER_LIST_SETTINGS_TAG: String = "folder-list:settings"
const val FOLDER_LIST_VIEW_TOGGLE_TAG: String = "folder-list:view-toggle"
const val FOLDER_LIST_WORKSPACE_SETTINGS_TAG: String = "folder-list:workspace-settings"
const val FOLDER_LIST_ASSISTANT_TAG: String = "folder-list:assistant"
/** Host-detail header `⋮` kebab overflow button (#522 item 2). */
const val FOLDER_LIST_OVERFLOW_TAG: String = "folder-list:overflow"
const val FOLDER_LIST_ASSISTANT_PANEL_TAG: String = "folder-list:assistant:panel"
const val FOLDER_LIST_ASSISTANT_PROMPT_TAG: String = "folder-list:assistant:prompt"
const val FOLDER_LIST_ASSISTANT_PROMPT_MIC_TAG: String = "folder-list:assistant:prompt-mic"
const val FOLDER_LIST_ASSISTANT_SUBMIT_TAG: String = "folder-list:assistant:submit"
const val FOLDER_LIST_ASSISTANT_CLOSE_TAG: String = "folder-list:assistant:close"
const val FOLDER_LIST_ACTION_STATUS_TAG: String = "folder-list:action-status"
const val FOLDER_LIST_ACTION_STATUS_DISMISS_TAG: String = "folder-list:action-status:dismiss"

// Issue #518 — "Stop session" confirmation dialog.
const val STOP_SESSION_DIALOG_TAG: String = "folder-list:stop-session:dialog"
const val STOP_SESSION_CONFIRM_TAG: String = "folder-list:stop-session:confirm"
const val STOP_SESSION_CANCEL_TAG: String = "folder-list:stop-session:cancel"
const val RENAME_SESSION_DIALOG_TAG: String = "folder-list:rename-session:dialog"
const val RENAME_SESSION_FIELD_TAG: String = "folder-list:rename-session:field"
const val RENAME_SESSION_CONFIRM_TAG: String = "folder-list:rename-session:confirm"
const val RENAME_SESSION_CANCEL_TAG: String = "folder-list:rename-session:cancel"

fun folderRowTestTag(path: String): String = "folder-list:row:$path"
fun folderHeaderClickTestTag(path: String): String = "folder-list:header-click:$path"
fun folderHeaderLabelTag(path: String): String = "folder-list:header:$path"
fun folderCountPillTestTag(path: String): String = "folder-list:count:$path"
fun folderListFlatRowTestTag(sessionName: String): String = "folder-list:flat-row:$sessionName"
fun folderListFlatRowStatusDotTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:status"
/** Tags the leading terminal tile glyph on a flat host-detail row (#522 item 3). */
fun folderListFlatRowTileTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:tile"
fun folderListFlatRowBadgeTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:badge"
fun folderListFlatRowActionsTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:stop"

/** Back-compat alias for tests/tools that still use the pre-#598 stop-specific name. */
fun folderListFlatRowStopTestTag(sessionName: String): String =
    folderListFlatRowActionsTestTag(sessionName)
fun folderListFlatRowOpenMenuItemTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:open:item"
fun folderListFlatRowRenameMenuItemTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:rename:item"
fun folderListFlatRowStopMenuItemTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:stop:item"
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
/** Tags the leading terminal tile glyph on a tree session child row (#522 item 3). */
fun folderSessionTileTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:tile"
fun folderSessionBadgeTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:badge"
fun folderSessionActionsTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:stop"

/** Back-compat alias for tests/tools that still use the pre-#598 stop-specific name. */
fun folderSessionStopTestTag(folderPath: String, sessionName: String): String =
    folderSessionActionsTestTag(folderPath, sessionName)
fun folderSessionOpenMenuItemTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:open:item"
fun folderSessionRenameMenuItemTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:rename:item"
fun folderSessionStopMenuItemTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:stop:item"
/** Tags the `├─/└─` tree connector cell on an expanded session child row (#503). */
fun folderSessionConnectorTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:connector"
fun folderSessionWindowConnectorTestTag(
    folderPath: String,
    sessionName: String,
    windowIndex: Int?,
    windowName: String?,
): String = "folder-list:detail:$folderPath:$sessionName:window:${windowStableKey(windowIndex, windowName)}:connector"
fun folderSessionWindowRowTestTag(
    folderPath: String,
    sessionName: String,
    windowIndex: Int?,
    windowName: String?,
): String = "folder-list:detail:$folderPath:$sessionName:window:${windowStableKey(windowIndex, windowName)}"
fun folderSessionWindowStatusDotTestTag(
    folderPath: String,
    sessionName: String,
    windowIndex: Int?,
    windowName: String?,
): String = "${folderSessionWindowRowTestTag(folderPath, sessionName, windowIndex, windowName)}:status"
fun folderSessionWindowTileTestTag(
    folderPath: String,
    sessionName: String,
    windowIndex: Int?,
    windowName: String?,
): String = "${folderSessionWindowRowTestTag(folderPath, sessionName, windowIndex, windowName)}:tile"
fun folderSessionWindowBadgeTestTag(
    folderPath: String,
    sessionName: String,
    windowIndex: Int?,
    windowName: String?,
): String = "${folderSessionWindowRowTestTag(folderPath, sessionName, windowIndex, windowName)}:badge"
fun folderTreeRootTestTag(path: String): String = "folder-list:tree-root:$path"
fun folderTreeRootLabelTag(path: String): String = "folder-list:tree-root:$path:label"
fun folderTreeRootCountTag(path: String): String = "folder-list:tree-root:$path:count"
fun folderTreeRootCreateTestTag(path: String): String = "folder-list:tree-root:$path:create"
fun folderTreeRootActionsTestTag(path: String): String = "folder-list:tree-root:$path:actions"
fun folderTreeRootEmptyHintAddTestTag(path: String): String =
    "folder-list:tree-root:$path:empty-hint:add"
fun folderTreeRootEmptyHintActionLabelTestTag(path: String): String =
    "folder-list:tree-root:$path:empty-hint:action-label"

private fun windowStableKey(windowIndex: Int?, windowName: String?): String =
    windowIndex?.let { "w$it" } ?: windowName?.ifBlank { null } ?: "unknown"
