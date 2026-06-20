package com.pocketshell.app.fileexplorer

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.core.ssh.RemoteEntry
import com.pocketshell.core.ssh.SortField
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.FileIconClass
import com.pocketshell.uikit.components.FileTypeIcon
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
const val FILE_EXPLORER_UPLOAD_TAG = "fileExplorerUpload"
const val FILE_EXPLORER_SORT_TAG = "fileExplorerSort"
const val FILE_EXPLORER_SORT_ITEM_TAG_PREFIX = "fileExplorerSort:"
const val FILE_EXPLORER_DOWNLOAD_TAG_PREFIX = "fileExplorerDownload:"
const val FILE_EXPLORER_TRANSFER_TAG = "fileExplorerTransfer"
const val FILE_EXPLORER_TRANSFER_DISMISS_TAG = "fileExplorerTransferDismiss"

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
    hostId: Long,
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
    LaunchedEffect(hostId, hostname, port, username, keyPath, startDir) {
        viewModel.start(
            FileExplorerViewModel.Request(
                hostId = hostId,
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
    val transfer by viewModel.transfer.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val context = LocalContext.current

    // Upload: pick any device file, resolve its display name + size from the
    // content provider, and hand a fresh-stream opener to the view-model.
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        var displayName = "file"
        var size = -1L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) displayName = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        viewModel.uploadFile(
            displayName = displayName,
            length = size,
            openStream = { resolver.openInputStream(uri) },
        )
    }

    // Download: the user names a destination document on the device; we hold
    // the pending entry so the result callback knows which remote file to pull.
    var pendingDownload by remember { mutableStateOf<RemoteEntry?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri == null || entry == null) return@rememberLauncherForActivityResult
        viewModel.downloadFile(entry) { bytes ->
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Couldn't open the chosen destination.")
        }
    }

    FileExplorerScaffold(
        hostName = hostName,
        state = state,
        transfer = transfer,
        sort = sort,
        onSetSort = viewModel::setSort,
        onBack = onBack,
        onUp = viewModel::goUp,
        onOpenDirectory = viewModel::openDirectory,
        onOpenFile = { entry -> onOpenFile(FileExplorerViewModel.joinPath(state.currentPath, entry.name)) },
        onDownloadFile = { entry ->
            pendingDownload = entry
            downloadLauncher.launch(entry.name)
        },
        onUpload = { uploadLauncher.launch("*/*") },
        onDismissTransfer = viewModel::dismissTransfer,
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
    transfer: FileTransferState,
    sort: FileExplorerViewModel.SortMode = FileExplorerViewModel.SortMode(SortField.NAME, ascending = true),
    onSetSort: (SortField, Boolean) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    onUp: () -> Unit,
    onOpenDirectory: (RemoteEntry) -> Unit,
    onOpenFile: (RemoteEntry) -> Unit,
    onDownloadFile: (RemoteEntry) -> Unit,
    onUpload: () -> Unit,
    onDismissTransfer: () -> Unit,
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
                // Upload only makes sense once a directory is listed (we need a
                // resolved cwd); gate it on Ready.
                canUpload = state is FileExplorerUiState.Ready &&
                    transfer !is FileTransferState.InProgress,
                // Sorting only applies to a listed directory; gate on Ready.
                canSort = state is FileExplorerUiState.Ready,
                sort = sort,
                onSetSort = onSetSort,
                onBack = onBack,
                onUpload = onUpload,
                onCrumb = onCrumb,
                onGoTo = {
                    goToText = state.currentPath
                    showGoTo = true
                },
            )
            TransferBanner(transfer = transfer, onDismiss = onDismissTransfer)
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
                    onDownloadFile = onDownloadFile,
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
                        style = PocketShellType.bodyDense,
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
    canUpload: Boolean,
    canSort: Boolean,
    sort: FileExplorerViewModel.SortMode,
    onSetSort: (SortField, Boolean) -> Unit,
    onBack: () -> Unit,
    onUpload: () -> Unit,
    onCrumb: (String) -> Unit,
    onGoTo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Background)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft),
    ) {
        ScreenHeader(
            title = "Files",
            subtitle = hostName.ifBlank { null },
            leading = {
                Box(
                    modifier = Modifier
                        .size(PocketShellDensity.tapTargetMin)
                        .clickable(role = Role.Button, onClick = onBack)
                        .testTag(FILE_EXPLORER_BACK_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "‹",
                        color = PocketShellColors.TextSecondary,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canSort) {
                        SortAction(sort = sort, onSetSort = onSetSort)
                    }
                    if (canUpload) {
                        HeaderAction(
                            label = "Upload",
                            testTag = FILE_EXPLORER_UPLOAD_TAG,
                            onClick = onUpload,
                        )
                    }
                    HeaderAction(
                        label = "Go to…",
                        testTag = FILE_EXPLORER_GOTO_TAG,
                        onClick = onGoTo,
                    )
                }
            },
        )
        // Breadcrumb strip — each segment is tappable to jump to that ancestor.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(
                    start = PocketShellDensity.rowPadH,
                    end = PocketShellDensity.rowPadH,
                    bottom = PocketShellSpacing.sm,
                )
                .testTag(FILE_EXPLORER_PATH_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
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
                        .padding(horizontal = PocketShellSpacing.xs / 2, vertical = PocketShellSpacing.xs / 2),
                )
                if (index < crumbs.lastIndex) {
                    Text(
                        text = "›",
                        color = PocketShellColors.TextMuted,
                        style = PocketShellType.labelMono,
                    )
                }
            }
        }
    }
}

