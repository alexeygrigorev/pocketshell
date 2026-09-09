@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pocketshell.next.files

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.core.transport.SftpEntry
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.FileIconClass
import com.pocketshell.uikit.components.FileTypeIcon
import com.pocketshell.uikit.components.KebabTrigger
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.components.fileIconClassForName
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType
import kotlinx.coroutines.flow.collect
import java.util.concurrent.TimeUnit

/** Header context shared by the file browser and its transfer history. */
internal fun fileLocationSubtitle(hostName: String, path: String): String? {
    val host = hostName.trim().takeIf { it.isNotEmpty() }
    val location = com.pocketshell.next.workspaces.displayRemotePath(path)
    return listOfNotNull(host, location).joinToString(" · ").takeIf { it.isNotEmpty() }
}

/** Stable test tags. Rows are keyed by the host's own file names. */
const val FILE_EXPLORER_TAG: String = "file-explorer"
const val FILE_EXPLORER_LIST_TAG: String = "file-explorer-list"
const val FILE_EXPLORER_UP_TAG: String = "file-explorer-up"
const val FILE_EXPLORER_UPLOAD_TAG: String = "file-explorer-upload"
const val FILE_EXPLORER_LOADING_TAG: String = "file-explorer-loading"
const val FILE_EXPLORER_EMPTY_TAG: String = "file-explorer-empty"
const val FILE_EXPLORER_ERROR_TAG: String = "file-explorer-error"
const val FILE_EXPLORER_TRANSFER_TAG: String = "file-explorer-transfer"
const val FILE_EXPLORER_CRUMBS_TAG: String = "file-explorer-crumbs"
const val FILE_EXPLORER_ACTIONS_TAG: String = "file-explorer-actions"
const val FILE_EXPLORER_TOOLS_SHEET_TAG: String = "file-explorer-tools-sheet"
const val FILE_EXPLORER_CREATE_FOLDER_TAG: String = "file-explorer-create-folder"
const val FILE_EXPLORER_CREATE_FOLDER_NAME_TAG: String = "file-explorer-create-folder-name"
const val FILE_EXPLORER_CREATE_FOLDER_CONFIRM_TAG: String = "file-explorer-create-folder-confirm"
const val FILE_EXPLORER_NEW_TEXT_FILE_SHEET_TAG: String = "file-explorer-new-text-file-sheet"
const val FILE_EXPLORER_NEW_TEXT_FILE_NAME_TAG: String = "file-explorer-new-text-file-name"
const val FILE_EXPLORER_NEW_TEXT_FILE_CONFIRM_TAG: String = "file-explorer-new-text-file-confirm"
const val FILE_EXPLORER_FILE_ACTIONS_TAG: String = "file-explorer-file-actions"
const val FILE_EXPLORER_RENAME_TAG: String = "file-explorer-rename"
const val FILE_EXPLORER_RENAME_NAME_TAG: String = "file-explorer-rename-name"
const val FILE_EXPLORER_RENAME_CONFIRM_TAG: String = "file-explorer-rename-confirm"
const val FILE_EXPLORER_DELETE_TAG: String = "file-explorer-delete"
const val FILE_EXPLORER_DELETE_CONFIRM_TAG: String = "file-explorer-delete-confirm"
const val FILE_EXPLORER_TRANSFERS_TAG: String = "file-explorer-transfers"
const val FILE_EXPLORER_NEW_TEXT_FILE_TAG: String = "file-explorer-new-text-file"

fun fileRowTag(name: String): String = "file-row-$name"

fun fileDownloadTag(name: String): String = "file-download-$name"

fun fileActionsTag(name: String): String = "file-actions-$name"

fun crumbTag(path: String): String = "file-crumb-$path"

/**
 * Route-level entry point: binds the Hilt-provided [FileExplorerViewModel] to
 * the stateless [FileExplorerScreen] and owns the two Storage Access Framework
 * launchers.
 *
 * ## Why SAF and not a file path
 *
 * app2 targets SDK 35, where an app has no general read/write access to shared
 * storage and `WRITE_EXTERNAL_STORAGE` does nothing. Both directions therefore
 * go through the system document picker: `GetContent` returns a readable
 * content URI for an upload, `CreateDocument` lets the user *name* the
 * destination for a download and returns a writable one. The app declares no
 * storage permission at all, works identically on API 26 and 35, and the user
 * sees the standard picker they already know. A `MediaStore` "save to Downloads"
 * path would need its own per-API-level branch and would not let the user choose
 * the destination.
 *
 * `ON_START` drives the refresh for the same reason the session tree does: it
 * covers first entry, coming back from the viewer, and returning from the
 * background, all with one trigger the ViewModel de-duplicates.
 */
