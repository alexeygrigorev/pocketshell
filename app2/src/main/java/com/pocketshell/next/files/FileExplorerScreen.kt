package com.pocketshell.next.files

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.core.transport.SftpEntry
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.FileIconClass
import com.pocketshell.uikit.components.FileTypeIcon
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.fileIconClassForName
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import java.util.concurrent.TimeUnit

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

fun fileRowTag(name: String): String = "file-row-$name"

fun fileDownloadTag(name: String): String = "file-download-$name"

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
        onDismissTransfer = viewModel::dismissTransfer,
        onRetry = viewModel::refresh,
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
 * download one file. The old client additionally had a sort menu, a "go to
 * path" dialog, a symlink-resolution toggle and a truncated-listing banner; none
 * of them are here (see [sortEntries] for the sort decision). Create/rename/
 * delete are not offered at all — [com.pocketshell.core.transport.SftpChannel]
 * has the verbs, but a destructive action on a phone needs a confirm-and-undo
 * design that has not been done.
 */
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
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(FILE_EXPLORER_TAG),
    ) {
        ScreenHeader(
            title = state.path.takeIf { it.isNotBlank() }?.let { RemotePath.nameOf(it) } ?: "Files",
            subtitle = state.path.ifBlank { "Opening…" },
            leading = {
                PocketShellButton(
                    text = "Back",
                    onClick = onBack,
                    variant = ButtonVariant.Text,
                    compact = true,
                )
            },
            trailing = {
                Row {
                    PocketShellButton(
                        text = "Up",
                        onClick = onUp,
                        variant = ButtonVariant.Text,
                        compact = true,
                        enabled = state.path.isNotBlank() && state.path != RemotePath.ROOT,
                        modifier = Modifier.testTag(FILE_EXPLORER_UP_TAG),
                    )
                    PocketShellButton(
                        text = "Upload",
                        onClick = onUpload,
                        variant = ButtonVariant.Primary,
                        compact = true,
                        enabled = state.loaded && !state.transferring,
                        modifier = Modifier.testTag(FILE_EXPLORER_UPLOAD_TAG),
                    )
                }
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
                contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            ) {
                items(state.entries, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        nowMs = nowMs,
                        transferring = state.transferring,
                        onOpen = { if (entry.isDirectory) onOpenDirectory(entry) else onOpenFile(entry) },
                        onDownload = { onDownload(entry) },
                    )
                }
            }
        }
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
            Text(
                text = crumb.label,
                color = if (isLast) PocketShellColors.Text else PocketShellColors.TextSecondary,
                style = PocketShellType.bodyMono,
                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clickable { onNavigateTo(crumb.path) }
                    .padding(horizontal = PocketShellSpacing.xs, vertical = PocketShellSpacing.xs)
                    .testTag(crumbTag(crumb.path)),
            )
            if (!isLast) {
                Text(
                    text = "›",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.bodyMono,
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
) {
    ListRow(
        title = entry.name,
        subtitle = rowSubtitle(entry, nowMs),
        onClick = onOpen,
        modifier = Modifier.testTag(fileRowTag(entry.name)),
        leading = { FileTypeIcon(iconClass = iconClassFor(entry)) },
        trailing = {
            if (!entry.isDirectory) {
                PocketShellButton(
                    text = "Save",
                    onClick = onDownload,
                    variant = ButtonVariant.Text,
                    compact = true,
                    enabled = !transferring,
                    modifier = Modifier.testTag(fileDownloadTag(entry.name)),
                )
            } else {
                Text(
                    text = "›",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.bodyDense,
                    overflow = TextOverflow.Clip,
                )
            }
        },
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
