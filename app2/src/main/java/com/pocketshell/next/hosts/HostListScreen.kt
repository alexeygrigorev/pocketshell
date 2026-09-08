package com.pocketshell.next.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.next.release.UpdateCheckViewModel
import com.pocketshell.next.release.launchUpdateUrl
import com.pocketshell.next.release.updateAvailableBannerText
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stable test tags. The list container plus one tag per row, keyed by host id,
 * so a journey can tap a specific host without matching on user-visible copy.
 */
const val HOST_LIST_TAG: String = "host-list"
const val HOST_LIST_ADD_TAG: String = "host-list-add"
const val HOST_LIST_SCAN_TAG: String = "host-list-scan"
const val HOST_LIST_SETTINGS_TAG: String = "host-list-settings"
const val HOST_LIST_UPDATE_BANNER_TAG: String = "host-list-update-banner"
const val HOST_LIST_UPDATE_DOWNLOAD_TAG: String = "host-list-update-download"
const val HOST_LIST_UPDATE_NOTES_TAG: String = "host-list-update-notes"
const val HOST_LIST_UPDATE_DISMISS_TAG: String = "host-list-update-dismiss"
const val HOST_LIST_UPDATE_RETRY_TAG: String = "host-list-update-retry"
const val HOST_LIST_UPDATE_FAILURE_TAG: String = "host-list-update-failure"
const val HOST_LIST_KEYS_TAG: String = "host-list-ssh-keys"
// Keep the pre-Quiet journey tag as the canonical semantics tag. The longer
// name remains a source-compatible alias for host-list tests and callers.
const val HOST_LIST_SETTINGS_ROW_TAG: String = HOST_LIST_SETTINGS_TAG
const val HOST_LIST_ADD_FOOTER_TAG: String = HOST_LIST_ADD_TAG
const val HOST_LIST_ADD_METHODS_TAG: String = "host-list-add-methods"
const val HOST_LIST_ADD_SCAN_TAG: String = "host-list-add-scan"
const val HOST_LIST_ADD_DETAILS_TAG: String = "host-list-add-details"

fun hostRowTag(hostId: Long): String = "host-row-$hostId"

fun hostRowMenuTag(hostId: Long): String = "host-row-menu-$hostId"

/**
 * Route-level entry point: binds the Hilt-provided [HostListViewModel] to the
 * stateless [HostListScreen].
 *
 * The split exists so the screen can be rendered from a test (or a design
 * render) with a hand-built state and no DI graph, which is what keeps the
 * screen itself free of `remember`-ed side state.
 */
@Composable
fun HostListRoute(
    onOpenHost: (Long) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (Long) -> Unit,
    onScanQr: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSshKeys: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HostListViewModel = hiltViewModel(),
    updateCheckViewModel: UpdateCheckViewModel? = null,
) {
    val state by viewModel.state.collectAsState()
    val available by collectOrNull(updateCheckViewModel?.available)
    val failed by collectOrNull(updateCheckViewModel?.failed)
    val context = LocalContext.current
    val info = available
    val failure = failed
    val notice = when {
        info != null -> HostListUpdateNotice.Available(
            text = updateAvailableBannerText(
                info,
                updateCheckViewModel?.installedVersionLabel() ?: "",
            ),
            apkUrl = info.apkUrl,
            htmlUrl = info.htmlUrl,
        )
        failure != null -> HostListUpdateNotice.Failed(failure)
        else -> null
    }
    HostListScreen(
        state = state,
        onOpenHost = onOpenHost,
        onAddHost = onAddHost,
        onEditHost = onEditHost,
        onScanQr = onScanQr,
        onOpenSettings = onOpenSettings,
        onOpenSshKeys = onOpenSshKeys,
        onDeleteHost = viewModel::delete,
        modifier = modifier,
        updateNotice = notice,
        onDownloadUpdate = { url -> launchUpdateUrl(context, url) },
        onOpenReleaseNotes = { url -> launchUpdateUrl(context, url) },
        onDismissUpdate = { updateCheckViewModel?.dismissUpdate() },
        onRetryUpdateCheck = { updateCheckViewModel?.refreshNow() },
        onDismissUpdateFailure = { updateCheckViewModel?.dismissFailure() },
    )
}