@Composable
fun FileExplorerRoute(
    onOpenFile: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FileExplorerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }

    LaunchedEffect(state.newFilePathToOpen) {
        state.newFilePathToOpen?.let { path ->
            viewModel.consumeNewFilePath()
            onOpenFile(path)
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            val resolver = context.contentResolver
            val document = describeDocument(
                queryColumns = { columns ->
                    resolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        columns.associateWith { column ->
                            val index = cursor.getColumnIndex(column)
                            if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
                        }
                    }
                },
                fallbackName = uri.lastPathSegment ?: "upload",
            )
            viewModel.upload(
                displayName = document.name,
                declaredSize = document.size,
                openStream = { resolver.openInputStream(uri) },
            )
        }
    }

    // The picker names the destination; the entry it is FOR has to survive the
    // round-trip through the system UI, so it is held here rather than inferred
    // from the returned URI (which carries the user's chosen name, not the
    // remote one).
    var pendingDownload by remember { mutableStateOf<SftpEntry?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) {
            viewModel.download(entry) { bytes ->
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("could not open the chosen destination")
                stream.use { it.write(bytes) }
            }
        }
    }

    FileExplorerScreen(
        state = state,
        onBack = onBack,
        onUp = viewModel::goUp,
        onOpenDirectory = viewModel::openDirectory,
        onOpenFile = { entry -> onOpenFile(entry.path) },
        onNavigateTo = viewModel::navigateTo,
        onUpload = { uploadLauncher.launch("*/*") },
        onDownload = { entry ->
            pendingDownload = entry
            downloadLauncher.launch(entry.name)
        },
        onCopyPath = { path ->
            context.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("Remote path", path))
            viewModel.dismissActions()
        },
        onDismissTransfer = viewModel::dismissTransfer,
        onRetry = viewModel::refresh,
        onOpenTools = viewModel::openTools,
        onDismissTools = viewModel::dismissTools,
        onOpenActions = viewModel::openActions,
        onDismissActions = viewModel::dismissActions,
        onOpenCreateFolder = viewModel::openCreateFolder,
        onCreateFolderNameChange = viewModel::setCreateFolderName,
        onCreateFolder = viewModel::createFolder,
        onDismissCreateFolder = viewModel::dismissCreateFolder,
        onOpenRename = viewModel::openRename,
        onRenameNameChange = viewModel::setRenameName,
        onRename = viewModel::renameFile,
        onDismissRename = viewModel::dismissRename,
        onRequestDelete = viewModel::requestDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onDismissDelete = viewModel::dismissDelete,
        onOpenTransfers = viewModel::openTransfers,
        onDismissTransfers = viewModel::dismissTransfers,
        onRetryTransfer = viewModel::retryTransfer,
        onNewTextFile = viewModel::openNewTextFile,
        onNewTextFileNameChange = viewModel::setNewTextFileName,
        onCreateNewTextFile = viewModel::createNewTextFile,
        onDismissNewTextFile = viewModel::dismissNewTextFile,
        onDismissOperationMessage = viewModel::dismissOperationMessage,
        modifier = modifier,
    )
}

