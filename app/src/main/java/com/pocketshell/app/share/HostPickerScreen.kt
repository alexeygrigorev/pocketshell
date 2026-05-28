package com.pocketshell.app.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketshell.core.storage.entity.HostEntity

/**
 * Host picker for the Android share-target flow (issue #138).
 *
 * Lists every configured host so the user can pick the destination
 * for the inbound file/text. Tap a row -> [ShareViewModel.startUpload]
 * runs SCP in the background. While the upload runs we swap the
 * picker for a small "uploading to X" surface; when it finishes the
 * activity dismisses itself and the system notification carries the
 * result.
 *
 * Issue #193: when the user picked the "Paste into session" branch
 * (text/plain shares only), the picker filters to hosts with a
 * registered live `tmux -CC` client and routes taps through
 * [ShareViewModel.pasteIntoSession] instead of the SCP uploader.
 *
 * Empty state: if there are zero hosts, surface a "Set up a host
 * first" message instead of an empty list (issue spec).
 */
@Composable
internal fun HostPickerScreen(
    viewModel: ShareViewModel,
    onUploadComplete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val item by viewModel.item.collectAsStateWithLifecycle()
    val uploadState by viewModel.uploadState.collectAsStateWithLifecycle()
    val dispatchChoice by viewModel.dispatchChoice.collectAsStateWithLifecycle()
    val hasAttached by viewModel.hasAttachedSession.collectAsStateWithLifecycle()
    val attachedHostIds by viewModel.hostsWithAttachedSession.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(SHARE_PICKER_ROOT_TAG),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (val state = uploadState) {
            is UploadState.Idle -> {
                when {
                    dispatchChoice == TextDispatchChoice.PromptUser ->
                        TextDispatchDialog(
                            hasAttachedSession = hasAttached,
                            onPaste = { viewModel.chooseTextPasteIfAvailable() },
                            onSave = { viewModel.chooseSaveAsFile() },
                            onCancel = onCancel,
                        )
                    item == null ->
                        ShareEmptyState(message = "Nothing to share")
                    dispatchChoice == TextDispatchChoice.PasteIntoSession -> {
                        // Issue #193: filter the picker to hosts that
                        // have a live `tmux -CC` client registered.
                        // Tapping such a host routes through
                        // `pasteIntoSession` instead of the SCP
                        // uploader. If no host qualifies (the rare
                        // race where the user's attached client tore
                        // down between the dispatch dialog and the
                        // picker render), fall back to the save-as-file
                        // surface with a clear message.
                        val pasteHosts = hosts.filter { it.id in attachedHostIds }
                        when {
                            pasteHosts.isEmpty() ->
                                ShareEmptyState(
                                    message = "No active session — save to inbox instead",
                                )
                            else ->
                                HostList(
                                    hosts = pasteHosts,
                                    title = "Paste into session",
                                    subtitle =
                                        "Text lands on the focused pane via tmux send-keys -l",
                                    onHostClick = { host -> viewModel.pasteIntoSession(host) },
                                )
                        }
                    }
                    hosts.isEmpty() ->
                        ShareEmptyState(message = "Set up a host first")
                    else ->
                        HostList(
                            hosts = hosts,
                            // Issue #258: when several files are staged,
                            // tell the user up front that all of them go
                            // to the host they pick.
                            title = if (items.size > 1) {
                                "Send ${items.size} files to host"
                            } else {
                                "Send to host"
                            },
                            subtitle = "Files land under ${ShareUploader.INBOX_DISPLAY_PATH}",
                            onHostClick = { host -> viewModel.startUpload(host) },
                        )
                }
            }
            is UploadState.Running -> UploadingSurface(hostName = state.hostName)
            is UploadState.Success -> {
                val isPaste = dispatchChoice == TextDispatchChoice.PasteIntoSession
                UploadResultSurface(
                    title = when {
                        isPaste -> "Pasted into ${state.hostName}"
                        state.totalCount > 1 ->
                            "Uploaded ${state.successCount} files to ${state.hostName}"
                        else -> "Uploaded to ${state.hostName}"
                    },
                    detail = state.remotePath,
                    isError = false,
                    onDismiss = {
                        viewModel.clearUploadState()
                        onUploadComplete()
                    },
                )
            }
            is UploadState.Failed -> {
                val isPaste = dispatchChoice == TextDispatchChoice.PasteIntoSession
                UploadResultSurface(
                    title = when {
                        isPaste -> "Could not paste into ${state.hostName}"
                        // Issue #258: a partial multi-file failure (some
                        // files landed) deserves a distinct title so the
                        // user does not think the whole share was lost.
                        state.totalCount > 1 && state.successCount > 0 ->
                            "Uploaded ${state.successCount} of ${state.totalCount} to ${state.hostName}"
                        state.totalCount > 1 ->
                            "Could not upload to ${state.hostName}"
                        else -> "Could not upload to ${state.hostName}"
                    },
                    detail = state.message,
                    isError = true,
                    onDismiss = {
                        viewModel.clearUploadState()
                        onUploadComplete()
                    },
                )
            }
        }
    }
}

