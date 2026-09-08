package com.pocketshell.next.ports

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.core.portfwd.AutoForwarderSupervisor.ConnectionState
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

const val SERVICES_SCREEN_TAG = "services_screen"
const val SERVICES_BACK_TAG = "services_back"
const val SERVICES_DISCOVERY_TAG = "services_discovery"
const val SERVICES_ADD_TUNNEL_TAG = "services_add_tunnel"
const val SERVICES_ACTIVE_TAG = "services_active_tunnels"
const val SERVICES_AVAILABLE_TAG = "services_available"

fun servicesRowTag(remotePort: Int): String = "service-row-$remotePort"

/** The host-scoped Quiet entry point for forwarding and discovered services. */
@Composable
fun ServicesRoute(
    onBack: () -> Unit,
    onOpenTunnel: (Int) -> Unit = {},
    onAddTunnel: (Int?) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PortForwardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    // Keep the Quiet route on the same real foreground-service lifecycle as the
    // original port-forward route. The Room-backed enabled flag is read again
    // after process death, so reopening this destination remounts every enabled
    // host through ForwardService.resume -> ForwardingController.resumeEnabled.
    LaunchedEffect(state.enabled) {
        if (state.enabled) ForwardService.resume(context)
    }
    ServicesScreen(
        state = state,
        onBack = onBack,
        onSetDiscovery = viewModel::setEnabled,
        onOpenTunnel = onOpenTunnel,
        onAddTunnel = onAddTunnel,
        modifier = modifier,
    )
}

/**
 * A Quiet projection over the existing forwarding controller.
 *
 * Discovery and tunnel rows are deliberately rendered from [state] only. No
 * sample services are injected when the host has not answered, and the
 * existing foreground-service/controller policy remains the owner of all
 * remote side effects.
 */
@Composable
fun ServicesScreen(
    state: PortForwardUiState,
    onBack: () -> Unit,
    onSetDiscovery: (Boolean) -> Unit,
    onOpenTunnel: (Int) -> Unit,
    onAddTunnel: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Services is the explicit discovery surface, so it must include ports
    // hidden by the legacy "interesting ports" filter used by PortForwardScreen
    // (for example sshd:22). Older screen-only test fixtures populate rows but
    // not discoveredRows, hence the compatibility fallback.
    val discovered = state.discoveredRows.ifEmpty { state.rows }
    val active = discovered.filter { it.status == TunnelInfo.Status.FORWARDING }
    val available = discovered.filter { it.status != TunnelInfo.Status.FORWARDING }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(SERVICES_SCREEN_TAG),
    ) {
        ScreenHeader(
            title = "Services & tunnels",
            subtitle = state.hostName.ifBlank { state.hostSubtitle },
            leading = {
                PocketShellButton(
                    text = "Back",
                    onClick = onBack,
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(SERVICES_BACK_TAG),
                )
            },
            trailing = {
                Text(
                    text = state.connection.quietLabel(state.enabled),
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.metadata,
                )
            },
        )

        DiscoveryRow(enabled = state.enabled, onEnabledChange = onSetDiscovery)

        if (!state.enabled) {
            EmptyState(
                title = "No active tunnels",
                description = "Turn on discovery or add a tunnel manually.",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            ServicesFooter(onAddTunnel = { onAddTunnel(null) })
            return@Column
        }

        when {
            (state.loading || (state.enabled && state.connection == ConnectionState.Idle) ||
                state.connection == ConnectionState.Connecting ||
                state.connection == ConnectionState.Reconnecting) -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator.Spinner(size = SpinnerSize.Medium, label = "Looking for services…")
            }

            state.connection == ConnectionState.Lost -> EmptyState(
                title = "Connection needs attention",
                description = state.attention ?: "Reconnect to discover services on this host.",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            active.isEmpty() && available.isEmpty() -> EmptyState(
                title = "No services found",
                description = "Nothing listening in the discoverable port range yet. Add a tunnel manually.",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f).testTag("$SERVICES_SCREEN_TAG-list"),
                contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            ) {
                if (active.isNotEmpty()) {
                    item { SectionHeader(label = "Active tunnels") }
                    items(active, key = { "active-${it.remotePort}" }) { tunnel ->
                        ServiceRow(
                            tunnel = tunnel,
                            active = true,
                            manual = tunnel.remotePort in state.manualRemotePorts,
                            manualName = state.manualTunnelNames[tunnel.remotePort],
                            onClick = { onOpenTunnel(tunnel.remotePort) },
                        )
                    }
                }
                if (available.isNotEmpty()) {
                    item { SectionHeader(label = "Available on ${state.hostName}") }
                    items(available, key = { "available-${it.remotePort}" }) { tunnel ->
                        ServiceRow(
                            tunnel = tunnel,
                            active = false,
                            manual = tunnel.remotePort in state.manualRemotePorts,
                            manualName = state.manualTunnelNames[tunnel.remotePort],
                            onClick = if (tunnel.remotePort in state.manualRemotePorts) {
                                { onOpenTunnel(tunnel.remotePort) }
                            } else {
                                { onAddTunnel(tunnel.remotePort) }
                            },
                        )
                    }
                }
            }
        }
        ServicesFooter(onAddTunnel = { onAddTunnel(null) })
    }
}