/**
 * The remote file explorer (rewrite task P-3a).
 *
 * Stateless: everything it paints comes from [state], so it renders identically
 * from a journey, a Robolectric test and a design render. Built from the ui-kit
 * primitives ([ScreenHeader], [ListRow], [FileTypeIcon], [Banner],
 * [EmptyState]) so row density, tap-target floor and icon vocabulary are the
 * shared ones — the same glyph set the viewer's header leads with.
 *
 * Interaction budget, deliberately small: navigate in, navigate up or to any
 * ancestor via the breadcrumb, open a file, upload into the current directory,
 * download one file, and expose the file tools and action sheets. The old
 * client additionally had a sort menu, a "go to path" dialog, a
 * symlink-resolution toggle and a truncated-listing banner; none of them are
 * here (see [sortEntries] for the sort decision). Mutating actions stay behind
 * explicit forms and confirmations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    state: FileExplorerUiState,
    onBack: () -> Unit,
    onUp: () -> Unit,
    onOpenDirectory: (SftpEntry) -> Unit,
    onOpenFile: (SftpEntry) -> Unit,
    onNavigateTo: (String) -> Unit,
    onUpload: () -> Unit,
    onDownload: (SftpEntry) -> Unit,
    onDismissTransfer: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
    onOpenTools: () -> Unit = {},
    onDismissTools: () -> Unit = {},
    onOpenActions: (SftpEntry) -> Unit = {},
    onDismissActions: () -> Unit = {},
    onCopyPath: (String) -> Unit = {},
    onOpenCreateFolder: () -> Unit = {},
    onCreateFolderNameChange: (String) -> Unit = {},
    onCreateFolder: () -> Unit = {},
    onDismissCreateFolder: () -> Unit = {},
    onOpenRename: (SftpEntry) -> Unit = {},
    onRenameNameChange: (String) -> Unit = {},
    onRename: () -> Unit = {},
    onDismissRename: () -> Unit = {},
    onRequestDelete: (SftpEntry) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onDismissDelete: () -> Unit = {},
    onOpenTransfers: () -> Unit = {},
    onDismissTransfers: () -> Unit = {},
    onRetryTransfer: (Long) -> Unit = {},
    onNewTextFile: () -> Unit = {},
    onNewTextFileNameChange: (String) -> Unit = {},
    onCreateNewTextFile: () -> Unit = {},
    onDismissNewTextFile: () -> Unit = {},
    onDismissOperationMessage: () -> Unit = {},
) {
    val scrollPositions = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    val savedPosition = scrollPositions[state.path]
    val listState = remember(state.path) {
        LazyListState(
            firstVisibleItemIndex = savedPosition?.first ?: 0,
            firstVisibleItemScrollOffset = savedPosition?.second ?: 0,
        )
    }
    LaunchedEffect(state.path, listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { position ->
            scrollPositions[state.path] = position
        }
    }
    if (state.transfersVisible) {
        TransfersScreen(
            state = state,
            onBack = onDismissTransfers,
            onRetry = onRetryTransfer,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(FILE_EXPLORER_TAG),
    ) {
        ScreenHeader(
            title = "Files",
            subtitle = fileLocationSubtitle(state.hostName, state.path),
            onBack = onBack,
            trailing = {
                KebabTrigger(
                    contentDescription = "File tools",
                    onClick = onOpenTools,
                    triggerTestTag = FILE_EXPLORER_ACTIONS_TAG,
                )
            },
        )

        CrumbBar(crumbs = state.crumbs, onNavigateTo = onNavigateTo)

        TransferBanner(transfer = state.transfer, onDismiss = onDismissTransfer)

        state.failure?.let { failure ->
            Banner(
                text = failure,
                role = BannerRole.Error,
                maxLines = 4,
                trailingContent = {
                    PocketShellButton(
                        text = "Retry",
                        onClick = onRetry,
                        variant = ButtonVariant.Text,
                        compact = true,
                    )
                },
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(FILE_EXPLORER_ERROR_TAG),
            )
        }

        state.operationMessage?.let { message ->
            Banner(
                text = message,
                role = BannerRole.Info,
                trailingContent = {
                    PocketShellButton(
                        text = "Dismiss",
                        onClick = onDismissOperationMessage,
                        variant = ButtonVariant.Text,
                        compact = true,
                    )
                },
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm),
            )
        }

        when {
            state.loading && !state.loaded -> EmptyState(
                title = "Opening…",
                description = "Reading the directory over SFTP.",
                modifier = Modifier
                    .weight(1f)
                    .testTag(FILE_EXPLORER_LOADING_TAG),
            )

            state.isEmptyAndHealthy -> EmptyState(
                title = "Empty folder",
                description = "Nothing in ${state.path}.",
                modifier = Modifier
                    .weight(1f)
                    .testTag(FILE_EXPLORER_EMPTY_TAG),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .testTag(FILE_EXPLORER_LIST_TAG),
                state = listState,
                contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            ) {
                items(state.entries, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        nowMs = nowMs,
                        transferring = state.transferring,
                        onOpen = { if (entry.isDirectory) onOpenDirectory(entry) else onOpenFile(entry) },
                        onDownload = { onDownload(entry) },
                        onOpenActions = { onOpenActions(entry) },
                    )
                }
            }
        }
    }

    if (state.toolsVisible) {
        FileToolsSheet(
            state = state,
            onUp = {
                onDismissTools()
                onUp()
            },
            onUpload = {
                onDismissTools()
                onUpload()
            },
            onCreateFolder = {
                onDismissTools()
                onOpenCreateFolder()
            },
            onNewTextFile = {
                onDismissTools()
                onNewTextFile()
            },
            onOpenTransfers = {
                onDismissTools()
                onOpenTransfers()
            },
            onDismiss = onDismissTools,
        )
    }

    state.actionEntry?.let { entry ->
        FileActionSheet(
            entry = entry,
            onPreview = {
                onDismissActions()
                if (!entry.isDirectory) onOpenFile(entry)
            },
            onEdit = {
                onDismissActions()
                if (!entry.isDirectory) onOpenFile(entry)
            },
            onDownload = {
                onDismissActions()
                onDownload(entry)
            },
            onCopyPath = {
                onDismissActions()
                onCopyPath(entry.path)
            },
            onRename = {
                onDismissActions()
                onOpenRename(entry)
            },
            onDelete = {
                onDismissActions()
                onRequestDelete(entry)
            },
            onDismiss = onDismissActions,
        )
    }

    if (state.createFolder.visible) {
        CreateFolderSheet(
            state = state.createFolder,
            parent = state.path,
            onNameChange = onCreateFolderNameChange,
            onCreate = onCreateFolder,
            onDismiss = onDismissCreateFolder,
        )
    }

    if (state.newTextFile.visible) {
        NewTextFileSheet(
            state = state.newTextFile,
            parent = state.path,
            onNameChange = onNewTextFileNameChange,
            onCreate = onCreateNewTextFile,
            onDismiss = onDismissNewTextFile,
        )
    }

    if (state.renameFile.visible) {
        RenameFileSheet(
            state = state.renameFile,
            parent = state.renameFile.entry?.let { RemotePath.parent(it.path) }.orEmpty(),
            onNameChange = onRenameNameChange,
            onRename = onRename,
            onDismiss = onDismissRename,
        )
    }

    state.deleteFile.entry?.let { entry ->
        DeleteFileDialog(
            entry = entry,
            parent = RemotePath.parent(entry.path),
            submitting = state.deleteFile.submitting,
            failure = state.deleteFile.failure,
            onConfirm = onConfirmDelete,
            onDismiss = onDismissDelete,
        )
    }
}

/**
 * The ancestor trail. Horizontally scrollable rather than truncated: a deep
 * path (`/home/alexey/git/pocketshell/app2/src/main`) has to stay fully
 * reachable, and eliding the middle is exactly the part a developer taps.
 */