@Composable
private fun <T> collectOrNull(flow: StateFlow<T?>?): androidx.compose.runtime.State<T?> {
    val fallback = remember { MutableStateFlow(null as T?) }
    return (flow ?: fallback).collectAsState()
}

/** In-app update surface on the host list (issue #2531). */
sealed interface HostListUpdateNotice {
    data class Available(
        val text: String,
        val apkUrl: String,
        val htmlUrl: String,
    ) : HostListUpdateNotice
    data class Failed(val reason: String) : HostListUpdateNotice
}

/**
 * The saved-host list — app2's landing screen (plan §U-1, extended by §P-6).
 *
 * The list itself is still a read-only projection of the `hosts` table: what
 * Room emits is what the screen paints, so there is no second source of truth
 * to reconcile, and there are still no status dots or bootstrap probes here.
 * What P-6 adds is the *management* surface it was missing — a fresh install had
 * literally no way to get a host into the table:
 *
 * - The empty state has one **Add host** action that opens the two real setup
 *   methods. Populated Hosts keeps setup in a full-width footer and puts **SSH
 *   keys** and **Settings** in a separate tools section.
 * - A per-row [Kebab] with Edit / Delete. It sits in the trailing slot the
 *   navigation chevron used to occupy: the row's own tap still dials the host,
 *   and a menu tap does not (an inner clickable consumes it). Share QR was
 *   removed (issue #2523); QR import remains available through Add host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    state: HostListUiState,
    onOpenHost: (Long) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (Long) -> Unit,
    onScanQr: () -> Unit,
    onOpenSettings: () -> Unit,
    onDeleteHost: (Long) -> Unit,
    onOpenSshKeys: () -> Unit = {},
    modifier: Modifier = Modifier,
    updateNotice: HostListUpdateNotice? = null,
    onDownloadUpdate: (apkUrl: String) -> Unit = {},
    onOpenReleaseNotes: (htmlUrl: String) -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    onRetryUpdateCheck: () -> Unit = {},
    onDismissUpdateFailure: () -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<HostRow?>(null) }
    var showAddHostMethods by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = "Hosts")

        when (val notice = updateNotice) {
            is HostListUpdateNotice.Available -> UpdateAvailableBanner(
                notice = notice,
                onDownload = { onDownloadUpdate(notice.apkUrl) },
                onNotes = { onOpenReleaseNotes(notice.htmlUrl) },
                onDismiss = onDismissUpdate,
            )
            is HostListUpdateNotice.Failed -> UpdateCheckFailedBanner(
                reason = notice.reason,
                onRetry = onRetryUpdateCheck,
                onDismiss = onDismissUpdateFailure,
            )
            null -> Unit
        }

        when {
            // Nothing painted until Room's first emission: showing the empty
            // state during the query would flash "No hosts yet" at every cold
            // launch of an install that has hosts.
            !state.loaded -> Unit

            state.hosts.isEmpty() -> EmptyState(
                title = "Your work, from here.",
                description = "Connect to a development machine to open its workspaces and terminals.",
                action = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
                    ) {
                        PocketShellButton(
                            text = "Add host",
                            onClick = { showAddHostMethods = true },
                            modifier = Modifier.testTag(HOST_LIST_ADD_FOOTER_TAG),
                        )
                        PocketShellButton(
                            text = "Settings",
                            onClick = onOpenSettings,
                            variant = ButtonVariant.Text,
                            compact = true,
                            modifier = Modifier.testTag(HOST_LIST_SETTINGS_TAG),
                        )
                    }
                },
            )

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(HOST_LIST_TAG),
                    contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
                ) {
                    item { SectionHeader(label = "Hosts", count = state.hosts.size) }
                    items(items = state.hosts, key = { it.id }) { host ->
                        ListRow(
                            title = host.name,
                            subtitle = host.subtitle,
                            trailing = {
                                Kebab(
                                    items = listOf(
                                        KebabItem(label = "Edit", onClick = { onEditHost(host.id) }),
                                        KebabItem(label = "Delete", onClick = { pendingDelete = host }),
                                    ),
                                    triggerTestTag = hostRowMenuTag(host.id),
                                )
                            },
                            onClick = { onOpenHost(host.id) },
                            modifier = Modifier.testTag(hostRowTag(host.id)),
                        )
                    }
                    item { SectionHeader(label = "Tools") }
                    item {
                        ListRow(
                            title = "SSH keys",
                            subtitle = "Manage device authentication",
                            onClick = onOpenSshKeys,
                            modifier = Modifier.testTag(HOST_LIST_KEYS_TAG),
                        )
                    }
                    item {
                        ListRow(
                            title = "Settings",
                            subtitle = "Connection and app preferences",
                            onClick = onOpenSettings,
                            modifier = Modifier.testTag(HOST_LIST_SETTINGS_ROW_TAG),
                        )
                    }
                    item {
                        PocketShellButton(
                            text = "Add host",
                            onClick = { showAddHostMethods = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.md)
                                .testTag(HOST_LIST_ADD_FOOTER_TAG),
                        )
                    }
                }
            }
        }
    }

    if (showAddHostMethods) {
        AddHostMethodSheet(
            onScanQr = {
                showAddHostMethods = false
                onScanQr()
            },
            onEnterDetails = {
                showAddHostMethods = false
                onAddHost()
            },
            onDismiss = { showAddHostMethods = false },
        )
    }

    pendingDelete?.let { host ->
        ConfirmDialog(
            title = "Delete ${host.name}?",
            message = "${host.subtitle} is removed from this device. " +
                "The SSH key stays; the server is not touched.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                onDeleteHost(host.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * The two real entry points into host setup. It deliberately has no standalone
 * primary action: choosing a row is the action, which keeps the sheet from
 * becoming a second form or a preview-only branch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddHostMethodSheet(
    onScanQr: () -> Unit,
    onEnterDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PocketShellColors.Surface,
        shape = PocketShellShapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HOST_LIST_ADD_METHODS_TAG)
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(bottom = PocketShellSpacing.lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            SheetHeader(title = "Add host")
            ListRow(
                title = "Scan a QR code",
                subtitle = "Import from your computer",
                onClick = onScanQr,
                modifier = Modifier.testTag(HOST_LIST_ADD_SCAN_TAG),
            )
            ListRow(
                title = "Enter connection details",
                subtitle = "Address, user and SSH key",
                onClick = onEnterDetails,
                modifier = Modifier.testTag(HOST_LIST_ADD_DETAILS_TAG),
            )
        }
    }
}

@Composable
private fun UpdateAvailableBanner(
    notice: HostListUpdateNotice.Available,
    onDownload: () -> Unit,
    onNotes: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm)
            .testTag(HOST_LIST_UPDATE_BANNER_TAG),
    ) {
        Banner(
            text = notice.text,
            role = BannerRole.Info,
            maxLines = 3,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PocketShellButton(
                text = "Download",
                onClick = onDownload,
                variant = ButtonVariant.Text,
                compact = true,
                modifier = Modifier.testTag(HOST_LIST_UPDATE_DOWNLOAD_TAG),
            )
            PocketShellButton(
                text = "Notes",
                onClick = onNotes,
                variant = ButtonVariant.Text,
                compact = true,
                modifier = Modifier.testTag(HOST_LIST_UPDATE_NOTES_TAG),
            )
            PocketShellButton(
                text = "Dismiss",
                onClick = onDismiss,
                variant = ButtonVariant.Text,
                compact = true,
                modifier = Modifier.testTag(HOST_LIST_UPDATE_DISMISS_TAG),
            )
        }
    }
}

@Composable
private fun UpdateCheckFailedBanner(
    reason: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm)
            .testTag(HOST_LIST_UPDATE_FAILURE_TAG),
    ) {
        Banner(
            text = "Couldn't check for updates ($reason)",
            role = BannerRole.Error,
            maxLines = 3,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PocketShellButton(
                text = "Retry",
                onClick = onRetry,
                variant = ButtonVariant.Text,
                compact = true,
                modifier = Modifier.testTag(HOST_LIST_UPDATE_RETRY_TAG),
            )
            PocketShellButton(
                text = "Dismiss",
                onClick = onDismiss,
                variant = ButtonVariant.Text,
                compact = true,
                modifier = Modifier.testTag(HOST_LIST_UPDATE_DISMISS_TAG),
            )
        }
    }
}
