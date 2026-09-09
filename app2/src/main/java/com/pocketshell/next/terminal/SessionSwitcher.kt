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
import com.pocketshell.uikit.components.SheetHeader
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

data class SessionSwitcherUiState(
    val loading: Boolean = false,
    val sessions: List<SessionRow> = emptyList(),
    val failure: String? = null,
    val hostLabel: String = "",
    val workspacePath: String? = null,
)

/** Reads the same host-owned session list as the workspace screen for the terminal switcher. */
@HiltViewModel
class SessionSwitcherViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: ConnectionsRegistry,
    private val clients: HostCliClientFactory,
    private val hostDao: com.pocketshell.core.storage.dao.HostDao,
) : ViewModel() {

    private val hostId: Long = requireNotNull(savedStateHandle.get<Long>(Destination.ARG_HOST_ID))
    private val workspacePath: String? = savedStateHandle
        .get<String>(Destination.ARG_WORKSPACE_PATH)
        ?.let(::canonicalRemotePath)

    private val _state = MutableStateFlow(SessionSwitcherUiState())
    val state: StateFlow<SessionSwitcherUiState> = _state.asStateFlow()
    private var job: Job? = null

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
    onOpenUsage: () -> Unit,
    onCopySelection: () -> Unit,
    onDetach: () -> Unit,
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
                item { TerminalActionRow("Copy selection", onCopySelection, TERMINAL_ACTIONS_COPY_TAG) }
                item { TerminalActionRow("Detach and keep running", onDetach, TERMINAL_ACTIONS_DETACH_TAG) }
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
const val TERMINAL_ACTIONS_COPY_TAG: String = "terminal-actions-copy"
const val TERMINAL_ACTIONS_DETACH_TAG: String = "terminal-actions-detach"