/** A compact text action button for the file explorer header. */
@Composable
private fun HeaderAction(
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(PocketShellDensity.tapTargetMin)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PocketShellSpacing.sm)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PocketShellColors.Accent,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The header Sort affordance (issue #762) — a compact text action that opens a
 * menu of Name / Size / Modified. Tapping a field that is already selected flips
 * its direction (asc ↔ desc); tapping a different field selects it in ascending
 * order. The active field shows a direction arrow. Re-sorting is a pure
 * in-memory reorder in the view-model — no re-list.
 */
@Composable
private fun SortAction(
    sort: FileExplorerViewModel.SortMode,
    onSetSort: (SortField, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        HeaderAction(
            label = "Sort",
            testTag = FILE_EXPLORER_SORT_TAG,
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(PocketShellColors.SurfaceElev),
        ) {
            SortField.values().forEach { field ->
                val selected = sort.field == field
                val arrow = if (selected) {
                    if (sort.ascending) " ↑" else " ↓"
                } else {
                    ""
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = sortFieldLabel(field) + arrow,
                            color = if (selected) PocketShellColors.Accent else PocketShellColors.Text,
                            style = PocketShellType.bodyDense,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        // Re-tap the active field → flip direction; new field →
                        // ascending.
                        val ascending = if (selected) !sort.ascending else true
                        onSetSort(field, ascending)
                    },
                    modifier = Modifier.testTag(FILE_EXPLORER_SORT_ITEM_TAG_PREFIX + field.name),
                )
            }
        }
    }
}

private fun sortFieldLabel(field: SortField): String = when (field) {
    SortField.NAME -> "Name"
    SortField.SIZE -> "Size"
    SortField.MODIFIED -> "Modified"
}

/**
 * Transfer status banner (issue #643) — overlays the top of the listing while
 * an upload/download is in flight or after one finishes. Stays out of the
 * listing's own state so the directory rows remain visible underneath.
 */
@Composable
private fun TransferBanner(
    transfer: FileTransferState,
    onDismiss: () -> Unit,
) {
    if (transfer is FileTransferState.Idle) return
    val (text, role, showSpinner, dismissible) = when (transfer) {
        is FileTransferState.InProgress ->
            BannerSpec(
                text = (if (transfer.isUpload) "Uploading " else "Downloading ") + transfer.name + "…",
                role = BannerRole.Progress,
                showSpinner = true,
                dismissible = false,
            )
        is FileTransferState.Success ->
            BannerSpec(transfer.message, BannerRole.Success, showSpinner = false, dismissible = true)
        is FileTransferState.Failure ->
            BannerSpec(transfer.message, BannerRole.Error, showSpinner = false, dismissible = true)
        FileTransferState.Idle -> return
    }
    val accent = when (role) {
        BannerRole.Progress -> PocketShellColors.Accent
        BannerRole.Success -> PocketShellColors.Accent
        BannerRole.Error -> PocketShellColors.TextSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft)
            .padding(horizontal = PocketShellDensity.rowPadH, vertical = PocketShellSpacing.sm)
            .testTag(FILE_EXPLORER_TRANSFER_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
    ) {
        if (showSpinner) {
            LoadingIndicator.Spinner(size = SpinnerSize.Small)
        }
        Text(
            text = text,
            color = accent,
            style = PocketShellType.bodyDense,
            modifier = Modifier.weight(1f),
        )
        if (dismissible) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(FILE_EXPLORER_TRANSFER_DISMISS_TAG),
            ) {
                Text("Dismiss", color = PocketShellColors.Accent, style = PocketShellType.bodyDense)
            }
        }
    }
}

