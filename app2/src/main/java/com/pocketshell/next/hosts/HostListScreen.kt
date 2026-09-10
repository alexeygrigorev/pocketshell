package com.pocketshell.next.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.next.release.UpdateCheckViewModel
import com.pocketshell.next.usage.UsageGlancePill
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
import com.pocketshell.uikit.components.HeaderIconAction
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stable test tags. The list container plus one tag per row, keyed by host id,
 * so a journey can tap a specific host without matching on user-visible copy.
 */
const val HOST_LIST_TAG: String = "host-list"
const val HOST_LIST_ADD_TAG: String = "host-list-add"
const val HOST_LIST_SETTINGS_TAG: String = "host-list-settings"
const val HOST_LIST_UPDATE_BANNER_TAG: String = "host-list-update-banner"
const val HOST_LIST_UPDATE_DOWNLOAD_TAG: String = "host-list-update-download"
const val HOST_LIST_UPDATE_NOTES_TAG: String = "host-list-update-notes"
const val HOST_LIST_UPDATE_DISMISS_TAG: String = "host-list-update-dismiss"
const val HOST_LIST_UPDATE_RETRY_TAG: String = "host-list-update-retry"
const val HOST_LIST_UPDATE_FAILURE_TAG: String = "host-list-update-failure"
const val HOST_LIST_KEYS_TAG: String = "host-list-ssh-keys"
/** The header cog that opens [HostToolsSheet] (SSH keys + Settings) — #2630. */
const val HOST_LIST_TOOLS_TAG: String = "host-list-tools"
const val HOST_LIST_TOOLS_SHEET_TAG: String = "host-list-tools-sheet"
// Keep the pre-Quiet journey tag as the canonical semantics tag. The longer
// name remains a source-compatible alias for host-list tests and callers.
const val HOST_LIST_SETTINGS_ROW_TAG: String = HOST_LIST_SETTINGS_TAG
const val HOST_LIST_ADD_FOOTER_TAG: String = HOST_LIST_ADD_TAG
/** The header's "N hosts" subtitle — replaces the redundant section header. */
const val HOST_LIST_COUNT_TAG: String = "host-list-count"
const val HOST_LIST_ADD_METHODS_TAG: String = "host-list-add-methods"
const val HOST_LIST_ADD_DETAILS_TAG: String = "host-list-add-details"

fun hostRowTag(hostId: Long): String = "host-row-$hostId"

/** The row's leading connection dot (#2635 2a). */
fun hostRowStatusTag(hostId: Long): String = "host-row-status-$hostId"

fun hostRowMenuTag(hostId: Long): String = "host-row-menu-$hostId"

/** The row's dot state (#2635 2a) — one expression, painted and announced. */
internal fun hostConnectionStatus(host: HostRow): ConnectionStatus =
    if (host.connected) ConnectionStatus.Connected else ConnectionStatus.Idle