@Composable
private fun CrumbBar(crumbs: List<RemotePath.Crumb>, onNavigateTo: (String) -> Unit) {
    if (crumbs.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface)
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellSpacing.sm,
            )
            .testTag(FILE_EXPLORER_CRUMBS_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            val isLast = index == crumbs.lastIndex
            Box(
                modifier = Modifier
                    .sizeIn(
                        minWidth = PocketShellDensity.tapTargetMin,
                        minHeight = PocketShellDensity.tapTargetMin,
                    )
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Navigate to ${crumb.path}",
                        onClick = { onNavigateTo(crumb.path) },
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = crumb.label
                    }
                    .testTag(crumbTag(crumb.path)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = crumb.label,
                    color = if (isLast) PocketShellColors.Text else PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyMono,
                    fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
            if (!isLast) {
                Icon(
                    imageVector = PocketShellIcons.Chevron,
                    contentDescription = null,
                    tint = PocketShellColors.TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TransferBanner(transfer: TransferState, onDismiss: () -> Unit) {
    val (text, role) = when (transfer) {
        TransferState.Idle -> return
        is TransferState.Running ->
            (if (transfer.uploading) "Uploading ${transfer.name}…" else "Downloading ${transfer.name}…") to
                BannerRole.Info

        is TransferState.Done -> transfer.message to BannerRole.Info
        is TransferState.Failed -> transfer.message to BannerRole.Error
    }
    Banner(
        text = text,
        role = role,
        maxLines = 4,
        trailingContent = {
            if (transfer !is TransferState.Running) {
                PocketShellButton(
                    text = "Dismiss",
                    onClick = onDismiss,
                    variant = ButtonVariant.Text,
                    compact = true,
                )
            }
        },
        modifier = Modifier
            .padding(horizontal = PocketShellSpacing.md)
            .padding(bottom = PocketShellSpacing.sm)
            .testTag(FILE_EXPLORER_TRANSFER_TAG),
    )
}

@Composable
private fun FileRow(
    entry: SftpEntry,
    nowMs: Long,
    transferring: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onOpenActions: () -> Unit,
) {
    ListRow(
        title = entry.name,
        subtitle = rowSubtitle(entry, nowMs),
        onClick = onOpen,
        modifier = Modifier.testTag(fileRowTag(entry.name)),
        leading = { FileTypeIcon(iconClass = iconClassFor(entry)) },
        trailing = {
            if (!entry.isDirectory) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PocketShellButton(
                        text = "Download",
                        onClick = onDownload,
                        variant = ButtonVariant.Text,
                        compact = true,
                        enabled = !transferring,
                        modifier = Modifier.testTag(fileDownloadTag(entry.name)),
                    )
                    KebabTrigger(
                        contentDescription = "Actions for ${entry.name}",
                        onClick = onOpenActions,
                        triggerTestTag = fileActionsTag(entry.name),
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = PocketShellIcons.Chevron,
                        contentDescription = null,
                        tint = PocketShellColors.TextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    KebabTrigger(
                        contentDescription = "Actions for ${entry.name}",
                        onClick = onOpenActions,
                        triggerTestTag = fileActionsTag(entry.name),
                    )
                }
            }
        },
    )
}

/** The overflow sheet for the current directory (design-kit frame 53). */
@Composable
private fun FileToolsSheet(
    state: FileExplorerUiState,
    onUp: () -> Unit,
    onUpload: () -> Unit,
    onCreateFolder: () -> Unit,
    onNewTextFile: () -> Unit,
    onOpenTransfers: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = PocketShellShapes.large,
        modifier = Modifier.testTag(FILE_EXPLORER_TOOLS_SHEET_TAG),
        containerColor = PocketShellColors.Surface,
    ) {
        FileToolsSheetContent(
            path = state.path,
            onUp = onUp,
            canGoUp = state.path.isNotBlank() && state.path != RemotePath.ROOT,
            canUpload = state.loaded && !state.transferring,
            onUpload = onUpload,
            onCreateFolder = onCreateFolder,
            onNewTextFile = onNewTextFile,
            onOpenTransfers = onOpenTransfers,
            onDismiss = onDismiss,
        )
    }
}

/** Content-only version used by the host-JVM tests and design renders. */
@Composable
internal fun FileToolsSheetContent(
    path: String,
    onUp: () -> Unit = {},
    canGoUp: Boolean = path.isNotBlank() && path != RemotePath.ROOT,
    canUpload: Boolean = true,
    onUpload: () -> Unit,
    onCreateFolder: () -> Unit,
    onNewTextFile: () -> Unit,
    onOpenTransfers: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = PocketShellSpacing.lg)
            .testTag(FILE_EXPLORER_TOOLS_SHEET_TAG),
    ) {
        SheetHeader(
            title = "File tools",
            subtitle = path,
            modifier = Modifier.padding(
                horizontal = PocketShellSpacing.lg,
                vertical = PocketShellSpacing.sm,
            ),
            onClose = onDismiss,
        )
        FileToolRow(
            title = "Up to parent",
            subtitle = "Stay in Files and browse the parent folder",
            icon = PocketShellIcons.Up,
            onClick = onUp,
            enabled = canGoUp,
            testTag = FILE_EXPLORER_UP_TAG,
        )
        FileToolRow(
            title = "Upload files",
            subtitle = "Android document picker",
            icon = PocketShellIcons.Upload,
            onClick = onUpload,
            enabled = canUpload,
            testTag = FILE_EXPLORER_UPLOAD_TAG,
        )
        FileToolRow(
            title = "Create folder",
            subtitle = "In the current remote folder",
            icon = PocketShellIcons.Folder,
            onClick = onCreateFolder,
            enabled = path.isNotBlank(),
            testTag = FILE_EXPLORER_CREATE_FOLDER_TAG,
        )
        FileToolRow(
            title = "New text file",
            subtitle = "Open an empty remote file in the editor",
            icon = PocketShellIcons.File,
            onClick = onNewTextFile,
            enabled = path.isNotBlank(),
            testTag = FILE_EXPLORER_NEW_TEXT_FILE_TAG,
        )
        FileToolRow(
            title = "Transfers",
            subtitle = "Upload and download history",
            icon = PocketShellIcons.Ports,
            onClick = onOpenTransfers,
            testTag = FILE_EXPLORER_TRANSFERS_TAG,
        )
        // The explorer has one stable ordering policy and already shows dotfiles
        // returned by SFTP. Keep the two design-kit affordances visible as honest
        // state descriptions rather than adding a second sort/filter model.
        FileToolRow(
            title = "Sort by name",
            subtitle = "Folders first · alphabetical",
            icon = PocketShellIcons.Sliders,
            onClick = onDismiss,
        )
        FileToolRow(
            title = "Show hidden files",
            subtitle = "Hidden entries are included",
            icon = PocketShellIcons.Eye,
            onClick = onDismiss,
        )
    }
}

