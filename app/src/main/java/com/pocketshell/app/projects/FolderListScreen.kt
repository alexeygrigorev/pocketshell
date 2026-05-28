package com.pocketshell.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.SessionRow
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.model.Tag
import com.pocketshell.uikit.model.TagKind
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Per-host folder list — issue #171.
 *
 * Replaces the inline `HostTmuxSessionPickerSheet` as the default
 * destination after a user taps a host card. The screen renders a
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
 *  - Tap "Show all sessions on this host" → expands an inline flat
 *    list. Low-cost affordance for the "I know the session name, not
 *    the folder" path.
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
    onBrowseRepos: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FolderListViewModel = hiltViewModel(),
) {
    LaunchedEffect(hostId, hostname, port, username, keyPath) {
        viewModel.bind(
            hostId = hostId,
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
    var showAllFlatList by remember { mutableStateOf(false) }
    var pickerFolder by remember { mutableStateOf<PickerTarget?>(null) }

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
                onBrowseRepos = onBrowseRepos,
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
                    flatSessions = s.flatSessions,
                    showAllFlatList = showAllFlatList,
                    onToggleShowAll = { showAllFlatList = !showAllFlatList },
                    onSessionClick = { folderPath, sessionName ->
                        onOpenSession(
                            sessionName,
                            folderPath.takeUnless { it == FolderListViewModel.UNTRACKED_PATH },
                        )
                    },
                    onCreateInFolder = { row ->
                        pickerFolder = PickerTarget(path = row.path, label = row.label)
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
    }

    pickerFolder?.let { target ->
        SessionTypePickerSheet(
            folderPath = target.path,
            folderLabel = target.label,
            onDismiss = { pickerFolder = null },
            onCreate = { choice ->
                pickerFolder = null
                val newName = derivedSessionName(choice)
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
}

private data class PickerTarget(val path: String, val label: String)

/**
 * Derive a tmux session name from the user's picker choice. The name
 * is based on the folder's trailing path segment (`/home/foo/bar` →
 * `bar`) plus a short timestamp suffix so multiple sessions in the
 * same folder don't collide. Agent sessions also carry the agent name
 * so a glance at the flat list still gives "what kind of session is
 * this".
 */
internal fun derivedSessionName(choice: SessionTypeChoice): String {
    val tail = choice.startDirectory.trim().trimEnd('/').substringAfterLast('/').ifBlank { "shell" }
    val safe = tail.replace(Regex("[^A-Za-z0-9_-]"), "-").take(20)
    val suffix = (System.currentTimeMillis() % 1_000_000L).toString().padStart(6, '0')
    return when (choice.type) {
        SessionType.Shell -> "$safe-$suffix"
        SessionType.Agent -> "${choice.agent?.command.orEmpty()}-$safe-$suffix"
    }
}

@Composable
private fun FolderListAppBar(
    hostName: String,
    onBack: () -> Unit,
    onBrowseRepos: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(PocketShellColors.Background)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft)
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
        Column(modifier = Modifier.padding(start = 4.dp).weight(1f)) {
            Text(
                text = "Folders",
                color = PocketShellColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(FOLDER_LIST_TITLE_TAG),
            )
            Text(
                text = hostName,
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        TextButton(
            onClick = onBrowseRepos,
            modifier = Modifier.testTag(FOLDER_LIST_BROWSE_REPOS_TAG),
        ) {
            Text(
                text = "Repos",
                color = PocketShellColors.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LoadingPanel() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(FOLDER_LIST_LOADING_TAG),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = PocketShellColors.Accent)
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
    flatSessions: List<FolderSessionEntry>,
    showAllFlatList: Boolean,
    onToggleShowAll: () -> Unit,
    onSessionClick: (folderPath: String, sessionName: String) -> Unit,
    onCreateInFolder: (FolderRow) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (folders.isEmpty()) {
            item {
                EmptyState()
            }
        } else {
            items(folders) { folder ->
                FolderGroup(
                    folder = folder,
                    onSessionClick = onSessionClick,
                    onCreateInFolder = onCreateInFolder,
                )
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            TextButton(
                onClick = onToggleShowAll,
                modifier = Modifier.testTag(FOLDER_LIST_SHOW_ALL_TAG),
            ) {
                Text(
                    text = if (showAllFlatList) "Hide flat session list" else "Show all sessions on this host",
                    color = PocketShellColors.Accent,
                )
            }
        }
        if (showAllFlatList) {
            if (flatSessions.isEmpty()) {
                item {
                    Text(
                        text = "No tmux sessions found.",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .testTag(FOLDER_LIST_FLAT_EMPTY_TAG),
                    )
                }
            } else {
                items(flatSessions) { session ->
                    SessionRow(
                        modifier = Modifier.testTag(folderListFlatRowTestTag(session.sessionName)),
                        badge = session.sessionName.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                        name = session.sessionName,
                        host = "",
                        preview = sessionPreviewFor(session),
                        time = "",
                        tags = sessionTagsFor(session),
                        agentKind = session.agentKind,
                        onClick = { onSessionClick(FolderListViewModel.UNTRACKED_PATH, session.sessionName) },
                    )
                }
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
 * Folder header + inline session rows.
 *
 * The header reads "folder label" with a small subtitle showing the
 * folder path; below it sit one [SessionRow] per active session in
 * that folder, plus a "+ New session in <folder>" action. Inline
 * rendering is intentional — it keeps the session names visible at the
 * folder-list level so the user can drill straight into a session
 * without an extra tap.
 */
@Composable
private fun FolderGroup(
    folder: FolderRow,
    onSessionClick: (folderPath: String, sessionName: String) -> Unit,
    onCreateInFolder: (FolderRow) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(folderRowTestTag(folder.path)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FolderHeader(folder = folder, onCreateInFolder = { onCreateInFolder(folder) })
        if (folder.sessions.isEmpty()) {
            EmptyFolderHint(onCreate = { onCreateInFolder(folder) })
        } else {
            folder.sessions.forEach { session ->
                SessionRow(
                    modifier = Modifier.testTag(
                        folderDetailRowTestTag(folder.path, session.sessionName),
                    ),
                    badge = session.sessionName.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                    name = session.sessionName,
                    host = "",
                    preview = sessionPreviewFor(session),
                    time = "",
                    tags = sessionTagsFor(session),
                    agentKind = session.agentKind,
                    onClick = { onSessionClick(folder.path, session.sessionName) },
                )
            }
        }
    }
}

@Composable
private fun FolderHeader(folder: FolderRow, onCreateInFolder: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = folder.label,
                    color = PocketShellColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag(folderHeaderLabelTag(folder.path)),
                )
                if (folder.isWatched) {
                    Spacer(modifier = Modifier.size(8.dp))
                    WatchedPin()
                }
            }
            val subtitle = buildString {
                append(folder.path)
                if (folder.sessions.isNotEmpty()) {
                    val agents = folder.sessions.count { it.agentKind.isAgent() }
                    val shells = folder.sessions.size - agents
                    append(" · ")
                    if (agents > 0) append("$agents agent")
                    if (agents > 0 && shells > 0) append(" · ")
                    if (shells > 0) append("$shells shell")
                }
            }
            Text(
                text = subtitle,
                color = PocketShellColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
        TextButton(
            onClick = onCreateInFolder,
            modifier = Modifier.testTag(folderDetailCreateTestTag(folder.path)),
        ) {
            Text(
                text = "+ New",
                color = PocketShellColors.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
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

@Composable
private fun EmptyFolderHint(onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "No active sessions in this folder yet.",
            color = PocketShellColors.Text,
            fontSize = 13.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onCreate) {
            Text("+ New session", color = PocketShellColors.Accent)
        }
    }
}

private fun sessionPreviewFor(session: FolderSessionEntry): String = when (session.agentKind) {
    SessionAgentKind.Claude ->
        if (session.attached) "claude conversation active" else "claude workspace ready"
    SessionAgentKind.Codex ->
        if (session.attached) "codex conversation active" else "codex workspace ready"
    SessionAgentKind.OpenCode ->
        if (session.attached) "opencode conversation active" else "opencode workspace ready"
    SessionAgentKind.Probing -> "detecting agent..."
    SessionAgentKind.Exited -> "agent exited, shell still alive"
    SessionAgentKind.Shell ->
        if (session.attached) "attached tmux client" else "tmux session detached"
}

private fun sessionTagsFor(session: FolderSessionEntry): List<Tag> = buildList {
    when (session.agentKind) {
        SessionAgentKind.Claude -> add(Tag("Claude", TagKind.Agent))
        SessionAgentKind.Codex -> add(Tag("Codex", TagKind.Agent))
        SessionAgentKind.OpenCode -> add(Tag("OpenCode", TagKind.Agent))
        SessionAgentKind.Probing -> add(Tag("Probing", TagKind.Deploy))
        SessionAgentKind.Exited -> add(Tag("Exited", TagKind.Default))
        SessionAgentKind.Shell -> add(Tag("Shell", TagKind.Default))
    }
    add(
        if (session.attached) Tag("Attached", TagKind.Attached)
        else Tag("Detached", TagKind.Detached),
    )
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

// Test tags exposed for the unit / connected E2E suite.
const val FOLDER_LIST_SCREEN_TAG: String = "folder-list:screen"
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

fun folderRowTestTag(path: String): String = "folder-list:row:$path"
fun folderHeaderLabelTag(path: String): String = "folder-list:header:$path"
fun folderListFlatRowTestTag(sessionName: String): String = "folder-list:flat-row:$sessionName"
fun folderDetailRowTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName"
fun folderDetailCreateTestTag(folderPath: String): String =
    "folder-list:detail:$folderPath:create"
