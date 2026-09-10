package com.pocketshell.next.terminal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.nav.Destination
import com.pocketshell.next.tree.STOP_SESSION_ITEM_LABEL
import com.pocketshell.next.tree.STOP_SESSION_ITEM_TAG
import com.pocketshell.next.workspaces.canonicalRemotePath
import com.pocketshell.next.workspaces.sessionDisplayNames
import com.pocketshell.next.workspaces.sessionKindLabel
import com.pocketshell.next.workspaces.sessionStatusLabel
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import androidx.compose.ui.unit.dp
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val SESSION_SWITCHER_SHEET_TAG: String = "session-switcher-sheet"
const val SESSION_SWITCHER_LOADING_TAG: String = "session-switcher-loading"
const val SESSION_SWITCHER_EMPTY_TAG: String = "session-switcher-empty"
const val SESSION_SWITCHER_ERROR_TAG: String = "session-switcher-error"
const val SESSION_SWITCHER_NEW_TAG: String = "session-switcher-new-session"

fun sessionSwitcherRowTag(name: String): String = "session-switcher-row-$name"

/** A neighbouring workspace's row in the switcher sheet (#2635 N2). */
fun switcherWorkspaceRowTag(path: String): String = "session-switcher-workspace-$path"

const val SESSION_SWITCHER_OTHER_WORKSPACES_LABEL: String = "Other workspaces"

/** The "Other workspaces" heading, so a test can scroll the sheet to it. */
const val SESSION_SWITCHER_OTHER_WORKSPACES_LABEL_TAG: String =
    "session-switcher-other-workspaces"

data class SessionSwitcherUiState(
    val loading: Boolean = false,
    val sessions: List<SessionRow> = emptyList(),
    val failure: String? = null,
    val hostLabel: String = "",
    val workspacePath: String? = null,
    /**
     * Every OTHER workspace on this host that has at least one session
     * (#2635 N2).
     *
     * Switching to a session in a different project used to cost three taps
     * from a terminal — back to the workspace list, the workspace, the session
     * — against the desktop's one, where the folder panel is always on screen.
     * This is the phone form of that panel: the sheet the user is already in
     * for sibling sessions also lists the neighbours, so any session on the
     * host is two taps away.
     *
     * Derived from the SAME listing the sibling sessions come from, so it costs
     * no extra round trip: the ViewModel already fetches the whole host and
     * then filters it down to the current workspace.
     */
    val otherWorkspaces: List<SwitcherWorkspace> = emptyList(),
)

/** One neighbouring workspace in the switcher sheet (#2635 N2). */
data class SwitcherWorkspace(
    val path: String,
    val label: String,
    val sessionCount: Int,
    val attached: Boolean,
    /** The session a tap opens — resolved the same way a workspace row does. */
    val entrySessionName: String,
)

