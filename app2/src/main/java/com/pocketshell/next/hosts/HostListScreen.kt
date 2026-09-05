package com.pocketshell.next.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 * - **Add** and **Scan** in the header, and repeated in the empty state, which
 *   is the only screen a fresh install ever sees. A **Settings** affordance sits
 *   alongside them — the only place in the app that reaches [SettingsScreen]
 *   (deliberately not a mid-session terminal menu action, plan §P-6).
 * - A per-row [Kebab] with Edit / Delete. It sits in the trailing slot the
 *   navigation chevron used to occupy: the row's own tap still dials the host,
 *   and a menu tap does not (an inner clickable consumes it). Share QR was
 *   removed (issue #2523); Scan in the header still imports.
 */
@Composable
fun HostListScreen(
    state: HostListUiState,
    onOpenHost: (Long) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (Long) -> Unit,
    onScanQr: () -> Unit,
    onOpenSettings: () -> Unit,
    onDeleteHost: (Long) -> Unit,
    modifier: Modifier = Modifier,
    updateNotice: HostListUpdateNotice? = null,
    onDownloadUpdate: (apkUrl: String) -> Unit = {},
    onOpenReleaseNotes: (htmlUrl: String) -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    onRetryUpdateCheck: () -> Unit = {},
    onDismissUpdateFailure: () -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<HostRow?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Hosts",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                    PocketShellButton(
                        text = "Scan",
                        onClick = onScanQr,
                        variant = ButtonVariant.Text,
                        compact = true,
                        modifier = Modifier.testTag(HOST_LIST_SCAN_TAG),
                    )
                    PocketShellButton(
                        text = "Add",
                        onClick = onAddHost,
                        variant = ButtonVariant.Primary,
                        compact = true,
                        modifier = Modifier.testTag(HOST_LIST_ADD_TAG),
                    )
                    // The only entry point into Settings anywhere in the app
                    // (plan §P-6's "reachable from the hosts screen, not a
                    // mid-session action"). A third compact Text button matches
                    // the header's existing Scan/Add grammar rather than
                    // introducing a bespoke icon-only affordance ui-kit does
                    // not otherwise use in a `ScreenHeader` trailing slot.
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
                title = "No hosts yet",
                description = "Add one by hand, or scan a QR code from your computer.",
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm)) {
                        PocketShellButton(text = "Add host", onClick = onAddHost)
                        PocketShellButton(
                            text = "Scan QR",
                            onClick = onScanQr,
                            variant = ButtonVariant.Secondary,
                        )
                    }
                },
            )

            else -> {
                SectionHeader(label = "Saved", count = state.hosts.size)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(HOST_LIST_TAG),
                    contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
                ) {
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
                }
            }
        }
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
