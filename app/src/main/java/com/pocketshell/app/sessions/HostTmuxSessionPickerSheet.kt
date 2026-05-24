package com.pocketshell.app.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.theme.PocketShellColors

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
) {
    if (state is HostTmuxSessionPickerState.Idle) return
    var showCreateDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Tmux sessions",
                color = PocketShellColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            when (state) {
                is HostTmuxSessionPickerState.Loading -> {
                    Text(
                        text = "Loading sessions from ${state.hostName}...",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 13.sp,
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
            onDismiss = { showCreateDialog = false },
            onCreate = { creation ->
                showCreateDialog = false
                onAttach(request, creation.sessionName, creation.startDirectory)
            },
        )
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