@Composable
private fun FileToolRow(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick.takeIf { enabled },
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) PocketShellColors.TextSecondary else PocketShellColors.TextMuted,
                modifier = Modifier.size(20.dp),
            )
        },
        modifier = (testTag?.let { Modifier.testTag(it) } ?: Modifier),
    )
}

/** Actions for one selected file or folder (design-kit frame 55). */
@Composable
private fun FileActionSheet(
    entry: SftpEntry,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onDownload: () -> Unit,
    onCopyPath: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = PocketShellShapes.large,
        modifier = Modifier.testTag(FILE_EXPLORER_FILE_ACTIONS_TAG),
        containerColor = PocketShellColors.Surface,
    ) {
        FileActionSheetContent(
            entry = entry,
            onPreview = onPreview,
            onEdit = onEdit,
            onDownload = onDownload,
            onCopyPath = onCopyPath,
            onRename = onRename,
            onDelete = onDelete,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun FileActionSheetContent(
    entry: SftpEntry,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onDownload: () -> Unit,
    onCopyPath: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = PocketShellSpacing.lg)
            .testTag(FILE_EXPLORER_FILE_ACTIONS_TAG),
    ) {
        SheetHeader(
            title = entry.name,
            subtitle = entry.path,
            modifier = Modifier.padding(
                horizontal = PocketShellSpacing.lg,
                vertical = PocketShellSpacing.sm,
            ),
            onClose = onDismiss,
        )
        if (!entry.isDirectory) {
            FileToolRow("Preview", icon = PocketShellIcons.Eye, onClick = onPreview)
            if (isLikelyEditableFile(entry.name)) {
                FileToolRow("Edit", icon = PocketShellIcons.Edit, onClick = onEdit)
            }
            FileToolRow("Download", icon = PocketShellIcons.Download, onClick = onDownload)
        }
        FileToolRow("Copy path", icon = PocketShellIcons.Copy, onClick = onCopyPath)
        FileToolRow("Rename", icon = PocketShellIcons.Edit, onClick = onRename)
        FileToolRow(
            title = "Delete…",
            icon = PocketShellIcons.Trash,
            onClick = onDelete,
            testTag = FILE_EXPLORER_DELETE_TAG,
        )
    }
}