/** What the leading dot announces. */
internal fun ConnectionStatus.rowLabel(): String = when (this) {
    ConnectionStatus.Connected -> "Connected"
    ConnectionStatus.Connecting -> "Connecting"
    ConnectionStatus.Error -> "Connection failed"
    ConnectionStatus.Idle -> "Not connected"
}

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
    onOpenSettings: () -> Unit,
    onOpenSshKeys: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
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
        onOpenSettings = onOpenSettings,
        onOpenSshKeys = onOpenSshKeys,
        onOpenUsage = onOpenUsage,
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
 *   methods — a first run needs a visible way forward, so that one stays a
 *   full-size call to action.
 * - Populated Hosts carries both page actions as compact header icons (#2630):
 *   a **cog** opening [HostToolsSheet] (SSH keys + Settings) and a **+**
 *   opening the same Add-host methods. They replace a "Tools" section of
 *   full-width rows plus a full-width footer button, which spent about a third
 *   of a phone screen on three affordances used once a month. The host rows
 *   themselves are unchanged — this is chrome, not the list.
 * - A per-row [Kebab] with Edit / Delete. It sits in the trailing slot the
 *   navigation chevron used to occupy: the row's own tap still dials the host,
 *   and a menu tap does not (an inner clickable consumes it). Share QR was
 *   removed (issue #2523); QR import was removed from the app entirely, so
 *   hosts are added by entering connection details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    state: HostListUiState,
    onOpenHost: (Long) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onDeleteHost: (Long) -> Unit,
    onOpenSshKeys: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
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
    var showTools by remember { mutableStateOf(false) }

    // Header actions belong to the populated page. A fresh install keeps the
    // untouched first-run CTA instead, so "Add host" is never two affordances
    // at once — the empty state's button and the header "+" share one tag and
    // are mutually exclusive by construction.
    val showHeaderActions = state.loaded && state.hosts.isNotEmpty()

    Column(modifier = modifier.fillMaxSize()) {
        // Issue #2632: the usage/cost number is on the LANDING screen, before
        // any tap. It is the cached last reading (the list is a pre-connection
        // screen and usage never dials — D21), so it labels itself stale once
        // it ages out instead of pretending to be live. #2630 put "Tools" and
        // "Add host" in the header too, so the trailing slot is
        // [usage pill][cog][+] — the pill only when there is a reading, the
        // two actions only on a populated page.
        ScreenHeader(
            title = "Hosts",
            // #2635 2a: the count lives here, not in a `SectionHeader` repeated
            // 40dp under a page titled "Hosts" — a screen with one section
            // needs no section label (Nielsen #8). One host needs no count
            // either: the list IS the count when it is one row long.
            subtitle = if (state.hosts.size >= 2) "${state.hosts.size} hosts" else null,
            subtitleTestTag = HOST_LIST_COUNT_TAG,
            trailing = if (state.usagePill == null && !showHeaderActions) {
                null
            } else {
                {
                    state.usagePill?.let { pill ->
                        UsageGlancePill(state = pill, onClick = onOpenUsage)
                    }
                    if (showHeaderActions) {
                        HeaderIconAction(
                            icon = PocketShellIcons.Settings,
                            contentDescription = "Settings and SSH keys",
                            onClick = { showTools = true },
                            testTag = HOST_LIST_TOOLS_TAG,
                        )
                        HeaderIconAction(
                            icon = PocketShellIcons.Plus,
                            contentDescription = "Add host",
                            onClick = { showAddHostMethods = true },
                            testTag = HOST_LIST_ADD_TAG,
                        )
                    }
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
                title = "Your work, from here.",
                description = "Connect to a development machine to open its workspaces and terminals.",
                action = {
                    PocketShellButton(
                        text = "Add host",
                        onClick = { showAddHostMethods = true },
                        modifier = Modifier.testTag(HOST_LIST_ADD_FOOTER_TAG),
                    )
                },
            )

            // #2630: the page is the host list and nothing else. "Tools" (SSH
            // keys + Settings) moved into the header cog's sheet and "Add host"
            // into the header "+", so the only full-width rows left are hosts.
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(HOST_LIST_TAG),
                contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            ) {
                items(items = state.hosts, key = { it.id }) { host ->
                    ListRow(
                        title = host.name,
                        subtitle = host.subtitle,
                        // #2635 2a: which host is warm, before tapping it. The
                        // desktop's host picker has had this dot since day one;
                        // the phone shipped a list with no status at all.
                        leading = {
                            // The dot's COLOUR and its label come from one
                            // expression, deliberately: a dot that says
                            // "Connected" to TalkBack while painting the idle
                            // grey is a lie no assertion on the words could
                            // catch (#2635).
                            val status = hostConnectionStatus(host)
                            StatusDot(
                                status = status,
                                modifier = Modifier
                                    .semantics { contentDescription = status.rowLabel() }
                                    .testTag(hostRowStatusTag(host.id)),
                            )
                        },
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

    if (showTools) {
        HostToolsSheet(
            onOpenSshKeys = {
                showTools = false
                onOpenSshKeys()
            },
            onOpenSettings = {
                showTools = false
                onOpenSettings()
            },
            onDismiss = { showTools = false },
        )
    }

    if (showAddHostMethods) {
        AddHostMethodSheet(
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
 * What the header cog opens: the two device-level surfaces that used to be a
 * full-width "Tools" section on the host list (#2630).
 *
 * A sheet rather than a jump straight to Settings, so ONE tap still reveals
 * both destinations — SSH keys is not a child of Settings, and burying it there
 * would trade two full-width rows for an extra level of navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostToolsSheet(
    onOpenSshKeys: () -> Unit,
    onOpenSettings: () -> Unit,
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
                .testTag(HOST_LIST_TOOLS_SHEET_TAG)
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(bottom = PocketShellSpacing.lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            SheetHeader(title = "Tools", onClose = onDismiss)
            ListRow(
                title = "SSH keys",
                subtitle = "Manage device authentication",
                onClick = onOpenSshKeys,
                modifier = Modifier.testTag(HOST_LIST_KEYS_TAG),
            )
            ListRow(
                title = "Settings",
                subtitle = "Connection and app preferences",
                onClick = onOpenSettings,
                modifier = Modifier.testTag(HOST_LIST_SETTINGS_ROW_TAG),
            )
        }
    }
}

/**
 * The remaining entry point into host setup. It deliberately has no standalone
 * primary action: choosing a row is the action, which keeps the sheet from
 * becoming a second form or a preview-only branch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddHostMethodSheet(
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
            SheetHeader(title = "Add host", onClose = onDismiss)
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
