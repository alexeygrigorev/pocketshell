package com.pocketshell.app.portfwd

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.core.terminal.selection.LocalhostUrl
import com.pocketshell.core.terminal.ui.openUrlWithFallback
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** Test tag for the #492/#602 hidden/noisy ports checkbox row. */
const val SHOW_ALL_PORTS_TEST_TAG = "port_forward_show_all_ports"

@Composable
fun PortForwardPanelScreen(
    hostId: Long,
    keyPath: String?,
    passphrase: CharArray? = null,
    // Slice B (#447): open the panel pre-filled with a remote port and
    // start its forward in one step. Null = the normal manual flow
    // (discovery scan + tap a row to forward).
    prefillRemotePort: Int? = null,
    openBrowserWhenForwardedRemotePort: Int? = null,
    openBrowserWhenForwardedLocalhostUrl: LocalhostUrl? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PortForwardPanelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val initialAutoOpen = remember(
        hostId,
        openBrowserWhenForwardedRemotePort,
        openBrowserWhenForwardedLocalhostUrl,
    ) {
        openBrowserWhenForwardedLocalhostUrl
            ?: openBrowserWhenForwardedRemotePort?.let {
                LocalhostUrl(remotePort = it, scheme = "http", pathAndQuery = "")
            }
    }
    var pendingAutoOpen by remember(
        hostId,
        openBrowserWhenForwardedRemotePort,
        openBrowserWhenForwardedLocalhostUrl,
    ) {
        mutableStateOf(initialAutoOpen)
    }

    fun leave() {
        viewModel.leavePanel()
        onBack()
    }

    BackHandler(onBack = ::leave)

    DisposableEffect(viewModel) {
        onDispose { viewModel.leavePanel() }
    }

    // D21 foreground-service carve-out: the ViewModel observes process
    // lifecycle explicitly so active auto-forward tunnels remain
    // supervised while backgrounded, and idle panels stay idle on
    // foreground return.
    LaunchedEffect(viewModel) {
        viewModel.observeProcessLifecycle()
    }

    LaunchedEffect(hostId, keyPath, passphrase, prefillRemotePort) {
        viewModel.load(
            hostId = hostId,
            initialKeyPath = keyPath,
            initialPassphrase = passphrase,
            discoverPorts = true,
            prefillRemotePort = prefillRemotePort,
        )
    }

    val autoOpenUrl = pendingAutoOpen?.let { request ->
        localForwardedUrlFor(state.tunnels, request)
    }
    LaunchedEffect(autoOpenUrl) {
        val url = autoOpenUrl ?: return@LaunchedEffect
        val remotePort = pendingAutoOpen?.remotePort ?: return@LaunchedEffect
        pendingAutoOpen = null
        DiagnosticEvents.record(
            "action",
            "port_forward_auto_open_url",
            "hostId" to hostId,
            "remotePort" to remotePort,
        )
        openUrlWithFallback(context, url)
    }

    val autoOpenFailed = pendingAutoOpen?.let { request ->
        shouldClearPendingForwardAutoOpen(state, request.remotePort)
    } == true
    LaunchedEffect(autoOpenFailed) {
        if (autoOpenFailed) {
            pendingAutoOpen = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .widthIn(max = 560.dp)
                .fillMaxHeight()
                .fillMaxWidth()
                .background(PocketShellColors.Surface)
                .border(1.dp, PocketShellColors.BorderSoft),
        ) {
            PanelHeader(
                title = state.host?.name ?: "Port Forwarding",
                subtitle = state.host?.let { "${it.username}@${it.hostname}:${it.port}" } ?: "",
                state = state.connectionState,
                onBack = ::leave,
            )

            AutoForwardRow(
                enabled = state.autoForwardEnabled,
                onEnabledChange = viewModel::setAutoForwardEnabled,
            )

            val hiddenPortCount = if (state.autoForwardEnabled) {
                hiddenTunnelRowCount(state.tunnels)
            } else {
                state.hiddenPortCount
            }
            val displayedTunnels = if (state.autoForwardEnabled) {
                visibleTunnelRows(state.tunnels, state.showAllPorts)
            } else {
                state.tunnels
            }

            // Issue #602: the table shows the user-useful 1000..10000 range by
            // default and hides low/system plus high/noisy rows. Keep that true
            // even when auto-forward is active so a foreground forwarding
            // session with many noisy ports does not recreate the dogfood
            // screen. The tunnels keep running; this only filters the rows
            // rendered in the panel.
            ShowAllPortsRow(
                checked = state.showAllPorts,
                hiddenCount = hiddenPortCount,
                onCheckedChange = viewModel::setShowAllPorts,
            )

            state.error?.let { error ->
                ErrorBanner(error)
            }

            SectionHeader(label = "Ports", count = displayedTunnels.size)
            PortTableHeader()

            when {
                state.autoForwardEnabled && displayedTunnels.isEmpty() &&
                    state.connectionState != PortForwardConnectionState.Error -> {
                    if (state.tunnels.isEmpty()) {
                        EmptyScanningState(modifier = Modifier.weight(1f))
                    } else {
                        HiddenPortsState(
                            hiddenCount = hiddenPortCount,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                !state.autoForwardEnabled -> {
                    if (displayedTunnels.isEmpty()) {
                        DisabledState(
                            scanning = state.connectionState == PortForwardConnectionState.Connecting,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = PocketShellSpacing.md),
                        ) {
                            items(displayedTunnels, key = { it.remotePort }) { tunnel ->
                                // Discovery (auto-forward off) state:
                                // tapping a discovered-port row — or its
                                // Start button — initiates a forward for
                                // that remote port in one step (#447).
                                PortForwardRow(
                                    tunnel = tunnel,
                                    onToggle = { viewModel.startPort(tunnel.remotePort) },
                                    onOpen = {},
                                    onRowClick = { viewModel.startPort(tunnel.remotePort) },
                                )
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = PocketShellSpacing.md),
                    ) {
                        items(displayedTunnels, key = { it.remotePort }) { tunnel ->
                            PortForwardRow(
                                tunnel = tunnel,
                                onToggle = { viewModel.togglePort(tunnel.remotePort) },
                                onOpen = {
                                    localForwardedUrlFor(listOf(tunnel), tunnel.remotePort)
                                        ?.let { url -> openUrlWithFallback(context, url) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun localForwardedUrlFor(tunnels: List<TunnelInfo>, remotePort: Int): String? {
    return localForwardedUrlFor(
        tunnels = tunnels,
        localhostUrl = LocalhostUrl(remotePort = remotePort, scheme = "http", pathAndQuery = ""),
    )
}

internal fun localForwardedUrlFor(tunnels: List<TunnelInfo>, localhostUrl: LocalhostUrl): String? {
    val tunnel = tunnels.firstOrNull {
        it.remotePort == localhostUrl.remotePort && it.status == TunnelInfo.Status.FORWARDING
    } ?: return null
    return localhostUrl.toLocalUrl(tunnel.localPort)
}

internal fun visibleTunnelRows(tunnels: List<TunnelInfo>, showAllPorts: Boolean): List<TunnelInfo> =
    if (showAllPorts) {
        tunnels.sortedWith(
            compareBy<TunnelInfo> {
                if (it.isVisibleByDefault()) 0 else 1
            }.thenBy { it.remotePort },
        )
    } else {
        tunnels.filter { it.isVisibleByDefault() }
    }

internal fun hiddenTunnelRowCount(tunnels: List<TunnelInfo>): Int =
    tunnels.count { !it.isVisibleByDefault() }

private fun TunnelInfo.isVisibleByDefault(): Boolean =
    InterestingPortFilter.isVisibleByDefault(remotePort) &&
        InterestingPortFilter.isVisibleByDefault(localPort)

internal fun shouldClearPendingForwardAutoOpen(state: PortForwardPanelState, remotePort: Int): Boolean =
    state.connectionState == PortForwardConnectionState.Error ||
        state.tunnels.any {
            it.remotePort == remotePort && it.status == TunnelInfo.Status.FAILED
        }

@Composable
private fun PanelHeader(
    title: String,
    subtitle: String,
    state: PortForwardConnectionState,
    onBack: () -> Unit,
) {
    ScreenHeader(
        title = title,
        subtitle = subtitle.ifBlank { state.label },
        modifier = Modifier
            .background(PocketShellColors.Background)
            .border(1.dp, PocketShellColors.BorderSoft),
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButtonBox(label = "<", onClick = onBack)
                Spacer(Modifier.width(PocketShellSpacing.sm))
                StatusDot(status = state.toConnectionStatus())
            }
        },
    )
}

/** Test tag for the #751 prominent Auto-forward On/Off control. */
const val AUTO_FORWARD_TOGGLE_TEST_TAG = "port_forward_auto_forward_toggle"

/**
 * Issue #751: the Auto-forward control was an inconspicuous `Switch` in a dense
 * row whose grey subtitle ("Off; discovery rows do not open loca…") read like
 * inert disabled text — the maintainer couldn't tell it was port-forwarding or
 * that the control was tappable. It is now a prominent, obviously-actionable
 * control: a strong "Auto-forward" label sitting on the elevated surface with a
 * cyan-active On/Off [SegmentedToggle] (the canonical "pick one of N" control,
 * #479) so the active state and the tap affordance are unmistakable, plus a
 * legible status line on the secondary text token instead of the muted token.
 */
@Composable
private fun AutoForwardRow(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
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
                text = "Auto-forward ports",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (enabled) {
                    "On — tunnels open automatically and stay alive"
                } else {
                    "Off — tap a port row below to forward it"
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
            modifier = Modifier.testTag(AUTO_FORWARD_TOGGLE_TEST_TAG),
            segmentTag = { index ->
                if (index == 1) "${AUTO_FORWARD_TOGGLE_TEST_TAG}_on"
                else "${AUTO_FORWARD_TOGGLE_TEST_TAG}_off"
            },
        )
    }
}

/**
 * Issue #492/#602: hidden/noisy ports checkbox. Unchecked = the default
 * filtered table (shows local/remote ports in 1000..10000). Checked = every
 * discovered port including hidden/noisy rows outside that range. The label
 * surfaces the hidden-row count so the user knows the table is filtered.
 */
@Composable
private fun ShowAllPortsRow(
    checked: Boolean,
    hiddenCount: Int,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListRow(
        title = hiddenNoisyPortsToggleLabel(checked, hiddenCount),
        modifier = Modifier
            .testTag(SHOW_ALL_PORTS_TEST_TAG)
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

internal fun hiddenNoisyPortsToggleLabel(checked: Boolean, hiddenCount: Int): String =
    if (!checked && hiddenCount > 0) {
        "Show hidden/noisy ports ($hiddenCount hidden)"
    } else {
        "Show hidden/noisy ports"
    }

@Composable
private fun PortTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Background)
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellDensity.rowPadV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("Remote", 0.18f)
        HeaderCell("Local", 0.16f)
        HeaderCell("Process", 0.28f)
        HeaderCell("Status", 0.18f)
        HeaderCell("Traffic", 0.20f)
    }
}

@Composable
private fun PortForwardRow(
    tunnel: TunnelInfo,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    // When the row represents a discovered-but-not-yet-forwarded port,
    // tapping the row body initiates the forward (#447). Forwarding rows
    // keep their "open in browser" tap. Null = row body not tappable.
    onRowClick: (() -> Unit)? = null,
) {
    val forwarding = tunnel.status == TunnelInfo.Status.FORWARDING
    val semantic = LocalPocketShellSemantic.current
    val statusColor: Color = when (tunnel.status) {
        TunnelInfo.Status.FORWARDING -> semantic.statusActive
        TunnelInfo.Status.AVAILABLE -> PocketShellColors.TextSecondary
        TunnelInfo.Status.FAILED -> semantic.statusError
        TunnelInfo.Status.STOPPED -> semantic.statusAttention
    }
    val rowClick: (() -> Unit)? = when {
        forwarding -> onOpen
        onRowClick != null -> onRowClick
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(
                minHeight = if (rowClick != null) {
                    PocketShellDensity.tapTargetMin
                } else {
                    PocketShellDensity.rowMinHeight
                },
            )
            .let { base ->
                if (rowClick != null) {
                    base.clickable(role = Role.Button, onClick = rowClick)
                } else {
                    base
                }
            }
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellDensity.rowPadV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BodyCell("${tunnel.remotePort}", 0.18f, monospace = true)
        BodyCell("${tunnel.localPort}", 0.16f, monospace = true)
        BodyCell(tunnel.process.ifBlank { "-" }, 0.28f)
        BodyCell(tunnel.status.label, 0.18f, color = statusColor)
        // Issue #456: declutter the table. Discovered/available rows have no
        // traffic yet, so rendering "0 B / 0 B/s" on every row is just noise.
        // Show the traffic figures only for rows that are actually forwarding.
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
        TextButtonBox(label = if (forwarding) "Stop" else "Start", onClick = onToggle)
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.weight(weight),
        color = PocketShellColors.TextMuted,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun RowScope.BodyCell(
    text: String,
    weight: Float,
    monospace: Boolean = false,
    color: Color = PocketShellColors.TextSecondary,
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        color = color,
        style = if (monospace) PocketShellType.bodyMono else PocketShellType.bodyDense,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TextButtonBox(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .defaultMinSize(
                minWidth = PocketShellDensity.tapTargetMin,
                minHeight = PocketShellDensity.tapTargetMin,
            )
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(
                horizontal = PocketShellDensity.chipPadH,
                vertical = PocketShellDensity.chipPadV,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ErrorBanner(error: String) {
    Text(
        text = error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellDensity.rowPadV,
            )
            .background(PocketShellColors.Red.copy(alpha = 0.12f), PocketShellShapes.small)
            .border(1.dp, PocketShellColors.Red.copy(alpha = 0.55f), PocketShellShapes.small)
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellDensity.rowPadV,
            ),
        color = PocketShellColors.Text,
        style = PocketShellType.bodyDense,
    )
}

@Composable
private fun EmptyScanningState(modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(PocketShellSpacing.sm))
            Text(
                "Scanning ports...",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
            )
        }
    }
}

@Composable
private fun DisabledState(scanning: Boolean, modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            if (scanning) "Discovering listening ports..." else "No listening ports discovered.",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
        )
    }
}

@Composable
private fun HiddenPortsState(hiddenCount: Int, modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            if (hiddenCount == 1) {
                "1 noisy port hidden."
            } else {
                "$hiddenCount noisy ports hidden."
            },
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
        )
    }
}

private fun PortForwardConnectionState.toConnectionStatus(): ConnectionStatus =
    when (this) {
        PortForwardConnectionState.Idle -> ConnectionStatus.Idle
        PortForwardConnectionState.Connecting -> ConnectionStatus.Connecting
        PortForwardConnectionState.Connected -> ConnectionStatus.Connected
        PortForwardConnectionState.Reconnecting -> ConnectionStatus.Connecting
        PortForwardConnectionState.Error -> ConnectionStatus.Error
    }

private val PortForwardConnectionState.label: String
    get() = when (this) {
        PortForwardConnectionState.Idle -> "Idle"
        PortForwardConnectionState.Connecting -> "Connecting"
        PortForwardConnectionState.Connected -> "Connected"
        PortForwardConnectionState.Reconnecting -> "Reconnecting"
        PortForwardConnectionState.Error -> "Error"
    }

private val TunnelInfo.Status.label: String
    get() = when (this) {
        TunnelInfo.Status.FORWARDING -> "Forwarding"
        TunnelInfo.Status.AVAILABLE -> "Available"
        TunnelInfo.Status.FAILED -> "Failed"
        TunnelInfo.Status.STOPPED -> "Stopped"
    }

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