/** Create-folder form (design-kit frame 54). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateFolderSheet(
    state: CreateFolderUiState,
    parent: String,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = PocketShellShapes.large,
        modifier = Modifier.testTag(FILE_EXPLORER_CREATE_FOLDER_TAG),
        containerColor = PocketShellColors.Surface,
    ) {
        CreateFolderSheetContent(
            state = state,
            parent = parent,
            onNameChange = onNameChange,
            onCreate = onCreate,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun CreateFolderSheetContent(
    state: CreateFolderUiState,
    parent: String,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = PocketShellColors.Text,
        unfocusedTextColor = PocketShellColors.Text,
        focusedBorderColor = PocketShellColors.Accent,
        unfocusedBorderColor = PocketShellColors.BorderSoft,
        focusedLabelColor = PocketShellColors.Accent,
        unfocusedLabelColor = PocketShellColors.TextSecondary,
        cursorColor = PocketShellColors.Accent,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = PocketShellSpacing.lg)
            .padding(top = PocketShellSpacing.lg, bottom = PocketShellSpacing.lg)
            .testTag(FILE_EXPLORER_CREATE_FOLDER_TAG),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
    ) {
        SheetHeader(title = "Create folder", subtitle = parent, onClose = onDismiss)
        state.failure?.let { failure ->
            Banner(text = failure, role = BannerRole.Error)
        }
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text("Folder name") },
            singleLine = true,
            enabled = !state.submitting,
            colors = fieldColors,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(FILE_EXPLORER_CREATE_FOLDER_NAME_TAG),
        )
        Text(
            text = "Creates ${RemotePath.join(parent, state.name.ifBlank { "folder" })} on the host.",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.metadata,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            PocketShellButton(
                text = "Cancel",
                onClick = onDismiss,
                variant = ButtonVariant.Text,
                enabled = !state.submitting,
            )
            PocketShellButton(
                text = if (state.submitting) "Creating…" else "Create folder",
                onClick = onCreate,
                variant = ButtonVariant.Primary,
                enabled = !state.submitting,
                modifier = Modifier.testTag(FILE_EXPLORER_CREATE_FOLDER_CONFIRM_TAG),
            )
        }
    }
}

/** Create-empty-text-file form; success immediately opens the remote editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewTextFileSheet(
    state: NewTextFileUiState,
    parent: String,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = PocketShellShapes.large,
        modifier = Modifier.testTag(FILE_EXPLORER_NEW_TEXT_FILE_SHEET_TAG),
        containerColor = PocketShellColors.Surface,
    ) {
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = PocketShellColors.Text,
            unfocusedTextColor = PocketShellColors.Text,
            focusedBorderColor = PocketShellColors.Accent,
            unfocusedBorderColor = PocketShellColors.BorderSoft,
            focusedLabelColor = PocketShellColors.Accent,
            unfocusedLabelColor = PocketShellColors.TextSecondary,
            cursorColor = PocketShellColors.Accent,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(top = PocketShellSpacing.lg, bottom = PocketShellSpacing.lg)
                .testTag(FILE_EXPLORER_NEW_TEXT_FILE_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        ) {
            SheetHeader(title = "New text file", subtitle = parent, onClose = onDismiss)
            state.failure?.let { failure ->
                Banner(text = failure, role = BannerRole.Error)
            }
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text("File name") },
                singleLine = true,
                enabled = !state.submitting,
                colors = fieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(FILE_EXPLORER_NEW_TEXT_FILE_NAME_TAG),
            )
            Text(
                text = "Creates an empty UTF-8 file on the host, then opens it in the editor.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.metadata,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                PocketShellButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    variant = ButtonVariant.Text,
                    enabled = !state.submitting,
                )
                PocketShellButton(
                    text = if (state.submitting) "Creating…" else "Create and edit",
                    onClick = onCreate,
                    variant = ButtonVariant.Primary,
                    enabled = !state.submitting,
                    modifier = Modifier.testTag(FILE_EXPLORER_NEW_TEXT_FILE_CONFIRM_TAG),
                )
            }
        }
    }
}

/** Rename form (design-kit frame 61). */
@Composable
private fun RenameFileSheet(
    state: RenameFileUiState,
    parent: String,
    onNameChange: (String) -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = PocketShellShapes.large,
        modifier = Modifier.testTag(FILE_EXPLORER_RENAME_TAG),
        containerColor = PocketShellColors.Surface,
    ) {
        RenameFileSheetContent(
            state = state,
            parent = parent,
            onNameChange = onNameChange,
            onRename = onRename,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun RenameFileSheetContent(
    state: RenameFileUiState,
    parent: String,
    onNameChange: (String) -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = PocketShellColors.Text,
        unfocusedTextColor = PocketShellColors.Text,
        focusedBorderColor = PocketShellColors.Accent,
        unfocusedBorderColor = PocketShellColors.BorderSoft,
        focusedLabelColor = PocketShellColors.Accent,
        unfocusedLabelColor = PocketShellColors.TextSecondary,
        cursorColor = PocketShellColors.Accent,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = PocketShellSpacing.lg)
            .padding(top = PocketShellSpacing.lg, bottom = PocketShellSpacing.lg)
            .testTag(FILE_EXPLORER_RENAME_TAG),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
    ) {
        SheetHeader(title = "Rename file", onClose = onDismiss)
        state.failure?.let { failure ->
            Banner(text = failure, role = BannerRole.Error)
        }
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text("File name") },
            singleLine = true,
            enabled = !state.submitting,
            colors = fieldColors,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(FILE_EXPLORER_RENAME_NAME_TAG),
        )
        Text(
            text = "In $parent",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.metadata,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            PocketShellButton(
                text = "Cancel",
                onClick = onDismiss,
                variant = ButtonVariant.Text,
                enabled = !state.submitting,
            )
            PocketShellButton(
                text = if (state.submitting) "Renaming…" else "Rename",
                onClick = onRename,
                variant = ButtonVariant.Primary,
                enabled = !state.submitting,
                modifier = Modifier.testTag(FILE_EXPLORER_RENAME_CONFIRM_TAG),
            )
        }
    }
}

