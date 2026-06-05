package com.pocketshell.app.fileexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import com.pocketshell.core.ssh.RemoteEntry
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType
import java.util.Locale

const val FILE_EXPLORER_SCREEN_TAG = "fileExplorerScreen"
const val FILE_EXPLORER_BACK_TAG = "fileExplorerBack"
const val FILE_EXPLORER_PATH_TAG = "fileExplorerPath"
const val FILE_EXPLORER_GOTO_TAG = "fileExplorerGoTo"
const val FILE_EXPLORER_GOTO_FIELD_TAG = "fileExplorerGoToField"
const val FILE_EXPLORER_GOTO_CONFIRM_TAG = "fileExplorerGoToConfirm"
const val FILE_EXPLORER_UP_TAG = "fileExplorerUp"
const val FILE_EXPLORER_LOADING_TAG = "fileExplorerLoading"
const val FILE_EXPLORER_EMPTY_TAG = "fileExplorerEmpty"
const val FILE_EXPLORER_TRUNCATED_TAG = "fileExplorerTruncated"
const val FILE_EXPLORER_ERROR_TAG = "fileExplorerError"
const val FILE_EXPLORER_RETRY_TAG = "fileExplorerRetry"
const val FILE_EXPLORER_ROW_TAG_PREFIX = "fileExplorerRow:"

/**
 * Browsable remote file explorer — issue #528.
 *
 * Lists the remote filesystem over SSH (folders-first), navigates into folders
 * and back up without typing paths, and hands a tapped file to the existing
 * [com.pocketshell.app.fileviewer.FileViewerScreen]. Reachable from the
 * in-session kebab's "Browse files…" action.
 */