private enum class BannerRole { Progress, Success, Error }

private data class BannerSpec(
    val text: String,
    val role: BannerRole,
    val showSpinner: Boolean,
    val dismissible: Boolean,
)

@Composable
private fun ReadyPanel(
    state: FileExplorerUiState.Ready,
    onUp: () -> Unit,
    onOpenDirectory: (RemoteEntry) -> Unit,
    onOpenFile: (RemoteEntry) -> Unit,
    onDownloadFile: (RemoteEntry) -> Unit,
) {
    val atRoot = state.currentPath == "/"
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = PocketShellSpacing.sm),
    ) {
        if (!atRoot) {
            item(key = "__up__") {
                ListRow(
                    title = "..",
                    subtitle = "Parent folder",
                    leading = { FileTypeIcon(iconClass = FileIconClass.FOLDER) },
                    trailing = { Chevron() },
                    onClick = onUp,
                    modifier = Modifier.testTag(FILE_EXPLORER_UP_TAG),
                )
            }
        }
        item(key = "__entries_header__") {
            SectionHeader(label = "Entries", count = state.entries.size)
        }
        if (state.entries.isEmpty()) {
            item(key = "__empty__") {
                ListRow(
                    title = "No entries",
                    subtitle = "This folder is empty.",
                    leading = { StatusGlyphCell("–") },
                    modifier = Modifier.testTag(FILE_EXPLORER_EMPTY_TAG),
                )
            }
        }
        items(state.entries, key = { it.name }) { entry ->
            // A symlink is treated as navigable (it usually points at a dir;
            // the server resolves it on re-list, and a link-to-file falls back
            // to a not-a-directory error the user can read).
            val navigable = entry.type == RemoteEntry.Type.DIRECTORY ||
                entry.type == RemoteEntry.Type.SYMLINK
            // Regular files get a tap-to-download affordance in the trailing
            // slot (issue #643); tapping the row itself still opens the viewer.
            // Everything else gets a navigational chevron (issue #762) — the
            // redundant Folder/Link/Other type pills are gone (hard-cut, D22).
            val trailing: (@Composable () -> Unit)? = if (entry.type == RemoteEntry.Type.FILE) {
                {
                    Box(
                        modifier = Modifier
                            .size(PocketShellDensity.tapTargetMin)
                            .clickable(role = Role.Button) { onDownloadFile(entry) }
                            .testTag(FILE_EXPLORER_DOWNLOAD_TAG_PREFIX + entry.name),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "↓",
                            color = PocketShellColors.Accent,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            } else {
                { Chevron() }
            }
            ListRow(
                title = entry.name,
                subtitle = rowSubtitle(entry),
                leading = { FileTypeIcon(iconClass = fileIconClass(entry.name, entry.type)) },
                trailing = trailing,
                onClick = {
                    if (navigable) onOpenDirectory(entry) else onOpenFile(entry)
                },
                modifier = Modifier.testTag(FILE_EXPLORER_ROW_TAG_PREFIX + entry.name),
            )
        }
        if (state.truncated) {
            item(key = "__truncated__") {
                ListRow(
                    title = "Folder truncated",
                    subtitle = "Showing the first ${state.entries.size} entries; this folder has more.",
                    leading = { StatusGlyphCell("…") },
                    modifier = Modifier.testTag(FILE_EXPLORER_TRUNCATED_TAG),
                )
            }
        }
    }
}

/**
 * A small text glyph cell for NON-entry status rows (empty / truncated / error)
 * — issue #762. File/folder entries lead with a [FileTypeIcon] instead; this is
 * only the `–` / `…` / `!` status marker. `maxLines = 1` + `softWrap = false`
 * so a short status marker never wraps in the fixed cell.
 */
@Composable
private fun StatusGlyphCell(glyph: String) {
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.labelMono,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The navigational chevron in the trailing slot for a folder / symlink row. */
@Composable
private fun Chevron() {
    Box(
        modifier = Modifier.size(PocketShellDensity.tapTargetMin),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "›",
            color = PocketShellColors.TextMuted,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun LoadingPanel() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(FILE_EXPLORER_LOADING_TAG),
        contentPadding = PaddingValues(vertical = PocketShellSpacing.sm),
    ) {
        item {
            SectionHeader(label = "Status")
        }
        item {
            ListRow(
                title = "Loading folder",
                subtitle = "Fetching remote entries",
                leading = {
                    LoadingIndicator.Spinner(size = SpinnerSize.Small)
                },
            )
        }
    }
}

@Composable
private fun ErrorPanel(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onUp: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(FILE_EXPLORER_ERROR_TAG),
        contentPadding = PaddingValues(vertical = PocketShellSpacing.sm),
    ) {
        item {
            SectionHeader(label = "Status")
        }
        item {
            ListRow(
                title = "Could not load folder",
                subtitle = message,
                leading = { StatusGlyphCell("!") },
                trailing = {
                    Badge(label = "Error", role = BadgeRole.Error, mono = false)
                },
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PocketShellDensity.rowPadH),
                horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
            ) {
                if (canRetry) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag(FILE_EXPLORER_RETRY_TAG),
                    ) {
                        Text("Retry", color = PocketShellColors.Accent, style = PocketShellType.bodyDense)
                    }
                }
                TextButton(onClick = onUp) {
                    Text("Go up", color = PocketShellColors.TextSecondary, style = PocketShellType.bodyDense)
                }
            }
        }
    }
}

