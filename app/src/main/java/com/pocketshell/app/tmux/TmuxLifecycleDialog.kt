package com.pocketshell.app.tmux

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.FormDialog

internal sealed interface TmuxDialogMode {
    data object RenameSession : TmuxDialogMode
    data object StopSession : TmuxDialogMode
}

@Composable
internal fun TmuxLifecycleDialog(
    mode: TmuxDialogMode,
    sessionName: String,
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    // Issue #898: the create-session branch is gone — "+ New session" now opens
    // the rich SessionTypePickerSheet (hard-cut D22). This dialog only handles
    // the remaining text/confirm lifecycle actions: Rename and Stop.
    val modifier = Modifier
        .navigationBarsPadding()
        .imePadding()
    when (mode) {
        TmuxDialogMode.RenameSession -> {
            FormDialog(
                title = "Rename session",
                confirmLabel = "Save",
                onConfirm = onConfirm,
                onDismiss = onDismiss,
                modifier = modifier,
                confirmEnabled = text.trim().isNotEmpty(),
                confirmTestTag = TMUX_LIFECYCLE_DIALOG_CONFIRM_TAG,
                dismissTestTag = TMUX_LIFECYCLE_DIALOG_CANCEL_TAG,
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    label = { Text("Session name") },
                )
            }
        }
        TmuxDialogMode.StopSession -> {
            ConfirmDialog(
                title = "Stop session",
                message = "This will close $sessionName.",
                confirmLabel = "Stop",
                onConfirm = onConfirm,
                onDismiss = onDismiss,
                modifier = modifier,
                destructive = true,
                confirmTestTag = TMUX_LIFECYCLE_DIALOG_CONFIRM_TAG,
                dismissTestTag = TMUX_LIFECYCLE_DIALOG_CANCEL_TAG,
            )
        }
    }
}