/** Explicit destructive confirmation (design-kit frame 62). */
@Composable
private fun DeleteFileDialog(
    entry: SftpEntry,
    parent: String,
    submitting: Boolean,
    failure: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = buildString {
        append("This permanently deletes ${entry.name} from $parent on the host. ")
        append("There is no undo.")
        if (failure != null) append("\n\n$failure")
    }
    ConfirmDialog(
        title = "Delete ${entry.name}?",
        message = message,
        confirmLabel = if (submitting) "Deleting…" else "Delete file",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        confirmTestTag = FILE_EXPLORER_DELETE_CONFIRM_TAG,
        modifier = Modifier.testTag(FILE_EXPLORER_DELETE_TAG),
    )
}

/** Known binary/image suffixes are hidden from the text editor action. */
private fun isLikelyEditableFile(name: String): Boolean {
    val extension = FileKindDetector.extensionOf(name)
    return extension !in setOf(
        "7z", "apk", "bin", "bmp", "class", "dmg", "gif", "gz", "ico", "iso",
        "jar", "jpeg", "jpg", "mp3", "mp4", "pdf", "png", "so", "tar", "wav", "webp", "zip",
    )
}

/** Folders get the folder glyph; files route through ui-kit's shared name map. */
internal fun iconClassFor(entry: SftpEntry): FileIconClass =
    if (entry.isDirectory) FileIconClass.FOLDER else fileIconClassForName(entry.name)