@Composable
private fun HostList(
    hosts: List<HostEntity>,
    title: String,
    subtitle: String,
    onHostClick: (HostEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
        items(hosts, key = { it.id }) { host ->
            HostRow(host = host, onClick = { onHostClick(host) })
        }
    }
}

@Composable
private fun HostRow(host: HostEntity, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(SHARE_HOST_ROW_TAG_PREFIX + host.id),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = host.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${host.username}@${host.hostname}:${host.port}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            host.lastConnectedAt?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Last connected — ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ShareEmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag(SHARE_EMPTY_STATE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UploadingSurface(hostName: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Uploading to $hostName...",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun UploadResultSurface(
    title: String,
    detail: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag(if (isError) SHARE_RESULT_FAILURE_TAG else SHARE_RESULT_SUCCESS_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                modifier = Modifier
                    .padding(12.dp)
                    .testTag(SHARE_RESULT_DETAIL_TAG),
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onDismiss) {
            Text(text = "Done")
        }
    }
}

@Composable
private fun TextDispatchDialog(
    hasAttachedSession: Boolean,
    onPaste: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = "Send text to host") },
        text = {
            Text(
                text = if (hasAttachedSession) {
                    "Paste into the active session or save as a .txt file in the inbox?"
                } else {
                    // Issue #193: clearer "why paste is disabled" copy
                    // so the user understands that attaching to a
                    // session unlocks the paste branch (vs. the old
                    // "No session is attached" which read like a
                    // permanent limitation).
                    "No active session — save to inbox instead."
                },
            )
        },
        confirmButton = {
            // Issue #222 (#208 follow-up): "Paste into session" is the
            // more-frequent action when a session is attached, so it
            // belongs in the right-hand `confirmButton` slot (the
            // thumb-friendly primary position). "Save as file" moves to
            // the left `dismissButton` slot.
            //
            // Issue #193: the paste option is now wired end-to-end.
            // Enable iff at least one host in the user's list has a
            // registered live `tmux -CC` client in
            // [com.pocketshell.app.sessions.ActiveTmuxClients].
            TextButton(
                onClick = onPaste,
                enabled = hasAttachedSession,
                modifier = Modifier.testTag(SHARE_TEXT_PASTE_TAG),
            ) {
                Text(text = "Paste into session")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onSave,
                modifier = Modifier.testTag(SHARE_TEXT_SAVE_TAG),
            ) {
                Text(text = "Save as file")
            }
        },
    )
}

internal const val SHARE_PICKER_ROOT_TAG: String = "share:picker:root"
internal const val SHARE_HOST_ROW_TAG_PREFIX: String = "share:host:row:"
internal const val SHARE_RESULT_SUCCESS_TAG: String = "share:result:success"
internal const val SHARE_RESULT_FAILURE_TAG: String = "share:result:failure"
internal const val SHARE_RESULT_DETAIL_TAG: String = "share:result:detail"
internal const val SHARE_TEXT_PASTE_TAG: String = "share:text:paste"
internal const val SHARE_TEXT_SAVE_TAG: String = "share:text:save"
internal const val SHARE_EMPTY_STATE_TAG: String = "share:picker:empty"
