package com.pocketshell.next.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
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

/**
 * Stable test tags. The list container plus one tag per row, keyed by host id,
 * so a journey can tap a specific host without matching on user-visible copy.
 */
const val HOST_LIST_TAG: String = "host-list"
const val HOST_LIST_ADD_TAG: String = "host-list-add"
const val HOST_LIST_SCAN_TAG: String = "host-list-scan"

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
    onShareHost: (Long) -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HostListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    HostListScreen(
        state = state,
        onOpenHost = onOpenHost,
        onAddHost = onAddHost,
        onEditHost = onEditHost,
        onShareHost = onShareHost,
        onScanQr = onScanQr,
        onDeleteHost = viewModel::delete,
        modifier = modifier,
    )
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
 *   is the only screen a fresh install ever sees.
 * - A per-row [Kebab] with Edit / Share QR / Delete. It sits in the trailing
 *   slot the navigation chevron used to occupy: the row's own tap still dials
 *   the host, and a menu tap does not (an inner clickable consumes it), so one
 *   row carries the primary action and the management actions without a
 *   long-press users have to discover.
 */
@Composable
fun HostListScreen(
    state: HostListUiState,
    onOpenHost: (Long) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (Long) -> Unit,
    onShareHost: (Long) -> Unit,
    onScanQr: () -> Unit,
    onDeleteHost: (Long) -> Unit,
    modifier: Modifier = Modifier,
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
                }
            },
        )

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
                                        KebabItem(label = "Share QR", onClick = { onShareHost(host.id) }),
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
