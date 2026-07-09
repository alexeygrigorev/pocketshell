package com.pocketshell.app.tmux

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.app.projects.ClaudeProfile
import com.pocketshell.app.projects.CodexProfile
import com.pocketshell.app.projects.FolderListViewModel
import com.pocketshell.app.projects.SessionKindPickerSheet
import com.pocketshell.app.projects.SessionTypeChoice
import com.pocketshell.app.projects.SessionTypePickerSheet
import com.pocketshell.app.sessions.DEFAULT_TMUX_START_DIRECTORY
import com.pocketshell.core.terminal.selection.LocalhostUrl
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

@Composable
internal fun TmuxSessionAuxiliaryModals(
    showNewSessionSheet: Boolean,
    currentPaneCwd: String?,
    suggestStartDirectories: (suspend (String) -> List<String>)?,
    claudeProfiles: List<ClaudeProfile>,
    codexProfiles: List<CodexProfile>,
    deriveDefaultName: (startDirectory: String) -> String,
    onDismissNewSessionSheet: () -> Unit,
    onCreateNewSession: (SessionTypeChoice) -> Unit,
    showKindPicker: Boolean,
    sessionName: String,
    currentSessionRecordedKind: SessionAgentKind?,
    currentSessionRecordedProfile: String?,
    onDismissKindPicker: () -> Unit,
    onPickKind: (SessionAgentKind) -> Unit,
    showOpenFileDialog: Boolean,
    openFilePath: String,
    onOpenFilePathChange: (String) -> Unit,
    onDismissOpenFileDialog: () -> Unit,
    onOpenFileConfirmed: (path: String, paneCwd: String?) -> Unit,
    pendingLocalhostForward: LocalhostUrl?,
    localhostTargetHost: String,
    onDismissLocalhostForward: () -> Unit,
    onConfirmLocalhostForward: (LocalhostUrl) -> Unit,
) {
    val paneCwd = currentPaneCwd?.takeIf { it.isNotBlank() }
    val newSessionFolderPath = paneCwd ?: DEFAULT_TMUX_START_DIRECTORY

    // Issue #898: the in-session "+ New session" rich sheet. This remains the
    // same shared picker used by the host/session-list screen; this wrapper
    // only hosts the modal, while the route owns create/navigation decisions.
    if (showNewSessionSheet) {
        SessionTypePickerSheet(
            folderPath = newSessionFolderPath,
            folderLabel = FolderListViewModel.defaultLabelForPath(newSessionFolderPath),
            onDismiss = onDismissNewSessionSheet,
            suggestStartDirectories = suggestStartDirectories,
            claudeProfiles = claudeProfiles,
            codexProfiles = codexProfiles,
            deriveDefaultName = deriveDefaultName,
            onCreate = onCreateNewSession,
        )
    }

    // Epic #821 Slice 1: the session-kind classify / change picker.
    if (showKindPicker) {
        SessionKindPickerSheet(
            sessionName = sessionName,
            onDismiss = onDismissKindPicker,
            onPick = onPickKind,
            isUnknown = currentSessionRecordedKind == null,
            currentKind = currentSessionRecordedKind,
            suggestedKind = null,
            currentProfile = currentSessionRecordedProfile,
        )
    }

    // Issue #497: in-app file viewer path-entry dialog. The active pane cwd is
    // threaded through so relative paths resolve server-side in the viewer.
    if (showOpenFileDialog) {
        AlertDialog(
            onDismissRequest = onDismissOpenFileDialog,
            title = { Text("Open file") },
            text = {
                Column {
                    Text(
                        text = if (paneCwd != null) {
                            "Enter a path. Relative paths resolve against $paneCwd."
                        } else {
                            "Enter an absolute path, or a path relative to your home directory."
                        },
                        color = PocketShellColors.TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    OutlinedTextField(
                        value = openFilePath,
                        onValueChange = onOpenFilePathChange,
                        singleLine = true,
                        placeholder = { Text("e.g. out/report.png") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = PocketShellSpacing.sm)
                            .testTag(TMUX_OPEN_FILE_DIALOG_FIELD_TAG),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = openFilePath.isNotBlank(),
                    onClick = {
                        val path = openFilePath.trim()
                        onDismissOpenFileDialog()
                        if (path.isNotEmpty()) onOpenFileConfirmed(path, paneCwd)
                    },
                    modifier = Modifier.testTag(TMUX_OPEN_FILE_DIALOG_CONFIRM_TAG),
                ) {
                    Text("Open")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissOpenFileDialog) { Text("Cancel") }
            },
        )
    }

    // Issue #488: confirm dialog for a tapped server-local URL whose remote
    // port is not yet forwarded.
    pendingLocalhostForward?.let { pending ->
        AlertDialog(
            onDismissRequest = onDismissLocalhostForward,
            title = { Text("Forward port ${pending.remotePort}?") },
            text = {
                Text(
                    "${pending.remotePort} is a port on $localhostTargetHost, " +
                        "not reachable directly from this phone. Forward it " +
                        "to open it here.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onConfirmLocalhostForward(pending) }) {
                    Text("Forward")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissLocalhostForward) {
                    Text("Cancel")
                }
            },
        )
    }
}
