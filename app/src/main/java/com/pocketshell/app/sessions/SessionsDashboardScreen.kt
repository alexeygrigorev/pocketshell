package com.pocketshell.app.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.SessionRow
import kotlin.math.max

/**
 * Sessions section of the dashboard — issue #46.
 *
 * Inlined into [com.pocketshell.app.hosts.HostListScreen] above the
 * "Hosts" section per the mockup at `docs/mockups/dashboard.html`. Renders
 * one [SessionRow] per [SessionSummary] from the view model, sorted by
 * recency (most-recent first — handled inside the view model, not here).
 *
 * The section composable itself is responsible for nothing more than
 * fan-out: it asks the view model for the current list, and for each
 * entry it asks the view model to resolve a navigation tuple at tap
 * time. Tap handling delegates to [onOpenTmuxSession], which the host
 * screen passes through to the navigator.
 *
 * If the view model's session list is empty the section renders nothing
 * — the host screen gates on `sessions.isNotEmpty()` for the
 * surrounding section label so the chrome doesn't appear above an
 * empty list. The section is also rendered inside a normal Compose
 * column (no `LazyColumn`) — the expected session count is small
 * (single digits per host, a handful of hosts) so the recycling cost of
 * a LazyColumn is not worth the layout complexity here.
 *
 * @param onOpenTmuxSession invoked when the user taps a session row.
 *   The host screen resolves it through the navigator. Default no-op
 *   so unit tests / previews can compose the section without setting
 *   up a navigator stub.
 */
@Composable
fun SessionsSection(
    modifier: Modifier = Modifier,
    viewModel: SessionsDashboardViewModel = hiltViewModel(),
    onOpenTmuxSession: (ActiveTmuxClients.Entry, sessionName: String) -> Unit = { _, _ -> },
) {
    val sessions by viewModel.sessions.collectAsState()
    if (sessions.isEmpty()) return

    val nowSec = System.currentTimeMillis() / 1000L
    var selectedSession by remember { mutableStateOf<SessionSummary?>(null) }
    var dialogMode by remember { mutableStateOf<DashboardDialogMode?>(null) }
    var dialogText by remember { mutableStateOf("") }

    fun openDialog(mode: DashboardDialogMode, initialText: String = "") {
        dialogMode = mode
        dialogText = initialText
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            onClick = {
                val entry = sessions.firstOrNull()?.let { viewModel.entryFor(it.hostId) }
                    ?: return@TextButton
                selectedSession = SessionSummary(
                    hostId = entry.hostId,
                    hostName = entry.hostName,
                    sessionName = "",
                    lastActivity = nowSec,
                    attached = false,
                )
                openDialog(DashboardDialogMode.CreateSession)
            },
        ) {
            Text("+ New session")
        }
        sessions.forEach { summary ->
            Box(modifier = Modifier.fillMaxWidth()) {
                SessionRow(
                    badge = summary.sessionName,
                    name = summary.sessionName,
                    host = summary.hostName,
                    // No preview text in v1 — the tmux protocol does not
                    // surface "last line written to the session" cheaply.
                    // The mockup's preview lines are aspirational and will
                    // arrive with the agent-aware conversation view in
                    // Phase 3 (#23 / #14).
                    preview = "",
                    time = formatRelativeTime(nowSec = nowSec, thenSec = summary.lastActivity),
                    tags = emptyList(),
                    onClick = {
                        // Resolve the navigation tuple via the view model's
                        // entry lookup — the row stays light, the view
                        // model owns the registry handle. If the host has
                        // unregistered between render and tap we drop the
                        // tap silently; the row will disappear on the next
                        // poll cycle.
                        val entry = viewModel.entryFor(summary.hostId) ?: return@SessionRow
                        onOpenTmuxSession(entry, summary.sessionName)
                    },
                    onLongClick = {
                        selectedSession = summary
                    },
                )
                DashboardSessionMenu(
                    expanded = selectedSession == summary && dialogMode == null,
                    onDismiss = { selectedSession = null },
                    onAttach = {
                        val entry = viewModel.entryFor(summary.hostId)
                        if (entry != null) {
                            selectedSession = null
                            onOpenTmuxSession(entry, summary.sessionName)
                        }
                    },
                    onRename = {
                        selectedSession = summary
                        openDialog(DashboardDialogMode.RenameSession, summary.sessionName)
                    },
                    onKill = {
                        selectedSession = summary
                        dialogMode = DashboardDialogMode.KillSession
                    },
                )
            }
        }
    }

    val currentDialog = dialogMode
    val currentSession = selectedSession
    if (currentDialog != null && currentSession != null) {
        DashboardLifecycleDialog(
            mode = currentDialog,
            sessionName = currentSession.sessionName,
            text = dialogText,
            onTextChange = { dialogText = it },
            onDismiss = {
                dialogMode = null
                selectedSession = null
            },
            onConfirm = {
                val entry = viewModel.entryFor(currentSession.hostId)
                if (entry != null) {
                    when (currentDialog) {
                        DashboardDialogMode.CreateSession -> {
                            val name = dialogText.trim()
                            viewModel.createSession(entry, name)
                            if (name.isNotEmpty()) {
                                onOpenTmuxSession(entry, name)
                            }
                        }
                        DashboardDialogMode.RenameSession -> {
                            viewModel.renameSession(
                                entry = entry,
                                oldName = currentSession.sessionName,
                                newName = dialogText,
                            )
                        }
                        DashboardDialogMode.KillSession -> {
                            viewModel.killSession(entry, currentSession.sessionName)
                        }
                    }
                }
                dialogMode = null
                selectedSession = null
            },
        )
    }
}

private enum class DashboardDialogMode {
    CreateSession,
    RenameSession,
    KillSession,
}

@Composable
private fun DashboardSessionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAttach: () -> Unit,
    onRename: () -> Unit,
    onKill: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(text = { Text("Attach") }, onClick = onAttach)
        DropdownMenuItem(text = { Text("Rename") }, onClick = onRename)
        DropdownMenuItem(text = { Text("Kill") }, onClick = onKill)
    }
}

@Composable
private fun DashboardLifecycleDialog(
    mode: DashboardDialogMode,
    sessionName: String,
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val isTextMode = mode != DashboardDialogMode.KillSession
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (mode) {
                    DashboardDialogMode.CreateSession -> "New session"
                    DashboardDialogMode.RenameSession -> "Rename session"
                    DashboardDialogMode.KillSession -> "Kill session"
                },
            )
        },
        text = {
            if (isTextMode) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    label = { Text("Session name") },
                )
            } else {
                Text("This will close $sessionName.")
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isTextMode || text.trim().isNotEmpty(),
            ) {
                Text(if (mode == DashboardDialogMode.KillSession) "Kill" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Format a tmux `session_activity` timestamp (seconds since epoch) as a
 * short relative duration string, matching the mockup's `2m / 8m / 14m
 * / 1h` cadence.
 *
 * Granularities:
 *  - `<1m` => `now`
 *  - `<60m` => `<n>m`
 *  - `<24h` => `<n>h`
 *  - else => `<n>d`
 *
 * Visible internal so the unit test can drive it with a fixed `nowSec`
 * — `System.currentTimeMillis()` would otherwise make the assertion
 * flaky.
 */
internal fun formatRelativeTime(nowSec: Long, thenSec: Long): String {
    val deltaSec = max(0L, nowSec - thenSec)
    return when {
        deltaSec < 60L -> "now"
        deltaSec < 3_600L -> "${deltaSec / 60L}m"
        deltaSec < 86_400L -> "${deltaSec / 3_600L}h"
        else -> "${deltaSec / 86_400L}d"
    }
}