@Composable
fun FileExplorerScreen(
    hostName: String,
    hostname: String,
    port: Int,
    username: String,
    keyPath: String,
    passphrase: CharArray?,
    startDir: String,
    onBack: () -> Unit,
    onOpenFile: (absolutePath: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FileExplorerViewModel = hiltViewModel(),
) {
    LaunchedEffect(hostname, port, username, keyPath, startDir) {
        viewModel.start(
            FileExplorerViewModel.Request(
                hostname = hostname,
                port = port,
                username = username,
                keyPath = keyPath,
                passphrase = passphrase,
                startDir = startDir,
            ),
        )
    }
    val state by viewModel.state.collectAsState()
    FileExplorerScaffold(
        hostName = hostName,
        state = state,
        onBack = onBack,
        onUp = viewModel::goUp,
        onOpenDirectory = viewModel::openDirectory,
        onOpenFile = { entry -> onOpenFile(FileExplorerViewModel.joinPath(state.currentPath, entry.name)) },
        onCrumb = viewModel::navigateToAbsolute,
        onGoToPath = viewModel::goToPath,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

/**
 * Stateless body — split from the view-model wiring so Compose tests can drive
 * every state (Loading, Ready, empty, truncated, Failed) without an SSH
 * session. Mirrors the [com.pocketshell.app.fileviewer.FileViewerScaffold]
 * convention.
 */
@Composable
internal fun FileExplorerScaffold(
    hostName: String,
    state: FileExplorerUiState,
    onBack: () -> Unit,
    onUp: () -> Unit,
    onOpenDirectory: (RemoteEntry) -> Unit,
    onOpenFile: (RemoteEntry) -> Unit,
    onCrumb: (String) -> Unit,
    onGoToPath: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showGoTo by remember { mutableStateOf(false) }
    var goToText by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(FILE_EXPLORER_SCREEN_TAG),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FileExplorerHeader(
                hostName = hostName,
                path = state.currentPath,
                onBack = onBack,
                onCrumb = onCrumb,
                onGoTo = {
                    goToText = state.currentPath
                    showGoTo = true
                },
            )
            when (state) {
                is FileExplorerUiState.Loading -> LoadingPanel()
                is FileExplorerUiState.Failed -> ErrorPanel(
                    message = state.message,
                    canRetry = state.canRetry,
                    onRetry = onRetry,
                    onUp = onUp,
                )
                is FileExplorerUiState.Ready -> ReadyPanel(
                    state = state,
                    onUp = onUp,
                    onOpenDirectory = onOpenDirectory,
                    onOpenFile = onOpenFile,
                )
            }
        }
    }

    if (showGoTo) {
        AlertDialog(
            onDismissRequest = { showGoTo = false },
            title = { Text("Go to path") },
            text = {
                Column {
                    Text(
                        text = "Enter an absolute path, a ~-relative path, or a path " +
                            "relative to the current folder.",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                    OutlinedTextField(
                        value = goToText,
                        onValueChange = { goToText = it },
                        singleLine = true,
                        placeholder = { Text("e.g. /var/log or out") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .testTag(FILE_EXPLORER_GOTO_FIELD_TAG),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = goToText.isNotBlank(),
                    onClick = {
                        val target = goToText.trim()
                        showGoTo = false
                        if (target.isNotEmpty()) onGoToPath(target)
                    },
                    modifier = Modifier.testTag(FILE_EXPLORER_GOTO_CONFIRM_TAG),
                ) { Text("Go") }
            },
            dismissButton = {
                TextButton(onClick = { showGoTo = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FileExplorerHeader(
    hostName: String,
    path: String,
    onBack: () -> Unit,
    onCrumb: (String) -> Unit,
    onGoTo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Background)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(role = Role.Button, onClick = onBack)
                    .testTag(FILE_EXPLORER_BACK_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "‹",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 2.dp)) {
                Text(
                    text = "Files",
                    color = PocketShellColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = hostName,
                    color = PocketShellColors.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onGoTo)
                    .testTag(FILE_EXPLORER_GOTO_TAG)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Go to…",
                    color = PocketShellColors.Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        // Breadcrumb strip — each segment is tappable to jump to that ancestor.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                .testTag(FILE_EXPLORER_PATH_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val crumbs = FileExplorerViewModel.breadcrumbSegments(path)
            crumbs.forEachIndexed { index, (label, absolute) ->
                val isCurrent = index == crumbs.lastIndex
                Text(
                    text = label,
                    color = if (isCurrent) PocketShellColors.Text else PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyMono,
                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable(enabled = !isCurrent) { onCrumb(absolute) }
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                )
                if (index < crumbs.lastIndex) {
                    Text(
                        text = "›",
                        color = PocketShellColors.TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyPanel(
    state: FileExplorerUiState.Ready,
    onUp: () -> Unit,
    onOpenDirectory: (RemoteEntry) -> Unit,
    onOpenFile: (RemoteEntry) -> Unit,
) {
    val atRoot = state.currentPath == "/"
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Parent row, unless we're at the filesystem root.
        if (!atRoot) {
            item(key = "__up__") {
                ListRow(
                    title = "..",
                    subtitle = "Parent folder",
                    leading = { GlyphCell("↑") },
                    onClick = onUp,
                    modifier = Modifier.testTag(FILE_EXPLORER_UP_TAG),
                )
            }
        }
        if (state.entries.isEmpty()) {
            item(key = "__empty__") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .testTag(FILE_EXPLORER_EMPTY_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "This folder is empty.",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        items(state.entries, key = { it.name }) { entry ->
            // A symlink is treated as navigable (it usually points at a dir;
            // the server resolves it on re-list, and a link-to-file falls back
            // to a not-a-directory error the user can read).
            val navigable = entry.type == RemoteEntry.Type.DIRECTORY ||
                entry.type == RemoteEntry.Type.SYMLINK
            ListRow(
                title = entry.name,
                subtitle = rowSubtitle(entry),
                leading = { GlyphCell(glyphFor(entry.type)) },
                onClick = {
                    if (navigable) onOpenDirectory(entry) else onOpenFile(entry)
                },
                modifier = Modifier.testTag(FILE_EXPLORER_ROW_TAG_PREFIX + entry.name),
            )
        }
        if (state.truncated) {
            item(key = "__truncated__") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag(FILE_EXPLORER_TRUNCATED_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Showing the first ${state.entries.size} entries — " +
                            "this folder has more.",
                        color = PocketShellColors.TextMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun GlyphCell(glyph: String) {
    Box(
        modifier = Modifier.width(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, fontSize = 15.sp, color = PocketShellColors.TextSecondary)
    }
}

@Composable
private fun LoadingPanel() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(FILE_EXPLORER_LOADING_TAG),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = PocketShellColors.Accent)
    }
}

@Composable
private fun ErrorPanel(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onUp: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(FILE_EXPLORER_ERROR_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                color = PocketShellColors.Text,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRetry) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag(FILE_EXPLORER_RETRY_TAG),
                    ) { Text("Retry", color = PocketShellColors.Accent) }
                }
                TextButton(onClick = onUp) {
                    Text("Go up", color = PocketShellColors.TextSecondary)
                }
            }
        }
    }
}

private fun glyphFor(type: RemoteEntry.Type): String = when (type) {
    RemoteEntry.Type.DIRECTORY -> "📁"
    RemoteEntry.Type.SYMLINK -> "🔗"
    RemoteEntry.Type.OTHER -> "⚙"
    RemoteEntry.Type.FILE -> "📄"
}

private fun rowSubtitle(entry: RemoteEntry): String? = when (entry.type) {
    RemoteEntry.Type.DIRECTORY -> null
    RemoteEntry.Type.SYMLINK -> "link"
    RemoteEntry.Type.OTHER -> null
    RemoteEntry.Type.FILE -> formatSize(entry.sizeBytes)
}

/**
 * Human-readable byte count for a file row. Visible-for-test so the rounding
 * boundaries are pinned.
 */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}