/** Reads the same host-owned session list as the workspace screen for the terminal switcher. */
@HiltViewModel
class SessionSwitcherViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: ConnectionsRegistry,
    private val clients: HostCliClientFactory,
    private val hostDao: com.pocketshell.core.storage.dao.HostDao,
    private val lastSessionStore: LastSessionStore,
) : ViewModel() {

    private val hostId: Long = requireNotNull(savedStateHandle.get<Long>(Destination.ARG_HOST_ID))
    private val workspacePath: String? = savedStateHandle
        .get<String>(Destination.ARG_WORKSPACE_PATH)
        ?.let(::canonicalRemotePath)

    private val _state = MutableStateFlow(SessionSwitcherUiState())
    val state: StateFlow<SessionSwitcherUiState> = _state.asStateFlow()
    private var job: Job? = null

    /**
     * The neighbouring workspaces, from the listing already in hand (#2635 N2).
     *
     * The current workspace is excluded — it is the tab strip. A workspace with
     * no sessions is excluded too: this sheet switches between running
     * terminals, and a folder with nothing in it has nothing to switch TO (the
     * workspace list is still one Back away for that).
     */
    private fun otherWorkspaces(all: List<SessionRow>): List<SwitcherWorkspace> = all
        .mapNotNull { session -> session.workspace?.let { canonicalRemotePath(it) to session } }
        .filter { (path, _) -> path != null && path != workspacePath }
        .groupBy({ it.first!! }, { it.second })
        .map { (path, sessions) ->
            SwitcherWorkspace(
                path = path,
                label = path.trimEnd('/').substringAfterLast('/').ifBlank { path },
                sessionCount = sessions.size,
                attached = sessions.any { it.attached },
                entrySessionName = resolveWorkspaceEntrySession(
                    rememberedName = lastSessionStore.getForWorkspace(hostId, path),
                    sessions = sessions,
                )?.name ?: sessions.first().name,
            )
        }
        .sortedBy { it.label.lowercase() }

    fun refresh() {
        if (job?.isActive == true) return
        _state.update { it.copy(loading = true, failure = null) }
        job = viewModelScope.launch {
            val hostLabel = hostDao.getById(hostId)?.let { host ->
                host.name.ifBlank { host.hostname }
            }.orEmpty()
            when (val result = registry.getOrConnect(hostId)) {
                is ConnectResult.Connected -> clients.create(result.connection).listSessions().fold(
                    onSuccess = { listing ->
                        val visible = workspacePath?.let { path ->
                            listing.sessions.filter { canonicalRemotePath(it.workspace) == path }
                        } ?: listing.sessions
                        _state.value = SessionSwitcherUiState(
                            sessions = visible,
                            hostLabel = hostLabel,
                            workspacePath = workspacePath,
                            otherWorkspaces = otherWorkspaces(listing.sessions),
                            failure = listing.errors.takeIf { it.isNotEmpty() }
                                ?.joinToString("; ") { it.message },
                        )
                    },
                    onFailure = { error ->
                        _state.value = SessionSwitcherUiState(
                            hostLabel = hostLabel,
                            workspacePath = workspacePath,
                            failure = error.message ?: "Could not list sessions.",
                        )
                    },
                )
                is ConnectResult.NeedsTrust -> _state.value = SessionSwitcherUiState(
                    hostLabel = hostLabel,
                    workspacePath = workspacePath,
                    failure = "Confirm this host's key from the host list first.",
                )
                is ConnectResult.Failed -> _state.value = SessionSwitcherUiState(
                    hostLabel = hostLabel,
                    workspacePath = workspacePath,
                    failure = result.message,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSwitcherSheet(
    currentSessionName: String,
    state: SessionSwitcherUiState,
    onNewSession: () -> Unit,
    onOpenSession: (SessionRow) -> Unit,
    onDismiss: () -> Unit,
    onOpenWorkspace: (SwitcherWorkspace) -> Unit = {},
) {
    val displayNames = sessionDisplayNames(state.sessions)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        shape = PocketShellShapes.large,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(bottom = PocketShellSpacing.lg)
                .testTag(SESSION_SWITCHER_SHEET_TAG),
        ) {
            item {
                SheetHeader(
                    title = "Sessions",
                    subtitle = "Switch terminals in this workspace.",
                    onClose = onDismiss,
                )
            }
            item {
                ListRow(
                    title = "New session",
                    subtitle = "Start another terminal here.",
                    leading = {
                        Icon(
                            imageVector = PocketShellIcons.Plus,
                            contentDescription = null,
                            tint = PocketShellColors.TextSecondary,
                        )
                    },
                    onClick = onNewSession,
                    modifier = Modifier.testTag(SESSION_SWITCHER_NEW_TAG),
                )
            }
            when {
                state.loading -> item {
                    EmptyState(
                        title = "Loading sessions…",
                        modifier = Modifier
                            .padding(vertical = PocketShellSpacing.lg)
                            .testTag(SESSION_SWITCHER_LOADING_TAG),
                    )
                }
                state.failure != null && state.sessions.isEmpty() -> item {
                    EmptyState(
                        title = "Sessions unavailable",
                        description = state.failure,
                        modifier = Modifier.testTag(SESSION_SWITCHER_ERROR_TAG),
                    )
                }
                state.sessions.isEmpty() -> item {
                    EmptyState(
                        title = "No other sessions",
                        description = "Start another session from this workspace.",
                        modifier = Modifier.testTag(SESSION_SWITCHER_EMPTY_TAG),
                    )
                }
                else -> items(state.sessions, key = { "${it.workspace}:${it.name}" }) { session ->
                    ListRow(
                        title = displayNames[session.name] ?: "Terminal",
                        subtitle = sessionSwitcherSubtitle(session),
                        leading = {
                            Icon(
                                imageVector = PocketShellIcons.Terminal,
                                contentDescription = null,
                                tint = PocketShellColors.TextSecondary,
                            )
                        },
                        trailing = if (session.name == currentSessionName) {
                            {
                                Text(
                                    text = "Current",
                                    color = PocketShellColors.TextSecondary,
                                    style = com.pocketshell.uikit.theme.PocketShellType.metadata,
                                )
                            }
                        } else {
                            null
                        },
                        onClick = { onOpenSession(session) },
                        modifier = Modifier.testTag(sessionSwitcherRowTag(session.name)),
                    )
                }
            }

            // #2635 N2: the neighbours. A terminal in another project used to
            // be three taps away (back, workspace, session) against the
            // desktop's one, where the folder panel never leaves the screen.
            // Listing them in the sheet the user already opened makes any
            // session on the host two taps.
            if (state.otherWorkspaces.isNotEmpty()) {
                item {
                    SectionHeader(
                        label = SESSION_SWITCHER_OTHER_WORKSPACES_LABEL,
                        count = state.otherWorkspaces.size,
                        modifier = Modifier.testTag(SESSION_SWITCHER_OTHER_WORKSPACES_LABEL_TAG),
                    )
                }
                items(state.otherWorkspaces, key = { "workspace:${it.path}" }) { workspace ->
                    ListRow(
                        title = workspace.label,
                        leading = {
                            StatusDot(
                                status = if (workspace.attached) {
                                    ConnectionStatus.Connected
                                } else {
                                    ConnectionStatus.Idle
                                },
                            )
                        },
                        trailing = {
                            Text(
                                text = workspace.sessionCount.toString(),
                                color = PocketShellColors.TextMuted,
                                style = com.pocketshell.uikit.theme.PocketShellType.labelMono,
                            )
                        },
                        onClick = { onOpenWorkspace(workspace) },
                        modifier = Modifier.testTag(switcherWorkspaceRowTag(workspace.path)),
                    )
                }
            }
        }
    }
}

private fun sessionSwitcherSubtitle(session: SessionRow): String = listOfNotNull(
    sessionProgramLabel(session),
    sessionStatusLabel(session),
).joinToString(" · ")

private fun sessionProgramLabel(session: SessionRow): String = when {
    session.agent.equals("claude", ignoreCase = true) -> "Claude Code"
    session.agent.equals("codex", ignoreCase = true) -> "Codex"
    session.agent.equals("opencode", ignoreCase = true) -> "OpenCode"
    session.agent.equals("grok", ignoreCase = true) -> "Grok"
    session.engine.equals("shell", ignoreCase = true) -> "Shell"
    !session.profile.isNullOrBlank() -> session.profile.orEmpty()
    else -> sessionKindLabel(session)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalActionsSheet(
    onSessions: () -> Unit,
    onBrowseFiles: () -> Unit,
    onOpenPorts: () -> Unit,
    onOpenUsage: () -> Unit,
    onCopySelection: () -> Unit,
    onEndSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        shape = PocketShellShapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(bottom = PocketShellSpacing.lg)
                .testTag(TERMINAL_ACTIONS_SHEET_TAG),
        ) {
            SheetHeader(title = "Terminal", onClose = onDismiss)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
            ) {
                item { TerminalActionRow("Sessions in workspace", onSessions, TERMINAL_ACTIONS_SESSIONS_TAG) }
                item { TerminalActionRow("Browse workspace files", onBrowseFiles, TERMINAL_ACTIONS_FILES_TAG) }
                // #2635 N1: tunnels were on the deleted workspace page, which
                // made them unreachable from a terminal at all — the audit
                // measured "not reachable" against the desktop's one tap.
                item { TerminalActionRow("Services & tunnels", onOpenPorts, TERMINAL_ACTIONS_PORTS_TAG) }
                item { TerminalActionRow("Copy selection", onCopySelection, TERMINAL_ACTIONS_COPY_TAG) }
                // #2635 (maintainer, 2026-09-10: "remove this button"): there
                // is no "Detach and keep running" row. Its callback was
                // literally `onBack()` — the same thing the header's back
                // arrow and the system back gesture already do — and aplexer
                // sessions are daemon-backed, so leaving the screen never
                // stopped anything in the first place. It was an affordance
                // describing the default behaviour as though it were an
                // action, which invites the reading that NOT tapping it might
                // kill the session. Ending one is `End session…`, below.
                item { TerminalActionRow(STOP_SESSION_ITEM_LABEL, onEndSession, STOP_SESSION_ITEM_TAG) }
            }
        }
    }
}

@Composable
private fun TerminalActionRow(title: String, onClick: () -> Unit, testTag: String) {
    ListRow(title = title, onClick = onClick, modifier = Modifier.testTag(testTag))
}

const val TERMINAL_ACTIONS_SHEET_TAG: String = "terminal-actions-sheet"
const val TERMINAL_ACTIONS_SESSIONS_TAG: String = "terminal-actions-sessions"
const val TERMINAL_ACTIONS_FILES_TAG: String = "terminal-actions-files"
const val TERMINAL_ACTIONS_USAGE_TAG: String = "terminal-actions-usage"
const val TERMINAL_ACTIONS_PORTS_TAG: String = "terminal-actions-ports"
const val TERMINAL_ACTIONS_COPY_TAG: String = "terminal-actions-copy"