/**
 * The secondary (muted mono) line for a row — issue #762.
 *
 * Files: `size · modified` (e.g. `8.6 KB · Jun 12`), surfacing the
 * `modifiedEpochSec` the model has always carried but never showed. Folders /
 * symlinks / others: just the modified date when the server reported one. A
 * `null` mtime collapses cleanly — a file shows just the size; a folder with no
 * mtime shows no subtitle. Visible-for-test so the composition is pinned.
 */
internal fun rowSubtitle(entry: RemoteEntry): String? {
    val modified = formatModified(entry.modifiedEpochSec)
    return when (entry.type) {
        RemoteEntry.Type.FILE -> {
            val size = formatSize(entry.sizeBytes)
            if (modified != null) "$size · $modified" else size
        }
        else -> modified
    }
}

/**
 * The coarse 6-bucket icon class for a row leading icon — issue #762. Folders /
 * symlinks come straight off the SFTP [RemoteEntry.Type]; regular files (and
 * OTHER) are bucketed by a small extension map into image / code / archive /
 * binary. Deliberately dev-tool-right granularity (not a per-MIME explosion).
 * Visible-for-test so the extension buckets are pinned.
 */
internal fun fileIconClass(name: String, type: RemoteEntry.Type): FileIconClass = when (type) {
    RemoteEntry.Type.DIRECTORY -> FileIconClass.FOLDER
    RemoteEntry.Type.SYMLINK -> FileIconClass.SYMLINK
    else -> when (extensionOf(name)) {
        in IMAGE_EXTENSIONS -> FileIconClass.IMAGE
        in CODE_EXTENSIONS -> FileIconClass.CODE
        in ARCHIVE_EXTENSIONS -> FileIconClass.ARCHIVE
        else -> FileIconClass.BINARY
    }
}

/** Lower-cased final extension of a basename, or "" when there is none. */
private fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    // No dot, leading-dot dotfile (".bashrc"), or trailing dot → no extension.
    if (dot <= 0 || dot == name.lastIndex) return ""
    return name.substring(dot + 1).lowercase(Locale.US)
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico")

private val CODE_EXTENSIONS = setOf(
    "kt", "java", "js", "ts", "tsx", "jsx", "py", "sh", "bash", "go", "rs",
    "c", "cpp", "cc", "h", "hpp", "json", "yaml", "yml", "toml", "xml", "html",
    "css", "scss", "md", "txt", "log", "patch", "diff", "rb", "php", "sql",
    "gradle", "kts", "properties", "ini", "cfg", "conf",
)

private val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar")

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

/**
 * Compact modified-date label for a row — issue #762. Surfaces
 * [RemoteEntry.modifiedEpochSec] for the first time. This-year dates read as
 * `Jun 12`; older dates as `Jan 2024`; a null mtime (server reported none)
 * returns null so the subtitle collapses. `Locale.US` + system default zone so
 * the rendering is deterministic and matches the device clock; visible-for-test
 * via [formatModifiedAt], which pins the "now" reference.
 */
internal fun formatModified(epochSec: Long?): String? =
    formatModifiedAt(epochSec, ZonedDateTime.now(ZoneId.systemDefault()))

/** Testable core of [formatModified] with an injectable [now] reference. */
internal fun formatModifiedAt(epochSec: Long?, now: ZonedDateTime): String? {
    if (epochSec == null) return null
    val moment = Instant.ofEpochSecond(epochSec).atZone(now.zone)
    return if (moment.year == now.year) {
        moment.format(THIS_YEAR_FORMAT)
    } else {
        moment.format(OLDER_YEAR_FORMAT)
    }
}

private val THIS_YEAR_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val OLDER_YEAR_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
