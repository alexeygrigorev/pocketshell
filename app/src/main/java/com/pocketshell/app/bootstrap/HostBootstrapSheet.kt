package com.pocketshell.app.bootstrap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Coarse visual / behavioural state of the bootstrap sheet.
 *
 * The sheet is driven from outside (the [HostListViewModel][com.pocketshell.app.hosts.HostListViewModel]
 * holds the state), so each state is a pure data value with no internal
 * coroutine. The sheet renders, the user taps, the ViewModel runs the
 * install and flips the state.
 *
 * - [Prompt] — first paint when tmux is missing. "Install" and "Skip"
 *   buttons.
 * - [Installing] — install command is in flight; both buttons disabled,
 *   spinner visible.
 * - [Success] — install succeeded. The sheet auto-dismisses after a
 *   short delay or on tap of "Continue".
 * - [Failed] — install failed. The sheet shows the stderr in a scrollable
 *   block + a "Close" button that dismisses to the host list (the user
 *   chooses to retry by reconnecting).
 */
public sealed interface HostBootstrapSheetState {
    public data object Prompt : HostBootstrapSheetState
    public data object Installing : HostBootstrapSheetState
    public data object Success : HostBootstrapSheetState
    public data class Failed(val message: String) : HostBootstrapSheetState
}

/**
 * Compose modal that surfaces on host connect when `tmux` is missing.
 *
 * Acceptance-criterion mapping (issue #49):
 *
 * - "Bootstrap sheet appears on connect when tmux is missing" — caller
 *   shows this sheet conditionally on
 *   [com.pocketshell.app.hosts.HostListViewModel.bootstrapState].
 * - "tap install runs the command and reports outcome" — the Install
 *   button calls [onInstall], which the ViewModel handles. Success vs
 *   failure flips the [state] passed back in.
 *
 * The host name is surfaced in the title so the user can tell which host
 * they're acting on (a common slip when juggling multiple connects).
 *
 * `onSkip` and `onDismiss` are split: `onSkip` is fired when the user
 * deliberately chooses to skip ("just open the session anyway"), while
 * `onDismiss` is invoked on swipe-down / scrim tap. Callers may bind both
 * to the same handler — the API leaves the distinction available in case
 * later analytics or routing needs to differ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun HostBootstrapSheet(
    state: HostBootstrapSheetState,
    hostName: String,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        modifier = modifier,
    ) {
        when (state) {
            HostBootstrapSheetState.Prompt -> PromptContent(
                hostName = hostName,
                onInstall = onInstall,
                onSkip = onSkip,
            )

            HostBootstrapSheetState.Installing -> InstallingContent(hostName = hostName)

            HostBootstrapSheetState.Success -> SuccessContent(
                hostName = hostName,
                onContinue = onDismiss,
            )

            is HostBootstrapSheetState.Failed -> FailedContent(
                hostName = hostName,
                message = state.message,
                onClose = onDismiss,
            )
        }
    }
}

@Composable
private fun PromptContent(
    hostName: String,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
) {
    SheetColumn {
        SheetTitle(text = "tmux is not installed")
        SheetSubtitle(text = "$hostName · install tmux now? PocketShell needs it for sessions, windows, and panes.")
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SecondaryButton(label = "Skip", onClick = onSkip, modifier = Modifier.weight(1f))
            PrimaryButton(label = "Install", onClick = onInstall, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun InstallingContent(hostName: String) {
    SheetColumn {
        SheetTitle(text = "Installing tmux…")
        SheetSubtitle(text = "$hostName · running the package manager. This can take a few seconds on a fresh host.")
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                color = PocketShellColors.Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "working…",
                color = PocketShellColors.TextSecondary,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun SuccessContent(hostName: String, onContinue: () -> Unit) {
    SheetColumn {
        SheetTitle(text = "tmux installed")
        SheetSubtitle(text = "$hostName · ready for sessions. PocketShell will skip this check for 24 hours.")
        Spacer(modifier = Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.weight(1f))
            PrimaryButton(label = "Continue", onClick = onContinue)
        }
    }
}

@Composable
private fun FailedContent(hostName: String, message: String, onClose: () -> Unit) {
    SheetColumn {
        SheetTitle(text = "Install failed")
        SheetSubtitle(text = "$hostName · the package manager reported an error.")
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 220.dp)
                .background(
                    color = PocketShellColors.SurfaceElev,
                    shape = RoundedCornerShape(8.dp),
                )
                .border(
                    width = 1.dp,
                    color = PocketShellColors.Border,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = message.ifBlank { "(no error message returned)" },
                color = PocketShellColors.Text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.weight(1f))
            // AlertDialog button styling, but reuse our SecondaryButton
            // so the visual matches the rest of the sheet.
            SecondaryButton(label = "Close", onClick = onClose)
        }
    }
}

@Composable
private fun SheetColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)),
    ) {
        content()
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        color = PocketShellColors.Text,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SheetSubtitle(text: String) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = text,
        color = PocketShellColors.TextSecondary,
        fontSize = 13.sp,
    )
}

@Composable
private fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .background(
                color = PocketShellColors.Accent,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PocketShellColors.OnAccent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.Border,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PocketShellColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Convenience surface used by some flows where a sheet is too heavy —
 * not consumed in #49's primary flow but exported because the bootstrap
 * may want to surface a generic "couldn't probe tmux" dialog later. The
 * import stays scoped to this file for now.
 */
@Suppress("unused")
@Composable
private fun BootstrapErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bootstrap error") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
        containerColor = PocketShellColors.Surface,
        textContentColor = PocketShellColors.Text,
        titleContentColor = PocketShellColors.Text,
    )
}
