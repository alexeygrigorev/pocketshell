package com.pocketshell.app.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Host picker for the Android share-target flow (issue #138).
 *
 * Lists every configured host so the user can pick the destination
 * for the inbound file/text. Tap a row -> [ShareViewModel.startUpload]
 * runs SCP in the background. While the upload runs we swap the
 * picker for a small "uploading to X" surface; successful file uploads
 * finish quietly, while failures remain visible with a retryable error
 * detail.
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
    val targetSelection by viewModel.targetSelection.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(SHARE_PICKER_ROOT_TAG),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (val state = uploadState) {
            is UploadState.Idle -> {
                val selection = targetSelection
                when {
                    // Issue #473: once a host is picked (file-save mode),
                    // show the per-host target chooser ("Host inbox" +
                    // the active-session quick target + known projects)
                    // before the upload runs.
                    selection != null ->
                        TargetPickerScreen(
                            selection = selection,
                            onChooseSession = { session ->
                                viewModel.stageIntoSession(selection.host, session)
                            },
                            onChooseHostInbox = {
                                viewModel.startUpload(selection.host, ShareTarget.HostInbox)
                            },
                            onChooseProject = { project ->
                                viewModel.startUpload(
                                    selection.host,
                                    ShareTarget.Project(project.path),
                                )
                            },
                            onBack = { viewModel.clearTargetSelection() },
                        )
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
                            // Issue #473: tapping a host now opens the
                            // target chooser (host inbox vs. a project's
                            // .inbox/) rather than uploading immediately.
                            subtitle = "Choose host inbox or a project's .inbox",
                            onHostClick = { host -> viewModel.selectTargetHost(host) },
                        )
                }
            }
            is UploadState.Running -> UploadingSurface(hostName = state.hostName)
            is UploadState.Success -> {
                val isPaste = dispatchChoice == TextDispatchChoice.PasteIntoSession
                if (isPaste) {
                    UploadResultSurface(
                        title = "Pasted into ${state.hostName}",
                        detail = state.remotePath,
                        isError = false,
                        onDismiss = {
                            viewModel.clearUploadState()
                            onUploadComplete()
                        },
                    )
                } else {
                    LaunchedEffect(state) {
                        onUploadComplete()
                    }
                    Box(Modifier.fillMaxSize())
                }
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

/**
 * Issue #473: per-host target chooser. Lets the user route the staged
 * file(s) to either the host inbox (`~/inbox/pocketshell/`, default) or
 * a specific project's `.inbox/` on that host.
 *
 * Layout (top to bottom):
 *  - "Host inbox" — the default, always present.
 *  - Issue #507: the current/open sessions' projects (each live tmux
 *    session's active-pane cwd, focused session first), as prominent
 *    one-tap quick targets ("share to the project I'm working in").
 *  - The host's top-level watched roots / recent folders, de-duplicated
 *    against the session projects above.
 */
@Composable
private fun TargetPickerScreen(
    selection: TargetSelection,
    onChooseSession: (ActiveSessionTarget) -> Unit,
    onChooseHostInbox: () -> Unit,
    onChooseProject: (ProjectTarget) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .testTag(SHARE_TARGET_PICKER_TAG),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Send to ${selection.host.name}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Pick the destination on this host",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }

        // Issue #560: the host's active tmux sessions lead the list — the
        // most convenient destination. Picking one stages the shared file
        // into that session (the #544 attachment mechanic) and opens the
        // session with the file as a composer chip, composer focused.
        if (selection.activeSessions.isNotEmpty()) {
            item {
                Text(
                    text = "Active sessions",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(
            selection.activeSessions,
            key = { _, session -> "active-session:" + session.sessionName },
        ) { index, session ->
            TargetRow(
                title = if (session.focused) {
                    "${session.label} (focused session)"
                } else {
                    "${session.label} (session)"
                },
                subtitle = session.cwd.takeIf { it.isNotBlank() }
                    ?: "Stage into this session's composer",
                testTag = if (index == 0) {
                    SHARE_TARGET_ACTIVE_SESSION_TAG
                } else {
                    SHARE_TARGET_SESSION_ROW_TAG_PREFIX + session.sessionName
                },
                onClick = { onChooseSession(session) },
            )
        }

        item {
            TargetRow(
                title = "Host inbox",
                subtitle = "${ShareUploader.INBOX_DISPLAY_PATH}/",
                testTag = SHARE_TARGET_HOST_INBOX_TAG,
                onClick = onChooseHostInbox,
            )
        }

        // Issue #507: the current/open sessions' projects, prominent at
        // the top so the user can drop a file into the project they are
        // actually working in (not just a top-level watched root). The
        // first entry is the focused session.
        if (selection.sessionProjects.isNotEmpty()) {
            item {
                Text(
                    text = "Open session projects",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(
            selection.sessionProjects,
            key = { _, project -> "session:" + project.path },
        ) { index, project ->
            TargetRow(
                title = if (index == 0) {
                    "${project.label} (active session)"
                } else {
                    "${project.label} (session)"
                },
                subtitle = "${project.path}/.inbox/",
                testTag = if (index == 0) {
                    SHARE_TARGET_ACTIVE_PROJECT_TAG
                } else {
                    SHARE_TARGET_SESSION_PROJECT_ROW_TAG_PREFIX + project.path
                },
                onClick = { onChooseProject(project) },
            )
        }

        if (selection.loading) {
            item {
                Text(
                    text = "Loading projects…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (selection.knownProjects.isNotEmpty()) {
            item {
                Text(
                    text = "Watched roots",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(selection.knownProjects, key = { it.path }) { project ->
            TargetRow(
                title = project.label,
                subtitle = "${project.path}/.inbox/",
                testTag = SHARE_TARGET_PROJECT_ROW_TAG_PREFIX + project.path,
                onClick = { onChooseProject(project) },
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag(SHARE_TARGET_BACK_TAG),
            ) {
                Text(text = "Back")
            }
        }
    }
}

@Composable
private fun TargetRow(
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit,
) {
    // Slice E1b (#539): the Material default-density card adopts the shared
    // dense `ListRow`. The target path is the mono subtitle (path data).
    ListRow(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.testTag(testTag),
        onClick = onClick,
    )
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
    // Slice E1b (#539): the Material default-density card adopts the shared
    // dense `ListRow`. `user@host:port` is the mono subtitle; the optional
    // last-connected timestamp folds into the trailing slot so no information
    // is lost when collapsing the 3-line card to the dense 2-line row.
    ListRow(
        title = host.name,
        subtitle = "${host.username}@${host.hostname}:${host.port}",
        modifier = Modifier.testTag(SHARE_HOST_ROW_TAG_PREFIX + host.id),
        onClick = onClick,
        trailing = host.lastConnectedAt?.let { connectedAt ->
            {
                Text(
                    text = java.text.DateFormat.getDateTimeInstance()
                        .format(java.util.Date(connectedAt)),
                    style = PocketShellType.labelMono,
                    color = PocketShellColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
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
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onDismiss) {
                Text(text = "Done")
            }
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
internal const val SHARE_TARGET_PICKER_TAG: String = "share:target:picker"
internal const val SHARE_TARGET_HOST_INBOX_TAG: String = "share:target:host-inbox"
internal const val SHARE_TARGET_ACTIVE_SESSION_TAG: String = "share:target:active-session"
internal const val SHARE_TARGET_SESSION_ROW_TAG_PREFIX: String = "share:target:session:"
internal const val SHARE_TARGET_ACTIVE_PROJECT_TAG: String = "share:target:active-project"
internal const val SHARE_TARGET_SESSION_PROJECT_ROW_TAG_PREFIX: String =
    "share:target:session-project:"
internal const val SHARE_TARGET_PROJECT_ROW_TAG_PREFIX: String = "share:target:project:"
internal const val SHARE_TARGET_BACK_TAG: String = "share:target:back"
