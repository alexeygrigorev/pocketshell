package com.pocketshell.next.ports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.core.portfwd.AutoForwarderSupervisor.ConnectionState
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** Stable test tags. */
const val PORT_TABLE_TAG: String = "port_forward_table"
const val FORWARDING_TOGGLE_TAG: String = "port_forward_toggle"
const val SHOW_ALL_PORTS_TAG: String = "port_forward_show_all_ports"
const val PORT_FORWARD_BACK_TAG: String = "port-forward-back"

fun portRowTag(remotePort: Int): String = "port-row-$remotePort"

/**
 * Route-level entry point: binds the Hilt-provided [PortForwardViewModel] to the
 * stateless [PortForwardScreen].
 *
 * The one thing that cannot live in the ViewModel is starting the foreground
 * service, which needs a `Context`. Doing it here — keyed on the enabled flag —
 * keeps the ViewModel Android-free and testable.
 *
 * Re-triggering `resume` for an already-enabled host is deliberate, not merely
 * harmless: the controller asks an already-mounted supervisor to retry now, and
 * arriving on this screen is exactly the recovery path for a host parked in the
 * terminal needs-attention state after its key was confirmed from the host list
 * (#2491). For a healthy host it changes nothing.
 */
@Composable
fun PortForwardRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PortForwardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(state.enabled) {
        if (state.enabled) ForwardService.resume(context)
    }
    PortForwardScreen(
        state = state,
        onSetEnabled = viewModel::setEnabled,
        onTogglePort = viewModel::togglePort,
        onSetShowAllPorts = viewModel::setShowAllPorts,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * The port-forward screen (rewrite task P-4).
 *
 * Three controls and a table:
 * - An Off/On [SegmentedToggle] for the whole host. The old client used a small
 *   `Switch` in a dense row and the maintainer could not tell it was tappable
 *   (#751); the segmented toggle is the canonical "pick one of N" control and
 *   makes both the state and the affordance unmistakable.
 * - A "Show hidden/noisy ports" checkbox, surfacing the hidden-row count so the
 *   user knows the table is filtered rather than empty.
 * - One row per discovered port: remote, local, process, status, traffic, and a
 *   Start/Stop action.
 */
@Composable
fun PortForwardScreen(
    state: PortForwardUiState,
    onSetEnabled: (Boolean) -> Unit,
    onTogglePort: (Int) -> Unit,
    onSetShowAllPorts: (Boolean) -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = state.hostName,
            subtitle = state.hostSubtitle.ifBlank { state.connection.label },
            leading = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PocketShellButton(
                        text = "Back",
                        onClick = onBack,
                        variant = ButtonVariant.Text,
                        compact = true,
                        modifier = Modifier.testTag(PORT_FORWARD_BACK_TAG),
                    )
                    Spacer(Modifier.width(PocketShellSpacing.sm))
                    StatusDot(status = state.connection.toConnectionStatus(state.enabled))
                }
            },
        )

        ForwardingToggleRow(enabled = state.enabled, onEnabledChange = onSetEnabled)

        ShowAllPortsRow(
            checked = state.showAllPorts,
            hiddenCount = state.hiddenCount,
            onCheckedChange = onSetShowAllPorts,
        )

        SectionHeader(label = "Ports", count = state.rows.size)
        PortTableHeader(PORT_COLUMNS)

        when {
            !state.enabled -> CenteredMessage(
                text = "Forwarding is off. Turn it on to discover listening ports.",
                modifier = Modifier.weight(1f),
            )

            state.connection == ConnectionState.Lost -> CenteredMessage(
                text = pausedMessage(state.attention),
                modifier = Modifier.weight(1f),
            )

            state.rows.isEmpty() && state.hiddenCount > 0 -> CenteredMessage(
                text = hiddenPortsMessage(state.hiddenCount),
                modifier = Modifier.weight(1f),
            )

            state.scanning -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator.Spinner(size = SpinnerSize.Medium, label = "Scanning ports…")
            }

            else -> {
                val rowKeys = tunnelRowKeys(state.rows)
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .testTag(PORT_TABLE_TAG),
                    contentPadding = PaddingValues(bottom = PocketShellSpacing.md),
                ) {
                    itemsIndexed(state.rows, key = { index, _ -> rowKeys[index] }) { _, tunnel ->
                        PortForwardRow(tunnel = tunnel, onToggle = { onTogglePort(tunnel.remotePort) })
                    }
                }
            }
        }
    }
}

/**
 * Stable, guaranteed-UNIQUE LazyColumn keys.
 *
 * Keying rows on `remotePort` alone crashed the old client: the list can
 * legitimately contain two rows for one remote port (the same port discovered on
 * two interfaces, or a forwarded row co-existing with its AVAILABLE twin), and
 * `LazyColumn` throws `IllegalArgumentException: Key "22" already used` on a
 * collision. The key is the row's `(remote, local, status)` identity plus an
 * occurrence counter, so it is stable across recompositions AND collision-free
 * whatever the data model produces.
 */
internal fun tunnelRowKeys(tunnels: List<TunnelInfo>): List<String> {
    val seen = HashMap<String, Int>()
    return tunnels.map { tunnel ->
        val base = "${tunnel.remotePort}:${tunnel.localPort}:${tunnel.status}"
        val occurrence = seen.getOrDefault(base, 0)
        seen[base] = occurrence + 1
        if (occurrence == 0) base else "$base#$occurrence"
    }
}

internal fun showAllPortsLabel(checked: Boolean, hiddenCount: Int): String =
    if (!checked && hiddenCount > 0) {
        "Show hidden/noisy ports ($hiddenCount hidden)"
    } else {
        "Show hidden/noisy ports"
    }

