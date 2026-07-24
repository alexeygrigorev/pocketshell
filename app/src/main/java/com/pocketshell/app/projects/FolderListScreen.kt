package com.pocketshell.app.projects

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.pocketshell.app.voice.InlineDictationErrorStrip
import com.pocketshell.app.voice.appendDictationText
import com.pocketshell.app.voice.toMicButtonState
import com.pocketshell.uikit.components.AgentKindBadge
import com.pocketshell.uikit.components.AgentStateChip
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.DisclosureIcon
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.MicButton
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

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
 *  - Tap "App settings" in the app-bar overflow → opens the global app settings.
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
    onOpenSession: (
        sessionName: String,
        startDirectory: String?,
        tmuxSessionId: String?,
        sessionCreated: Long?,
    ) -> Unit,
    onOpenSessionWindow: (
        sessionName: String,
        startDirectory: String?,
        windowIndex: Int?,
        tmuxSessionId: String?,
        sessionCreated: Long?,
    ) -> Unit =
        { sessionName, startDirectory, _, tmuxSessionId, sessionCreated ->
            onOpenSession(sessionName, startDirectory, tmuxSessionId, sessionCreated)
        },
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
    /**
     * Issue #646: open the read-only Git commit-history view for a project.
     * Fired with the tapped folder's canonical path + label so the caller
     * (MainActivity) can route to `AppDestination.GitHistory`, reusing the SSH
     * credentials this screen already holds.
     */
    onGitHistory: (path: String, label: String) -> Unit = { _, _ -> },
    /**
     * Issue #643: open the SFTP file explorer rooted at [startDir]. Fired from
     * the host-detail overflow ("Browse files" → `~`) and from a folder's
     * long-press action sheet ("Browse files" → that folder's path). The caller
     * (MainActivity) routes to `AppDestination.FileExplorer`, reusing the SSH
     * credentials this screen already holds.
     */
    onBrowseFiles: (startDir: String) -> Unit = {},
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
    // Issue #1155: the "this session no longer exists" recovery prompt is now
    // owned app-level by `MainActivity` (via `StaleSessionPromptController`) so
    // it surfaces on the cold-restore path too, not only an in-tree tap. The
    // folder tree still drops the confirmed-gone row from its list for accuracy
    // (FolderListViewModel.onStaleSession), it just no longer raises the dialog.
    // Issue #885: passive host-CLI-version mismatch, detected from the regular
    // `pocketshell tree` payload on every host open — surfaced as a dismissible
    // update prompt (NOT a slow blocking on-open `--version` wait).
    val cliVersionMismatch by viewModel.cliVersionMismatch.collectAsState()
    // Issue #947: progress of the banner's one-tap Update action (host upgrade
    // over the warm session). Drives the Update button's spinner + failure line.
    val cliVersionUpdateState by viewModel.cliVersionUpdateState.collectAsState()
    val assistantState by viewModel.assistantState.collectAsState()
    val claudeProfiles by viewModel.claudeProfiles.collectAsState()
    val codexProfiles by viewModel.codexProfiles.collectAsState()
    val assistantDictationUiState = remember(assistantDictationViewModel) {
        assistantDictationViewModel?.uiState
            ?: MutableStateFlow(InlineDictationViewModel.UiState())
    }
    val assistantDictationState by assistantDictationUiState.collectAsState()
    val context = LocalContext.current
    val importScope = rememberCoroutineScope()
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

    // Issue #1509: the notification-permission request is now driven by the
    // single session-tree setup coordinator in the view model (folded in from the
    // deleted MainActivity.onCreate app-open trigger). When the setup raises the
    // request, launch the Android 13+ POST_NOTIFICATIONS runtime prompt from the
    // session tree — the one place — then consume it so it fires at most once.
    val notificationPermissionRequest by viewModel.notificationPermissionRequest.collectAsState()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* best-effort — the in-app indicators work regardless of the grant */ }
    LaunchedEffect(notificationPermissionRequest) {
        if (notificationPermissionRequest) {
            runCatching {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            viewModel.onNotificationPermissionRequestConsumed()
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
            val appContext = context.applicationContext
            importScope.launch {
                val payload = runCatching {
                    folderImportPayload(
                        resolver = appContext.contentResolver,
                        uri = uri,
                        cacheDir = appContext.cacheDir,
                    )
                }.getOrElse {
                    Toast.makeText(appContext, "Couldn't read selected file", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                viewModel.importFileIntoFolder(
                    folderPath = target.path,
                    payload = payload,
                )
            }
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
                onBrowseFiles = { onBrowseFiles("~") },
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
                    isRefreshing = s.isRefreshing,
                    portForwarding = s.portForwarding,
                    showFlatFolderList = showFlatFolderList,
                    actionStatus = actionStatus,
                    onDismissActionStatus = viewModel::clearActionStatus,
                    onOpenPortForwarding = onOpenPortForwarding,
                    onCreateTopLevelSession = onCreateTopLevelSession,
                    onSessionClick = { folderPath, session, windowIndex ->
                        onOpenSessionWindow(
                            session.sessionName,
                            folderPath.takeUnless { it == FolderListViewModel.UNTRACKED_PATH },
                            windowIndex,
                            session.tmuxSessionId,
                            session.sessionCreated,
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
                    onPullToRefresh = viewModel::refreshSessions,
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

        // Issue #885: the passive host-CLI-version update prompt. Non-displacing
        // overlay pinned to the bottom edge (mirrors the failure banner) so it
        // never pushes a session row down; appears on any open where the regular
        // `tree` payload reports a host CLI older than this app build expects.
        cliVersionMismatch?.let { mismatch ->
            CliVersionMismatchBanner(
                message = PayloadVersionCheck.outdatedHostPrompt(mismatch),
                updateState = cliVersionUpdateState,
                onUpdate = viewModel::runHostPocketshellUpgrade,
                onDismiss = viewModel::dismissCliVersionMismatch,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }

    pickerFolder?.let { target ->
        SessionTypePickerSheet(
            folderPath = target.path,
            folderLabel = target.label,
            onDismiss = { pickerFolder = null },
            suggestStartDirectories = suggestStartDirectories,
            claudeProfiles = claudeProfiles,
            codexProfiles = codexProfiles,
            creating = (state as? FolderListUiState.Ready)?.isCreatingSession == true,
            allowMissingStartDirectoryCreation = true,
            // Issue #1184: prefill the editable "Session name" field with the
            // directory-derived default for the chosen start folder.
            deriveDefaultName = { dir ->
                defaultSessionBaseName(dir, conventionalRemoteHome(username))
            },
            onCreate = { choice ->
                fun createPickedSession(cwd: String) {
                    val resolvedChoice = choice.copy(
                        startDirectory = cwd,
                        createStartDirectory = null,
                    )
                    val newName = derivedSessionName(
                        choice = resolvedChoice,
                        homeDirectory = conventionalRemoteHome(username),
                        existingNames = knownSessionNames(state),
                    )
                    viewModel.createSession(
                        sessionName = newName,
                        cwd = cwd,
                        startCommand = resolvedChoice.startCommand(claudeProfiles, codexProfiles),
                        // Epic #821 Workstream A: record the picked kind onto the
                        // new tree node immediately (no detection round-trip).
                        chosenKind = resolvedChoice.sessionAgentKind,
                        onResolved = { resolved ->
                            pickerFolder = null
                            onSessionCreated(resolved, cwd)
                        },
                        onFinished = {
                            pickerFolder = null
                        },
                    )
                }

                val missingFolder = choice.createStartDirectory
                if (missingFolder != null) {
                    pickerFolder = null
                    viewModel.createEmptyProject(
                        parentPath = missingFolder.parentPath,
                        folderName = missingFolder.folderName,
                        onCreated = { createdPath ->
                            createPickedSession(createdPath)
                        },
                    )
                } else {
                    createPickedSession(choice.startDirectory)
                }
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
            // Git history (#646). Suppressed for roots — they aren't projects
            // themselves — using the same envSources signal as the env row.
            onGitHistory = if (target.envSources.isNotEmpty()) {
                {
                    actionFolder = null
                    onGitHistory(target.path, target.label)
                }
            } else {
                null
            },
            // Browse files (#643): always available — open the explorer rooted
            // at this folder's path so the user sees its file tree.
            onBrowseFiles = {
                actionFolder = null
                onBrowseFiles(target.path)
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
                viewModel.createEmptyProject(
                    parentPath = target.path,
                    folderName = name,
                    onCreated = { path ->
                        pickerFolder = PickerTarget(
                            path = path,
                            label = FolderListViewModel.defaultLabelForPath(path),
                        )
                    },
                )
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
            onCreateNamedProject = { folderName ->
                rootAddSheet = null
                viewModel.createEmptyProject(
                    parentPath = root.path,
                    folderName = folderName,
                    onCreated = { path ->
                        pickerFolder = PickerTarget(
                            path = path,
                            label = FolderListViewModel.defaultLabelForPath(path),
                        )
                    },
                )
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

private suspend fun folderImportPayload(
    resolver: ContentResolver,
    uri: Uri,
    cacheDir: File,
): FolderImportPayload = withContext(Dispatchers.IO) {
    withTimeoutOrNull(FOLDER_IMPORT_SAF_TIMEOUT_MS) {
        val displayName = ShareUploader.queryUriDisplayName(resolver, uri)
            ?: uri.lastPathSegment
            ?: "imported"
        val mime = resolver.getType(uri)
        val sanitised = FilenameSanitiser.sanitise(
            input = displayName,
            defaultExtension = ShareUploader.extensionForMimeType(mime),
        )
        val temp = copyImportUriToTempFile(resolver, uri, cacheDir)
        FolderImportPayload(
            remoteName = sanitised.render(),
            length = temp.length().takeIf { it > 0L } ?: queryUriSize(resolver, uri),
            openStream = { temp.inputStream() },
            cleanup = { temp.delete() },
        )
    } ?: throw IllegalStateException("Timed out reading selected file.")
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

private fun copyImportUriToTempFile(
    resolver: ContentResolver,
    uri: Uri,
    cacheDir: File,
): File {
    val dir = File(cacheDir, "folder-imports").also { it.mkdirs() }
    val temp = File.createTempFile("folder-import-", ".bin", dir)
    try {
        resolver.openInputStream(uri).use { input ->
            if (input == null) throw IllegalStateException("Couldn't read selected file.")
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        return temp
    } catch (t: Throwable) {
        temp.delete()
        throw t
    }
}

private const val FOLDER_IMPORT_SAF_TIMEOUT_MS: Long = 10_000L

/**
 * Host-detail header (#522 items 1 + 2). Mockup #489 shows the host name ONCE,
 * with the `N active · M idle · K sessions` count line directly beneath it and a
 * single `⋮` kebab on the right — not the old three cramped circular action
 * buttons and not a second host-name band in the list. Host assistant, Browse
 * repos, global app settings, and Workspace settings are items in the kebab
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
    onBrowseFiles: () -> Unit,
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
                onBrowseFiles = onBrowseFiles,
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
    onBrowseFiles: () -> Unit,
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
                label = "Browse files",
                onClick = onBrowseFiles,
                contentDescription = "Browse files",
                testTag = FOLDER_LIST_BROWSE_FILES_TAG,
            ),
            KebabItem(
                label = "Browse repos",
                onClick = onBrowseRepos,
                contentDescription = "Browse repos",
                testTag = FOLDER_LIST_BROWSE_REPOS_TAG,
            ),
            KebabItem(
                label = "Usage",
                onClick = onOpenUsage,
                contentDescription = "Usage",
                testTag = FOLDER_LIST_USAGE_TAG,
            ),
            KebabItem(
                label = "App settings",
                onClick = onOpenSettings,
                contentDescription = "App settings",
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
            .background(PocketShellColors.Surface, PocketShellShapes.medium)
            .border(1.dp, PocketShellColors.AccentDim, PocketShellShapes.medium)
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
            PocketShellButton(
                text = "Close",
                onClick = onDismiss,
                modifier = Modifier.testTag(FOLDER_LIST_ASSISTANT_CLOSE_TAG),
                variant = ButtonVariant.Text,
                compact = true,
            )
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
                PocketShellButton(
                    onClick = {
                        val text = prompt.trim()
                        if (text.isNotEmpty()) {
                            prompt = ""
                            onSubmit(text)
                        }
                    },
                    variant = ButtonVariant.Text,
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
            LoadingIndicator.Spinner(size = SpinnerSize.Medium)
            Spacer(modifier = Modifier.height(PocketShellSpacing.md))
            Text(
                text = "Loading workspace tree",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
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
        PocketShellButton(
            text = "Retry",
            onClick = onRetry,
            variant = ButtonVariant.Text,
            modifier = Modifier.testTag(FOLDER_LIST_RETRY_TAG),
        )
    }
}

// Issue #639: internal (was private) so the refresh-indicator + stable-order
// screenshot test can drive the real content composable directly.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderListContent(
    hostName: String,
    folders: List<FolderRow>,
    treeRoots: List<FolderTreeRoot>,
    flatSessions: List<FolderSessionEntry>,
    expandedProjectPaths: Set<String>,
    isRefreshing: Boolean,
    portForwarding: HostPortForwardingSummary,
    showFlatFolderList: Boolean,
    actionStatus: FolderActionStatus,
    onDismissActionStatus: () -> Unit,
    onOpenPortForwarding: () -> Unit,
    onCreateTopLevelSession: () -> Unit,
    onSessionClick: (folderPath: String, session: FolderSessionEntry, windowIndex: Int?) -> Unit,
    onRenameSession: (sessionName: String) -> Unit,
    onStopSession: (sessionName: String) -> Unit,
    onFolderActions: (FolderRow) -> Unit,
    onCreateInRoot: (FolderTreeRoot) -> Unit,
    onRootActions: (FolderTreeRoot) -> Unit,
    onToggleProjectExpanded: (FolderRow) -> Unit,
    // EPIC #679 requirement #4: the swipe-down pull-to-refresh gesture is the
    // explicit manual-reconcile affordance (no extra button). Defaulted so the
    // existing render/screenshot harnesses that don't drive a refresh still
    // compile.
    onPullToRefresh: () -> Unit = {},
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
    // Issue #639: the refresh indicator must NOT displace the list. A routine
    // refresh fires on every session switch, so a top in-list row that pushes
    // every row down makes the whole list jump under the user's finger. The
    // single non-displacing affordance is the [PullToRefreshBox]'s own circular
    // spinner, which overlays the content and reflows nothing (issue #750 removed
    // the redundant second top linear bar that used to render alongside it).
    //
    // EPIC #679 requirement #4: the standard swipe-down pull-to-refresh gesture
    // is the explicit manual-reconcile affordance (no extra button). The
    // [PullToRefreshBox] hosts the LazyColumn so a drag-down fires
    // [onPullToRefresh] (→ the maintained-tree reconcile) and surfaces the
    // refreshing spinner driven by the [isRefreshing] flag — the SINGLE loading
    // indicator for the refresh state (issue #750).
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onPullToRefresh,
        state = pullState,
        modifier = Modifier
            .fillMaxSize()
            .testTag(FOLDER_LIST_PULL_TO_REFRESH_TAG),
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
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
        // Issue #656: the action status no longer renders as a top-of-list row.
        // A routine success emits no status at all (the list change is the
        // feedback), while in-flight/failed work surfaces as a non-displacing
        // overlay pinned to the bottom of this Box, so no action ever pushes the
        // list down.
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
                                    session,
                                    null,
                                )
                            },
                            onOpen = {
                                onSessionClick(
                                    sessionFolderPaths[session.sessionName]
                                        ?: FolderListViewModel.UNTRACKED_PATH,
                                    session,
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
                                    session,
                                    null,
                                )
                            },
                            onOpen = {
                                onSessionClick(
                                    sessionFolderPaths[session.sessionName]
                                        ?: FolderListViewModel.UNTRACKED_PATH,
                                    session,
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
            // Issue #965 (ANR — defeated virtualization): emit each watched root's
            // header AND each of its project folders as SEPARATE LazyColumn items,
            // so off-screen folder rows do NOT compose. The previous code put a
            // single `items(treeRoots)` entry per root and composed ALL of that
            // root's project folders eagerly in a nested `Column { root.folders
            // .forEach { FolderGroup(...) } }` — so the dominant "git" root with 71
            // projects measured/laid-out all 71 `FolderGroup`s (plus each expanded
            // folder's session rows) in ONE synchronous composition pass at cold
            // open, a multi-frame Main-thread stall. Flattening into per-folder
            // `items` restores LazyColumn virtualization across the whole tree.
            treeRoots.forEach { root ->
                folderTreeRootItems(
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
        // Issue #750: exactly ONE loading indicator. The refresh affordance is the
        // single circular spinner the [PullToRefreshBox] already surfaces from the
        // same [isRefreshing] flag. The previous thin top linear progress bar (#639)
        // rendered AT THE SAME TIME as that circular spinner — two loaders at once
        // (the maintainer's screenshot: cyan top line under the header + a circular
        // spinner over the list) — so it is removed (hard-cut, D22). The
        // pull-to-refresh indicator is itself non-displacing (it overlays the
        // content and reflows no rows), so the #639 "don't push rows down on every
        // session switch" requirement still holds with the single indicator.
        // Issue #656: action feedback surfaces as a non-displacing snackbar-style
        // overlay pinned to the bottom edge of the content. Like the #639 refresh
        // bar it occupies zero layout slot in the list, so create/failure feedback
        // never pushes a row down.
        when (val status = actionStatus) {
            FolderActionStatus.Idle -> Unit
            is FolderActionStatus.Running -> FolderActionProgressBanner(
                message = status.message,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        horizontal = FolderListBottomContentPadding,
                        vertical = FolderListBottomContentPadding,
                    ),
            )
            is FolderActionStatus.Failed -> FolderActionFailureBanner(
                message = status.message,
                onDismiss = onDismissActionStatus,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
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

/**
 * Issue #656: non-displacing in-progress affordance for host-detail actions.
 * Pinned as an overlay to the bottom edge of the content [Box], it occupies zero
 * layout slot in the list (mirroring the #639 refresh progress bar), so a
 * pending create is visible without pushing any list row down.
 */
@Composable
private fun FolderActionProgressBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    val color = PocketShellColors.Accent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, PocketShellShapes.medium)
            .background(color.copy(alpha = 0.12f), PocketShellShapes.medium)
            .border(1.dp, color.copy(alpha = 0.4f), PocketShellShapes.medium)
            .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm)
            .testTag(FOLDER_LIST_ACTION_STATUS_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
    ) {
        LoadingIndicator.Spinner(size = SpinnerSize.Small)
        Text(
            text = message,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Issue #656: non-displacing failure affordance for host-detail actions. Pinned
 * as an overlay to the bottom edge of the content [Box], it occupies zero layout
 * slot in the list (mirroring the #639 refresh progress bar), so a failed action
 * is visible without pushing any list row down. Routine success emits no
 * [FolderActionStatus] at all, so the list change alone is the feedback and
 * nothing displaces the list.
 */
@Composable
private fun FolderActionFailureBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = PocketShellColors.Red
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(FOLDER_LIST_ACTION_STATUS_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            modifier = Modifier.weight(1f),
        )
        PocketShellButton(
            text = "Dismiss",
            onClick = onDismiss,
            modifier = Modifier.testTag(FOLDER_LIST_ACTION_STATUS_DISMISS_TAG),
            variant = ButtonVariant.Text,
            compact = true,
        )
    }
}

/**
 * Issue #885: the passive host-CLI-version update prompt. Like
 * [FolderActionFailureBanner] it is a non-displacing bottom-edge overlay (zero
 * layout slot in the list), so it never pushes a session row down. It appears on
 * ANY host open — warm/direct included — where the regular `pocketshell tree`
 * payload reports a host CLI older than this app build expects (no slow blocking
 * on-open `--version` exec). The [message] carries the version delta + the
 * copy-paste update command.
 */
// Issue #947: `internal` (not `private`) so the connected UI-gate androidTest
// (`CliVersionMismatchBannerUpdateButtonTest`) can compose the PRODUCTION banner
// in its real Idle/Running/Failure states and assert viewport CONTAINMENT of the
// Update + Dismiss controls (the #641/#657/#780 real-component model).
@Composable
internal fun CliVersionMismatchBanner(
    message: String,
    updateState: FolderListViewModel.CliVersionUpdateState,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = PocketShellColors.Accent
    val running = updateState is FolderListViewModel.CliVersionUpdateState.Running
    val failure = updateState as? FolderListViewModel.CliVersionUpdateState.Failure
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, PocketShellShapes.medium)
            .background(color.copy(alpha = 0.12f), PocketShellShapes.medium)
            .border(1.dp, color.copy(alpha = 0.4f), PocketShellShapes.medium)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(FOLDER_LIST_CLI_VERSION_BANNER_TAG),
    ) {
        Text(
            text = message,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            modifier = Modifier.fillMaxWidth(),
        )
        // Issue #947: the upgrade failure line (installer stderr / timeout /
        // no-installer), shown above the action row so the user can read it and
        // then Retry or Dismiss — the spinner never sticks.
        failure?.let { fail ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = fail.message,
                color = PocketShellColors.Red,
                style = PocketShellType.bodyDense,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(FOLDER_LIST_CLI_VERSION_UPDATE_ERROR_TAG),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (running) {
                // Issue #947: running spinner — Update is in flight; the action
                // buttons are replaced so a second tap can't double-run.
                LoadingIndicator.Spinner(
                    size = SpinnerSize.Small,
                    modifier = Modifier.testTag(FOLDER_LIST_CLI_VERSION_UPDATE_SPINNER_TAG),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Updating…",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                )
            } else {
                PocketShellButton(
                    // Issue #947: a no-op upgrade re-raises a failure, so offer
                    // "Retry" once the first attempt failed.
                    text = if (failure != null) "Retry" else "Update",
                    onClick = onUpdate,
                    modifier = Modifier.testTag(FOLDER_LIST_CLI_VERSION_UPDATE_TAG),
                    variant = ButtonVariant.Primary,
                    compact = true,
                )
                Spacer(modifier = Modifier.width(8.dp))
                PocketShellButton(
                    text = "Dismiss",
                    onClick = onDismiss,
                    modifier = Modifier.testTag(FOLDER_LIST_CLI_VERSION_DISMISS_TAG),
                    variant = ButtonVariant.Text,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, PocketShellShapes.medium)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.medium)
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
 *  - trailing: compact agent-kind monogram — purple for Claude/Codex/OpenCode,
 *    grey for Shell.
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
                // Issue #1237: the agent resting-state chip leads the trailing
                // lane when known; absent (no chip, no spacer) when unknown.
                session.agentState.chipLabel?.let {
                    AgentStateChip(
                        state = session.agentState,
                        modifier = Modifier.testTag(
                            folderListFlatRowAgentStateChipTestTag(session.sessionName),
                        ),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                // Issue #858: a non-default profile (e.g. z.ai Claude) gets a
                // distinct chip BEFORE the kind badge so it reads as a separate
                // dimension. A default / non-profiled / legacy session shows no
                // chip — just the plain kind badge.
                profileChipLabel(session.recordedProfile)?.let { chip ->
                    ProfileChip(
                        label = chip,
                        modifier = Modifier.testTag(
                            folderListFlatRowProfileChipTestTag(session.sessionName),
                        ),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
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
            .background(PocketShellColors.Surface, PocketShellShapes.medium)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.medium)
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

/**
 * Issue #965 (ANR — restore virtualization): emit one watched root as SEPARATE
 * [LazyColumn] items — a header item plus one item PER project folder — instead
 * of a single list item that eagerly composed `root.folders.forEach { … }` in a
 * nested `Column`. With the dominant "git" root holding 71 projects, the old
 * single-item shape composed/measured all 71 `FolderGroup`s (plus each expanded
 * folder's session rows + agent badges) in one synchronous pass at cold open — a
 * multi-frame Main-thread stall that contributed to the folder-list ANR.
 * Per-folder items let LazyColumn virtualize: off-screen folder rows never
 * compose.
 *
 * The root container test tag ([folderTreeRootTestTag]) now lives on the header
 * row so existing instrumentation that locates a root by tag still finds it.
 */
private fun LazyListScope.folderTreeRootItems(
    root: FolderTreeRoot,
    expandedProjectPaths: Set<String>,
    onSessionClick: (folderPath: String, session: FolderSessionEntry, windowIndex: Int?) -> Unit,
    onRenameSession: (sessionName: String) -> Unit,
    onStopSession: (sessionName: String) -> Unit,
    onFolderActions: (FolderRow) -> Unit,
    onCreateInRoot: (FolderTreeRoot) -> Unit,
    onRootActions: (FolderTreeRoot) -> Unit,
    onToggleProjectExpanded: (FolderRow) -> Unit,
) {
    item(key = "tree-root-header:${root.path}") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(folderTreeRootTestTag(root.path))
                .padding(bottom = 4.dp),
        ) {
            FolderTreeRootHeader(
                root = root,
                onCreateInRoot = { onCreateInRoot(root) },
                onRootActions = { onRootActions(root) },
            )
            if (root.folders.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                EmptyRootHint(
                    rootPath = root.path,
                    candidateCount = root.addSheetProjects.size,
                    onCreate = { onCreateInRoot(root) },
                )
            }
        }
    }
    items(root.folders, key = { "tree-root-folder:${root.path}:${it.path}" }) { folder ->
        Box(modifier = Modifier.padding(start = treeProjectIndent, top = 2.dp, bottom = 2.dp)) {
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
            // One subtle accent `+` (add project) — the overflow kebab is gone;
            // root actions stay on the band long-press (`onLongClick`).
            Spacer(modifier = Modifier.width(6.dp))
            SubtleAddButton(
                contentDescription = "Add project",
                onClick = onCreateInRoot,
                testTag = folderTreeRootCreateTestTag(root.path),
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

/**
 * Inactive / empty watched-root callout (#603).
 *
 * Redesigned to read as ONE dense, SINGLE-LINE project-tree row — visually
 * indistinguishable in chrome weight from an active project row
 * ([FolderHeader]) and, if anything, lighter: a muted (idle) [StatusDot] leads,
 * the title carries the inactive count (`N inactive folders` / `No folders
 * yet`), and the row's single trailing affordance is the SAME subtle accent `+`
 * ([SubtleAddButton]) the active root/folder rows use. `+` means the same thing
 * at every level — add here — so the inactive callout stops looking like a
 * heavier, divergent "+ Review / + Add" pill inside the dense tree (maintainer
 * feedback 2026-06-07).
 *
 * The earlier redesign carried a second instructional subtitle line ("Tap to
 * review folders under this root.") that ellipsised to truncated mono prose on a
 * phone width (`…under this r…`) — it read like a clipped bug, not a design, and
 * made the callout a two-line block heavier than the dense tree wants (#603
 * design sign-off, #679 Child D). That instructional line is redundant: the
 * title already names the state, the muted idle dot signals "inactive", and the
 * visible `+` is the affordance. So the callout is now a clean single-line row.
 * The action verb (`Review inactive project folders` / `Add project folder`)
 * lives only in the `+`'s content description for a11y/instrumentation, never as
 * a competing visible text line. The whole row is clickable; the trailing `+` is
 * the explicit visible target.
 */
@Composable
private fun EmptyRootHint(rootPath: String, candidateCount: Int, onCreate: () -> Unit) {
    val title = if (candidateCount > 0) {
        candidateCount.countLabel("inactive folder")
    } else {
        "No folders yet"
    }
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
            leading = {
                StatusDot(active = false)
            },
            trailing = {
                // One subtle accent `+` — identical chrome to the active root and
                // folder rows (#603). The plus glyph is tagged so the same
                // instrumentation that located the retired "+ Review/Add" pill
                // keeps resolving the affordance; the action verb lives only in
                // the content description (the visible row carries just the title).
                SubtleAddButton(
                    contentDescription = actionDescription,
                    onClick = onCreate,
                    testTag = folderTreeRootEmptyHintActionPlusTestTag(rootPath),
                )
            },
            onClick = onCreate,
            modifier = Modifier
                .background(PocketShellColors.Surface.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
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
    onSessionClick: (folderPath: String, session: FolderSessionEntry, windowIndex: Int?) -> Unit,
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
                                expandedIntoWindows = row.expandedIntoWindows,
                                onClick = { onSessionClick(folder.path, row.session, null) },
                                onRename = { onRenameSession(row.session.sessionName) },
                                onStop = { onStopSession(row.session.sessionName) },
                                modifier = Modifier.weight(1f),
                            )
                            is FolderTreeSessionChildRow.Window -> WorkspaceSessionWindowRow(
                                folderPath = folder.path,
                                sessionName = row.sessionName,
                                window = row.window,
                                onClick = {
                                    onSessionClick(folder.path, row.session, row.window.index)
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
        DisclosureIcon(
            expanded = expanded,
            tint = PocketShellColors.TextSecondary,
            size = 16.dp,
            modifier = Modifier
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
        // The folder's one visible affordance: a subtle accent `+` that opens
        // the new-session flow for this folder — the frequent, additive action
        // gets a visible target (maintainer call, 2026-06-09). Rename / env /
        // import / clone / remove stay on the row long-press
        // (`onLongClick = onFolderActions`). Untracked has no filesystem path,
        // so it carries no `+`.
        if (folder.path != FolderListViewModel.UNTRACKED_PATH) {
            SubtleAddButton(
                contentDescription = "New session in ${folderDisplayLabel(folder.label, folder.path)}",
                onClick = onFolderActions,
                testTag = folderDetailActionsTestTag(folder.path),
            )
        }
    }
}

internal sealed interface FolderTreeSessionChildRow {
    /**
     * A session parent row. [expandedIntoWindows] is true when this session is
     * broken out into per-window [Window] child rows below it (multi-window).
     * When true the parent row drops its redundant inline window-agent summary
     * and trailing agent badge — the window child rows already carry that
     * detail, so the agent type is shown once per window, not three times (#675).
     */
    data class Session(
        val session: FolderSessionEntry,
        val expandedIntoWindows: Boolean = false,
    ) : FolderTreeSessionChildRow
    data class Window(
        val session: FolderSessionEntry,
        val window: FolderSessionWindowEntry,
    ) : FolderTreeSessionChildRow {
        val sessionName: String get() = session.sessionName
    }
}

internal fun folderTreeSessionChildRows(
    sessions: List<FolderSessionEntry>,
): List<FolderTreeSessionChildRow> =
    sessions.flatMap { session ->
        val windows = sortedSessionWindows(session)
        if (windows.size <= 1) {
            listOf(FolderTreeSessionChildRow.Session(session))
        } else {
            listOf(FolderTreeSessionChildRow.Session(session, expandedIntoWindows = true)) +
                windows.map { window ->
                    FolderTreeSessionChildRow.Window(
                        session = session,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceSessionRow(
    folderPath: String,
    session: FolderSessionEntry,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    // True when this session is broken out into per-window child rows below it
    // (#675). The window rows already carry each window's status + identity, so
    // the parent drops its inline `w0 Claude · idle · w1 Claude` window summary
    // and its trailing agent badge to avoid repeating the agent type 3×.
    expandedIntoWindows: Boolean = false,
) {
    // Option A: the session row stays quiet — no inline kebab. Tap opens the
    // session; long-press opens the action menu (Open / Rename / Stop). The
    // trailing lane is then ONLY the agent badge, so badges right-align into one
    // clean column down the list.
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Compact paint via the density rung, but the interactive row keeps
            // the 48 dp a11y touch floor (#461 §6.1).
            .heightIn(min = PocketShellDensity.tapTargetMin)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = { menuExpanded = true },
            )
            .padding(horizontal = 6.dp, vertical = PocketShellDensity.rowPadV)
            .testTag(folderDetailRowTestTag(folderPath, session.sessionName)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(
            active = session.attached || session.agentKind.isAgent(),
            modifier = Modifier.testTag(folderSessionStatusDotTestTag(folderPath, session.sessionName)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        // No inline window-agent summary on the parent: a multi-window session is
        // already expanded into per-window child rows (which carry each window's
        // status + identity), and a single-window session has nothing to
        // summarise. The old `w0 Claude · idle · w1 Claude` subtitle was one of
        // three places that repeated the agent type, so it is gone (#675).
        Text(
            text = sessionDisplayTitle(session),
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // The parent's trailing agent badge is also redundant once the windows
        // are broken out (each window row carries the agent identity in its
        // title), so it is dropped on an expanded session (#675).
        if (!expandedIntoWindows) {
            // Issue #1237: the agent resting-state chip (idle / waiting / working)
            // leads the trailing lane so the "waiting for you" signal is the most
            // prominent. Absent (no chip, no spacer) when the state is unknown.
            session.agentState.chipLabel?.let {
                Spacer(modifier = Modifier.width(8.dp))
                AgentStateChip(
                    state = session.agentState,
                    modifier = Modifier.testTag(
                        folderSessionAgentStateChipTestTag(folderPath, session.sessionName),
                    ),
                )
            }
            // Issue #858: a non-default profile (e.g. z.ai Claude) gets a
            // distinct chip BEFORE the kind badge so the tree distinguishes it
            // from a default Claude. No chip for a default / legacy session.
            profileChipLabel(session.recordedProfile)?.let { chip ->
                Spacer(modifier = Modifier.width(8.dp))
                ProfileChip(
                    label = chip,
                    modifier = Modifier.testTag(
                        folderSessionProfileChipTestTag(folderPath, session.sessionName),
                    ),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            AgentTypeBadge(
                session = session,
                modifier = Modifier.testTag(folderSessionBadgeTestTag(folderPath, session.sessionName)),
            )
        }
        // Zero-size anchor for the long-press action menu at the row's trailing
        // edge — no visible affordance, so the row stays quiet until pressed.
        Box {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(PocketShellColors.SurfaceElev),
            ) {
                SubtleMenuItem(
                    "Open session",
                    folderSessionOpenMenuItemTestTag(folderPath, session.sessionName),
                ) { menuExpanded = false; onClick() }
                SubtleMenuItem(
                    "Rename session",
                    folderSessionRenameMenuItemTestTag(folderPath, session.sessionName),
                ) { menuExpanded = false; onRename() }
                SubtleMenuItem(
                    "Stop session",
                    folderSessionStopMenuItemTestTag(folderPath, session.sessionName),
                ) { menuExpanded = false; onStop() }
            }
        }
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
    Kebab(
        contentDescription = "Session actions $sessionName",
        triggerTestTag = triggerTestTag,
        triggerSize = PocketShellDensity.tapTargetMin,
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

/**
 * The shared "add a child here" affordance — a subtle accent `+` glyph with no
 * filled circular chrome, so the root and folder rows read as text + light
 * chrome rather than heavy buttons. `+` means the same thing at every level:
 * root `+` adds a project, folder `+` starts a new session. Full 48dp touch
 * target via the transparent [Box].
 */
@Composable
private fun SubtleAddButton(
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(PocketShellDensity.tapTargetMin)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+",
            color = PocketShellColors.Accent,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** One row in the session long-press action menu. */
@Composable
private fun SubtleMenuItem(label: String, testTag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
            )
        },
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
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
        // The window title (`w0 claude`) already names the agent and the status
        // dot conveys active/idle, so the per-row agent badge is dropped (#675):
        // on a multi-window agent session "Claude" was repeated 3× (parent inline
        // summary + parent badge + per-window badge). One indication per window —
        // the dot + the title — is enough.
        Text(
            text = sessionWindowEntryTitle(sessionName, window),
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Right-aligned compact agent-kind monogram on a session row — issues
 * #478/#1701. The full label remains its accessibility description.
 */
@Composable
private fun AgentTypeBadge(
    session: FolderSessionEntry,
    modifier: Modifier = Modifier,
) {
    AgentKindBadge(
        monogram = sessionBadgeMonogram(session),
        label = sessionBadgeLabel(session),
        isAgent = session.agentKind.isAgent(),
        modifier = modifier,
    )
}

private val ProfileChipMaxWidth = 70.dp

/**
 * Issue #858: a small chip next to the agent badge that names the NON-default
 * profile a session was launched with — so a z.ai-routed Claude reads
 * differently from a default Anthropic Claude in the tree. Rendered ONLY when
 * [FolderSessionEntry.recordedProfile] is non-null; a default / non-profiled /
 * legacy session shows no chip (the plain kind badge only).
 *
 * The chip uses a distinct neutral-accent fill (not the agent-accent purple of
 * the kind badge) so the two pills read as separate dimensions: "what agent"
 * (badge) and "which provider/profile" (this chip).
 */
@Composable
private fun ProfileChip(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .widthIn(max = ProfileChipMaxWidth)
            .background(
                PocketShellColors.SurfaceElev.copy(alpha = 0.9f),
                PocketShellShapes.small,
            )
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
            .padding(horizontal = PocketShellDensity.chipPadH, vertical = PocketShellDensity.chipPadV),
    ) {
        Text(
            text = label,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.labelMono,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val FolderListBottomContentPadding = 12.dp

// Test tags exposed for the unit / connected E2E suite.
const val FOLDER_LIST_SCREEN_TAG: String = "folder-list:screen"
const val FOLDER_LIST_CONTENT_TAG: String = "folder-list:content"
/** EPIC #679 req #4: the swipe-down pull-to-refresh host over the tree. */
const val FOLDER_LIST_PULL_TO_REFRESH_TAG: String = "folder-list:pull-to-refresh"
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
const val FOLDER_LIST_FLAT_EMPTY_TAG: String = "folder-list:flat:empty"
const val FOLDER_LIST_FLAT_HEADER_TAG: String = "folder-list:flat:header"
const val FOLDER_LIST_FLAT_HEADER_DOT_TAG: String = "folder-list:flat:header:dot"
const val FOLDER_LIST_FLAT_HEADER_COUNTS_TAG: String = "folder-list:flat:header:counts"
const val FOLDER_LIST_FLAT_ACTIVE_SECTION_TAG: String = "folder-list:flat:section:active"
const val FOLDER_LIST_FLAT_IDLE_SECTION_TAG: String = "folder-list:flat:section:idle"

// Issue #885: the passive host-CLI-version update prompt banner + its dismiss.
const val FOLDER_LIST_CLI_VERSION_BANNER_TAG: String = "folder-list:cli-version-banner"
const val FOLDER_LIST_CLI_VERSION_DISMISS_TAG: String = "folder-list:cli-version-dismiss"
// Issue #947: the one-tap Update button on that banner + its running spinner.
const val FOLDER_LIST_CLI_VERSION_UPDATE_TAG: String = "folder-list:cli-version-update"
const val FOLDER_LIST_CLI_VERSION_UPDATE_SPINNER_TAG: String = "folder-list:cli-version-update-spinner"
const val FOLDER_LIST_CLI_VERSION_UPDATE_ERROR_TAG: String = "folder-list:cli-version-update-error"

// Stable LazyColumn item keys for the flat-view section rows (#489). The host
// header band moved into the app bar (#522 item 1), so there is no in-list
// header item key any more.
private const val FLAT_ACTIVE_SECTION_KEY: String = "flat-section-active"
private const val FLAT_IDLE_SECTION_KEY: String = "flat-section-idle"