/**
 * The secondary line of a row: size for a file, and a relative modified time
 * when the server reported one.
 *
 * A directory shows no size because SFTP's size for a directory is the inode's,
 * not the tree's — printing "4.0 KB" next to a folder holding a gigabyte is a
 * lie the old client also told.
 */
internal fun rowSubtitle(entry: SftpEntry, nowMs: Long): String? {
    val parts = buildList {
        if (!entry.isDirectory) add(formatSize(entry.sizeBytes))
        relativeTime(entry.modifiedEpochMs, nowMs)?.let { add(it) }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

/**
 * "3m ago" / "2d ago" for [epochMs], or null when the server sent no mtime
 * (which [com.pocketshell.core.transport.SftpEntry] reports as 0) or when the
 * timestamp is in the future — a clock-skewed host must not render "-4h ago".
 */
internal fun relativeTime(epochMs: Long, nowMs: Long): String? {
    if (epochMs <= 0L) return null
    val deltaMs = nowMs - epochMs
    if (deltaMs < 0L) return null
    val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
    val hours = TimeUnit.MILLISECONDS.toHours(deltaMs)
    val days = TimeUnit.MILLISECONDS.toDays(deltaMs)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 365 -> "${days}d ago"
        else -> "${days / 365}y ago"
    }
}

/** What the SAF picker told us about the chosen document. */
internal data class PickedDocument(val name: String, val size: Long)

/**
 * Reads the display name and size out of a document provider's cursor.
 *
 * Extracted from the launcher (which owns the `ContentResolver`) so the
 * "provider reported nothing / reported a path / reported no size" branches are
 * unit-testable without Android's content framework: [queryColumns] returns the
 * raw column values, so a test supplies them directly.
 */
internal fun describeDocument(
    queryColumns: (List<String>) -> Map<String, String?>?,
    fallbackName: String,
): PickedDocument {
    val columns = runCatching {
        queryColumns(listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
    }.getOrNull().orEmpty()
    val name = columns[OpenableColumns.DISPLAY_NAME]?.takeIf { it.isNotBlank() } ?: fallbackName
    val size = columns[OpenableColumns.SIZE]?.toLongOrNull() ?: -1L
    return PickedDocument(name = sanitizeUploadName(name), size = size)
}