internal fun hiddenPortsMessage(hiddenCount: Int): String =
    if (hiddenCount == 1) "1 noisy port hidden." else "$hiddenCount noisy ports hidden."

/**
 * What a host in the TERMINAL [ConnectionState.Lost] state says (#2491).
 *
 * It must never claim a retry is coming: the supervisor has parked, and the
 * whole point of the state is that nothing will change until the user acts.
 * [attention] carries what to actually do when the dial site knew (an
 * unconfirmed host key, a deleted host row).
 */
internal fun pausedMessage(attention: String?): String =
    "Forwarding paused. " + (attention ?: "This host could not be reached.")

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

private val PORT_COLUMNS: List<PortColumn> = listOf(
    PortColumn("Remote", 0.18f),
    PortColumn("Local", 0.16f),
    PortColumn("Process", 0.28f),
    PortColumn("Status", 0.18f),
    PortColumn("Traffic", 0.20f),
)

@Composable
private fun ForwardingToggleRow(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev)
            .border(1.dp, PocketShellColors.BorderSoft)
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellSpacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Forward ports from this host",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (enabled) {
                    "On — tunnels stay alive while the app is in the background"
                } else {
                    "Off — nothing is forwarded and no connection is held"
                },
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(PocketShellSpacing.md))
        SegmentedToggle(
            labels = listOf("Off", "On"),
            selectedIndex = if (enabled) 1 else 0,
            onSelected = { onEnabledChange(it == 1) },
            modifier = Modifier.testTag(FORWARDING_TOGGLE_TAG),
            segmentTag = { index ->
                if (index == 1) "${FORWARDING_TOGGLE_TAG}_on" else "${FORWARDING_TOGGLE_TAG}_off"
            },
        )
    }
}

@Composable
private fun ShowAllPortsRow(checked: Boolean, hiddenCount: Int, onCheckedChange: (Boolean) -> Unit) {
    ListRow(
        title = showAllPortsLabel(checked, hiddenCount),
        modifier = Modifier
            .testTag(SHOW_ALL_PORTS_TAG)
            .defaultMinSize(minHeight = PocketShellDensity.tapTargetMin)
            .clickable(role = Role.Checkbox) { onCheckedChange(!checked) },
        leading = {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = PocketShellColors.Accent,
                    uncheckedColor = PocketShellColors.TextSecondary,
                ),
            )
        },
    )
}

@Composable
private fun PortForwardRow(tunnel: TunnelInfo, onToggle: () -> Unit) {
    val forwarding = tunnel.status == TunnelInfo.Status.FORWARDING
    val semantic = LocalPocketShellSemantic.current
    val statusColor: Color = when (tunnel.status) {
        TunnelInfo.Status.FORWARDING -> semantic.statusActive
        TunnelInfo.Status.AVAILABLE -> PocketShellColors.TextSecondary
        TunnelInfo.Status.FAILED -> semantic.statusError
        TunnelInfo.Status.STOPPED -> semantic.statusAttention
    }
    PortTableRow(
        onClick = onToggle,
        modifier = Modifier.testTag(portRowTag(tunnel.remotePort)),
    ) {
        PortBodyCell("${tunnel.remotePort}", 0.18f, monospace = true)
        PortBodyCell(if (forwarding) "${tunnel.localPort}" else "-", 0.16f, monospace = true)
        PortBodyCell(tunnel.process.ifBlank { "-" }, 0.28f)
        PortBodyCell(tunnel.status.label, 0.18f, color = statusColor)
        // Discovered/available rows have no traffic yet, so "0 B / 0 B/s" on
        // every row would be pure noise; the figures appear only where they mean
        // something.
        Column(modifier = Modifier.weight(0.20f)) {
            if (forwarding) {
                Text(
                    text = formatBytes(tunnel.bytesIn + tunnel.bytesOut),
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.labelMono,
                )
                Text(
                    text = "${formatBytes(tunnel.speedBps)}/s",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.labelMono,
                )
            } else {
                Text(
                    text = "-",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.labelMono,
                )
            }
        }
        Spacer(Modifier.width(PocketShellSpacing.sm))
        PocketShellButton(
            text = if (forwarding) "Stop" else "Start",
            onClick = onToggle,
            variant = if (forwarding) ButtonVariant.Text else ButtonVariant.Primary,
            compact = true,
        )
    }
}

@Composable
private fun CenteredMessage(text: String, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            // The terminal "why forwarding stopped" copy is a sentence, not a
            // label, so it wraps — without a gutter it ran edge to edge (#2491).
            .padding(horizontal = PocketShellSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
            textAlign = TextAlign.Center,
        )
    }
}

private fun ConnectionState.toConnectionStatus(enabled: Boolean): ConnectionStatus = when {
    !enabled -> ConnectionStatus.Idle
    this == ConnectionState.Connected -> ConnectionStatus.Connected
    this == ConnectionState.Lost -> ConnectionStatus.Error
    this == ConnectionState.Idle -> ConnectionStatus.Idle
    else -> ConnectionStatus.Connecting
}

private val ConnectionState.label: String
    get() = when (this) {
        ConnectionState.Idle -> "Idle"
        ConnectionState.Connecting -> "Connecting"
        ConnectionState.Connected -> "Connected"
        ConnectionState.Reconnecting -> "Reconnecting"
        // Terminal, not "still trying": the supervisor has stopped dialling and
        // only the user can change the outcome (#2491).
        ConnectionState.Lost -> "Needs attention"
    }

private val TunnelInfo.Status.label: String
    get() = when (this) {
        TunnelInfo.Status.FORWARDING -> "Forwarding"
        TunnelInfo.Status.AVAILABLE -> "Available"
        TunnelInfo.Status.FAILED -> "Failed"
        TunnelInfo.Status.STOPPED -> "Stopped"
    }
