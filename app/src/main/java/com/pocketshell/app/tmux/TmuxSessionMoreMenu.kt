package com.pocketshell.app.tmux

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.app.portfwd.SessionForwardingIndicatorState
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.theme.PocketShellColors

internal fun sessionForwardingMenuStatusLabel(
    state: SessionForwardingIndicatorState,
): String =
    when {
        !state.visible -> ""
        state.restoring -> "Restoring"
        state.tunnelCount == 1 -> "1 active port"
        state.tunnelCount > 1 -> "${state.tunnelCount} active ports"
        else -> "Active"
    }

@Composable
internal fun TmuxMoreMenu(
    expanded: Boolean,
    forwardingState: SessionForwardingIndicatorState = SessionForwardingIndicatorState(),
    onDismiss: () -> Unit,
    onCreateSession: () -> Unit,
    onRenameSession: () -> Unit,
    onKillSession: () -> Unit,
    // Epic #821 Slice 1: classify / re-classify this session's agent kind.
    // The label adapts to whether the session has a recorded kind: an
    // unclassified (foreign) session reads "What is this session?" and a
    // classified one "Change kind". Defaulted so existing direct callers /
    // tests of TmuxMoreMenu stay source-compatible.
    onChangeKind: () -> Unit = {},
    changeKindIsUnknown: Boolean = false,
    onSwitchSession: () -> Unit,
    onOpenJobs: () -> Unit,
    onOpenUsage: () -> Unit,
    // Issue #592: direct global Settings shortcut from live session chrome.
    // Defaulted so existing direct callers / tests stay source-compatible.
    onOpenSettings: () -> Unit = {},
    // Issue #497: "Open file…" kebab item — opens the in-app file viewer
    // path-entry dialog. Defaulted so existing direct callers / tests of
    // TmuxMoreMenu stay source-compatible.
    onOpenFile: () -> Unit = {},
    // Issue #528: "Browse files…" kebab item — opens the browsable file
    // explorer. Defaulted so existing direct callers / tests stay
    // source-compatible.
    onBrowseFiles: () -> Unit = {},
    // Issue #445: "Port forwarding" kebab item — opens the per-host
    // port-forward panel. Defaulted so existing direct callers / tests
    // of TmuxMoreMenu stay source-compatible.
    onOpenPortForwarding: () -> Unit = {},
    // Issue #235: user-driven detach. Tears the `-CC` control client
    // down (server-clean — uses the same `detach-client` round-trip
    // [TmuxSessionViewModel.detachAndExit] runs internally) and pops
    // back to the sessions dashboard. The session itself stays alive
    // on the remote; reattach via the normal sessions-list path.
    onDetach: () -> Unit = {},
    // Issue #892: "Redraw" — force a full-viewport reseed of the active pane over the
    // warm session (no reconnect/detach/new lease) to recover from a black/partial
    // terminal. Defaulted so existing direct callers / tests of TmuxMoreMenu stay
    // source-compatible.
    onRedraw: () -> Unit = {},
    // Issue #993: "Reconnect" — force an immediate reconnect of the CURRENT session in
    // place (the manual escape hatch when auto-reconnect doesn't fire). Defaulted so
    // existing direct callers / tests of TmuxMoreMenu stay source-compatible.
    onReconnect: () -> Unit = {},
    // Issue #993: gate the "Reconnect" item — disabled while there is no target to
    // reconnect to OR a connect/reconnect is already in flight, so a tap is never a
    // silent no-op nor a redundant re-dial. Defaulted true so existing callers/tests
    // stay source-compatible.
    reconnectEnabled: Boolean = true,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        // Issue #857: the kebab was a flat, ungrouped list that mixed
        // current-session actions, an identify/classify item, file actions,
        // connection, and app settings. It is now grouped into logical sections
        // (header + divider per section). Every item keeps its exact onClick and
        // test tag — this is ordering/grouping only, no behaviour change.
        // (Issue #782 already removed the tmux-window group; the only session-
        // scoped ops left are session lifecycle + host shortcuts.)
        //
        // --- This session: act on the session you're looking at right now. ---
        DropdownMenuSectionHeader(text = "This session")
        DropdownMenuItem(text = { Text("Rename session") }, onClick = onRenameSession)
        // Epic #821 Slice 1: classify a foreign session ("What is this
        // session?") or re-classify any session ("Change kind"). Writes the
        // durable host-side `@ps_agent_kind` option via ManualKindWriter.
        // Issue #857: moved up next to Rename — it identifies/changes *this*
        // session, so it belongs with the current-session actions rather than
        // buried mid-lifecycle between Rename and Stop.
        DropdownMenuItem(
            text = {
                Text(
                    if (changeKindIsUnknown) "What is this session?" else "Change kind",
                )
            },
            onClick = onChangeKind,
            modifier = Modifier.testTag(TMUX_CHANGE_KIND_BUTTON_TAG),
        )
        DropdownMenuItem(text = { Text("Stop session") }, onClick = onKillSession)
        // Issue #235: explicit "I'm done with this session for now" affordance —
        // frees the tmux server-side window-size lock (max(phone, desktop) ->
        // desktop dimensions) without killing the session. Still a current-
        // session action (Detach -> sessions dashboard), so it lives in the
        // "This session" group next to the lifecycle items.
        DropdownMenuItem(
            text = { Text("Detach") },
            onClick = onDetach,
            modifier = Modifier.testTag(TMUX_DETACH_BUTTON_TAG),
        )
        // Issue #892: manual escape hatch from a black/partial-black terminal — force a
        // full-viewport reseed of the pane you're looking at, over the warm session (no
        // reconnect / detach / new lease). Lives in the "This session" group because it
        // acts on the current pane's display only.
        DropdownMenuItem(
            text = { Text("Redraw") },
            onClick = onRedraw,
            modifier = Modifier.testTag(TMUX_REDRAW_BUTTON_TAG),
        )
        // Issue #993: manual escape hatch from a dropped session whose auto-reconnect
        // didn't fire — force an immediate reconnect of THIS session in place (no
        // session-switch dance), reusing the VM's single TransportEffects reconnect
        // entrypoint. On reconnect the #900 outbound queue auto-flushes the pending
        // message. Disabled (greyed) when there is no target or a reconnect is already
        // in flight so the tap is never a silent no-op / redundant re-dial.
        DropdownMenuItem(
            text = { Text("Reconnect") },
            onClick = onReconnect,
            enabled = reconnectEnabled,
            modifier = Modifier.testTag(TMUX_RECONNECT_BUTTON_TAG),
        )

        // --- Sessions: move between / create sessions on this host. ---
        HorizontalDivider()
        DropdownMenuSectionHeader(text = "Sessions")
        DropdownMenuItem(text = { Text("+ New session") }, onClick = onCreateSession)
        DropdownMenuItem(text = { Text("Switch session") }, onClick = onSwitchSession)

        // --- Files: open / browse files on this host. ---
        HorizontalDivider()
        DropdownMenuSectionHeader(text = "Files")
        // Issue #528: browse the remote filesystem and tap a file to open it in
        // the viewer.
        DropdownMenuItem(
            text = { Text("Browse files…") },
            onClick = onBrowseFiles,
            modifier = Modifier.testTag(TMUX_BROWSE_FILES_BUTTON_TAG),
        )
        // Issue #497: open a server file (image / text) in the in-app viewer.
        DropdownMenuItem(
            text = { Text("Open file…") },
            onClick = onOpenFile,
            modifier = Modifier.testTag(TMUX_OPEN_FILE_BUTTON_TAG),
        )

        // --- Connection: per-host networking. ---
        HorizontalDivider()
        DropdownMenuSectionHeader(text = "Connection")
        // Issue #445 (epic #432 slice A): per-host port-forward panel. Navigating
        // away pushes onto the hand-rolled back-stack; back returns to this exact
        // session/window.
        DropdownMenuItem(
            text = {
                if (forwardingState.visible) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            status = if (forwardingState.restoring) {
                                com.pocketshell.uikit.model.ConnectionStatus.Connecting
                            } else {
                                com.pocketshell.uikit.model.ConnectionStatus.Connected
                            },
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Port forwarding")
                            Text(
                                text = sessionForwardingMenuStatusLabel(forwardingState),
                                color = PocketShellColors.TextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Text("Port forwarding")
                }
            },
            onClick = onOpenPortForwarding,
            modifier = Modifier
                .semantics {
                    if (forwardingState.visible) {
                        contentDescription = forwardingState.contentDescription
                    }
                }
                .testTag(TMUX_PORT_FORWARDING_BUTTON_TAG),
        )

        // --- Host & app: cross-host / global affordances. ---
        HorizontalDivider()
        DropdownMenuSectionHeader(text = "Host & app")
        DropdownMenuItem(text = { Text("Recurring jobs") }, onClick = onOpenJobs)
        // Issue #114 Fix A: jump to the cross-host Usage / quota panel from
        // inside a live tmux session.
        DropdownMenuItem(text = { Text("Usage") }, onClick = onOpenUsage)
        DropdownMenuItem(
            text = { Text("Settings") },
            onClick = onOpenSettings,
            modifier = Modifier.testTag(TMUX_SETTINGS_BUTTON_TAG),
        )
    }
}

@Composable
private fun DropdownMenuSectionHeader(text: String) {
    Text(
        text = text,
        color = PocketShellColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
