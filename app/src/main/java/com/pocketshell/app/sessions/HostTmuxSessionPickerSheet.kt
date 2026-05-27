package com.pocketshell.app.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.pocketshell.app.projects.WatchedFoldersChipRow
import com.pocketshell.uikit.theme.PocketShellColors

// Issue #109: tags used by the connect-error connected test to drive
// the sheet without relying on free-form text.
internal const val HOST_PICKER_SHEET_TAG: String = "host:picker:sheet"
internal const val HOST_PICKER_CONNECTING_TAG: String = "host:picker:connecting"
internal const val HOST_PICKER_CANCEL_TAG: String = "host:picker:cancel"
internal const val HOST_PICKER_ERROR_TAG: String = "host:picker:error"
internal const val HOST_PICKER_RETRY_TAG: String = "host:picker:retry"
internal const val HOST_PICKER_RAW_SHELL_TAG: String = "host:picker:rawShell"
internal const val HOST_PICKER_SHOW_DETAILS_TAG: String = "host:picker:showDetails"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostTmuxSessionPickerSheet(
    state: HostTmuxSessionPickerState,
    onAttach: (
        HostTmuxSessionPickerRequest,
        sessionName: String,
        startDirectory: String?,
    ) -> Unit,
    onRawSsh: (HostTmuxSessionPickerRequest) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit = {},
    onCancel: () -> Unit = onDismiss,
) {
    if (state is HostTmuxSessionPickerState.Idle) return
    var showCreateDialog by remember { mutableStateOf(false) }
    // Issue #109: details disclosure starts collapsed; the user opts in
    // to read the underlying exception only when they want a bug-report
    // copy.
    var showDetails by remember(state) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(HOST_PICKER_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Issue #109: title now follows state. "Connection failed"
            // belongs to the error path; "Tmux sessions" stays on the
            // success/fallback paths where the body is a real session
            // list or a tmux-availability message.
            Text(
                text = when (state) {
                    is HostTmuxSessionPickerState.ConnectError -> "Connection failed"
                    is HostTmuxSessionPickerState.Loading -> "Connecting"
                    HostTmuxSessionPickerState.Idle,
                    is HostTmuxSessionPickerState.Ready,
                    is HostTmuxSessionPickerState.Fallback,
                    -> "Tmux sessions"
                },
                color = PocketShellColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            when (state) {
                is HostTmuxSessionPickerState.Loading -> {
                    ConnectingRow(
                        request = state.request,
                        onCancel = onCancel,
                    )
                }
                is HostTmuxSessionPickerState.Ready -> {
                    state.message?.let {
                        Text(text = it, color = PocketShellColors.TextSecondary, fontSize = 13.sp)
                    }
                    TextButton(onClick = { showCreateDialog = true }) {
                        Text("+ New session")
                    }
                    TextButton(onClick = { onRawSsh(state.request) }) {
                        Text("Continue with SSH")
                    }
                    state.rows.forEach { row ->
                        HostTmuxSessionRowView(row = row, onClick = { onAttach(state.request, row.name, null) })
                    }
                }
                is HostTmuxSessionPickerState.Fallback -> {
                    Text(
                        text = state.message,
                        color = PocketShellColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                    TextButton(onClick = { onRawSsh(state.request) }) {
                        Text("Continue with SSH")
                    }
                }
                is HostTmuxSessionPickerState.ConnectError -> {
                    ConnectErrorBody(
                        state = state,
                        showDetails = showDetails,
                        onToggleDetails = { showDetails = !showDetails },
                        onRetry = onRetry,
                        onRawSsh = { onRawSsh(state.request) },
                    )
                }
                HostTmuxSessionPickerState.Idle -> Unit
            }
            Spacer(modifier = Modifier.height(18.dp))
        }
    }

    val request = when (state) {
        is HostTmuxSessionPickerState.Ready -> state.request
        is HostTmuxSessionPickerState.Fallback -> state.request
        else -> null
    }
    if (showCreateDialog && request != null) {
        CreateTmuxSessionDialog(
            // Issue #206 + #204: thread the host id so the create
            // dialog can render the watched-folders chip row.
            hostId = request.host.id,
            onDismiss = { showCreateDialog = false },
            onCreate = { creation ->
                showCreateDialog = false
                onAttach(request, creation.sessionName, creation.startDirectory)
            },
        )
    }
}

@Composable
private fun ConnectingRow(
    request: HostTmuxSessionPickerRequest,
    onCancel: () -> Unit,
) {
    // Issue #109: the SSH connect path can sit silent for up to 30s
    // (default `SshConnection.DEFAULT_TIMEOUT_MS`). Render a spinner +
    // host coordinates so the user knows the app is doing something,
    // and a Cancel button so they aren't stuck waiting for the timeout.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HOST_PICKER_CONNECTING_TAG),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = PocketShellColors.Accent,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = "Connecting to ${request.host.username}@${request.host.hostname}:${request.host.port}…",
                color = PocketShellColors.Text,
                fontSize = 14.sp,
            )
        }
        TextButton(
            onClick = onCancel,
            modifier = Modifier.testTag(HOST_PICKER_CANCEL_TAG),
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun ConnectErrorBody(
    state: HostTmuxSessionPickerState.ConnectError,
    showDetails: Boolean,
    onToggleDetails: () -> Unit,
    onRetry: () -> Unit,
    onRawSsh: () -> Unit,
) {
    val host = state.request.host
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HOST_PICKER_ERROR_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Body line — `Couldn't reach <user>@<host>:<port>. <reason>.`
        // Acceptance criterion: user-facing summary, not a stack trace.
        Text(
            text = formatHostConnectErrorBody(
                user = host.username,
                host = host.hostname,
                port = host.port,
                summary = state.summary,
            ),
            color = PocketShellColors.Text,
            fontSize = 14.sp,
        )

        // Details disclosure — collapsed by default; expanded text is
        // the joined cause chain from `summarizeConnectError`. Kept in
        // the sheet so the user can copy/paste for a bug report without
        // chasing logcat.
        Text(
            text = if (showDetails) "Hide details" else "Show details",
            color = PocketShellColors.Accent,
            fontSize = 13.sp,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onToggleDetails)
                .padding(vertical = 4.dp)
                .testTag(HOST_PICKER_SHOW_DETAILS_TAG),
        )
        if (showDetails) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = state.summary.details,
                    color = PocketShellColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }

        // Primary action — Retry. Secondary — Open raw shell (skip
        // tmux). The "Open raw shell (skip tmux)" copy replaces the
        // earlier "Continue with SSH" wording for the error path only;
        // the success/Fallback paths keep "Continue with SSH" so this
        // change doesn't move the cheese for normal tmux flow.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier.testTag(HOST_PICKER_RETRY_TAG),
            ) {
                Text("Retry")
            }
            TextButton(
                onClick = onRawSsh,
                modifier = Modifier.testTag(HOST_PICKER_RAW_SHELL_TAG),
            ) {
                Text("Open raw shell (skip tmux)")
            }
        }
    }
}

@Composable
private fun HostTmuxSessionRowView(row: HostTmuxSessionRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                color = PocketShellColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (row.attached) "attached" else "available",
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        Text(text = "Attach", color = PocketShellColors.Accent, fontSize = 13.sp)
    }
}

@Composable
private fun CreateTmuxSessionDialog(
    hostId: Long? = null,
    onDismiss: () -> Unit,
    onCreate: (TmuxSessionCreation) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var startDirectory by remember { mutableStateOf(DEFAULT_TMUX_START_DIRECTORY) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Session name") },
                )
                // Issue #206 + #204: watched-folders chip row.
                WatchedFoldersChipRow(
                    hostId = hostId,
                    onChipTap = { path -> startDirectory = path },
                )
                OutlinedTextField(
                    value = startDirectory,
                    onValueChange = { startDirectory = it },
                    singleLine = true,
                    label = { Text("Start folder") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        resolveTmuxSessionCreation(
                            rawName = text,
                            rawStartDirectory = startDirectory,
                        ),
                    )
                },
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