@Composable
private fun DiscoveryRow(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellDensity.rowPadH, vertical = PocketShellSpacing.sm)
            .testTag(SERVICES_DISCOVERY_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Discover services",
                color = PocketShellColors.Text,
                style = PocketShellType.body,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Look for listening ports on this host.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.metadata,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(PocketShellSpacing.md))
        SegmentedToggle(
            labels = listOf("Off", "On"),
            selectedIndex = if (enabled) 1 else 0,
            onSelected = { onEnabledChange(it == 1) },
            modifier = Modifier.testTag("$SERVICES_DISCOVERY_TAG-toggle"),
            segmentTag = { index -> "$SERVICES_DISCOVERY_TAG-${if (index == 1) "on" else "off"}" },
        )
    }
}

@Composable
private fun ServiceRow(
    tunnel: TunnelInfo,
    active: Boolean,
    manual: Boolean,
    manualName: String?,
    onClick: () -> Unit,
) {
    val title = manualName?.trim().takeUnless { it.isNullOrEmpty() }
        ?: tunnel.process.ifBlank { "Port ${tunnel.remotePort}" }
    ListRow(
        title = title,
        subtitle = if (active) {
            "127.0.0.1:${tunnel.localPort} · ${tunnel.status.label}"
        } else {
            "${tunnel.remotePort} · ${tunnel.status.label}"
        },
        leading = {
            Icon(
                imageVector = PocketShellIcons.Ports,
                contentDescription = null,
                tint = PocketShellColors.TextSecondary,
                modifier = Modifier.padding(PocketShellSpacing.xs),
            )
        },
        trailing = {
            Text(
                text = if (active || manual) "Details" else "Add",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.metadata,
            )
        },
        modifier = Modifier.testTag(servicesRowTag(tunnel.remotePort)),
        onClick = onClick,
    )
}

@Composable
private fun ServicesFooter(onAddTunnel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        PocketShellButton(
            text = "Add tunnel",
            onClick = onAddTunnel,
            variant = ButtonVariant.Secondary,
            modifier = Modifier.testTag(SERVICES_ADD_TUNNEL_TAG),
        )
    }
}

private fun ConnectionState.quietLabel(enabled: Boolean): String = when {
    !enabled -> "Off"
    this == ConnectionState.Connected -> "Connected"
    this == ConnectionState.Lost -> "Needs attention"
    this == ConnectionState.Idle -> "Idle"
    else -> "Connecting"
}

private val TunnelInfo.Status.label: String
    get() = when (this) {
        TunnelInfo.Status.FORWARDING -> "Forwarding"
        TunnelInfo.Status.AVAILABLE -> "Not forwarded"
        TunnelInfo.Status.FAILED -> "Failed"
        TunnelInfo.Status.STOPPED -> "Stopped"
    }
