package com.pocketshell.app.portfwd

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors

@Composable
fun PortForwardPanelScreen(
    hostId: Long,
    keyPath: String?,
    passphrase: CharArray? = null,
    // Slice B (#447): open the panel pre-filled with a remote port and
    // start its forward in one step. Null = the normal manual flow
    // (discovery scan + tap a row to forward).
    prefillRemotePort: Int? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PortForwardPanelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

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

            state.error?.let { error ->
                ErrorBanner(error)
            }

            PortTableHeader()

            when {
                state.autoForwardEnabled && state.tunnels.isEmpty() &&
                    state.connectionState != PortForwardConnectionState.Error -> {
                    EmptyScanningState(modifier = Modifier.weight(1f))
                }

                !state.autoForwardEnabled -> {
                    if (state.tunnels.isEmpty()) {
                        DisabledState(
                            scanning = state.connectionState == PortForwardConnectionState.Connecting,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 12.dp),
                        ) {
                            items(state.tunnels, key = { it.remotePort }) { tunnel ->
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
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        items(state.tunnels, key = { it.remotePort }) { tunnel ->
                            PortForwardRow(
                                tunnel = tunnel,
                                onToggle = { viewModel.togglePort(tunnel.remotePort) },
                                onOpen = {
                                    if (tunnel.status == TunnelInfo.Status.FORWARDING) {
                                        val url = "http://127.0.0.1:${tunnel.localPort}"
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        } catch (_: ActivityNotFoundException) {
                                            // No browser on stripped-down Android images. Keep the panel stable.
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(
    title: String,
    subtitle: String,
    state: PortForwardConnectionState,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(PocketShellColors.Background)
            .border(1.dp, PocketShellColors.BorderSoft)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButtonBox(label = "<", onClick = onBack)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = PocketShellColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = subtitle.ifBlank { state.label },
                    color = PocketShellColors.TextMuted,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AutoForwardRow(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Auto-forward",
                color = PocketShellColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (enabled) {
                    "Foreground service keeps tunnels alive while active"
                } else {
                    "Off; discovery rows do not open local tunnels"
                },
                color = PocketShellColors.TextMuted,
                fontSize = 12.sp,
            )
        }
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

@Composable
private fun PortTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
    val rowClick: (() -> Unit)? = when {
        forwarding -> onOpen
        onRowClick != null -> onRowClick
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { base ->
                if (rowClick != null) {
                    base.clickable(role = Role.Button, onClick = rowClick)
                } else {
                    base
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BodyCell("${tunnel.remotePort}", 0.18f, monospace = true)
        BodyCell("${tunnel.localPort}", 0.16f, monospace = true)
        BodyCell(tunnel.process.ifBlank { "-" }, 0.28f)
        BodyCell(tunnel.status.label, 0.18f, color = tunnel.status.color)
        // Issue #456: declutter the table. Discovered/available rows have no
        // traffic yet, so rendering "0 B / 0 B/s" on every row is just noise.
        // Show the traffic figures only for rows that are actually forwarding.
        Column(modifier = Modifier.weight(0.20f)) {
            if (forwarding) {
                Text(
                    text = formatBytes(tunnel.bytesIn + tunnel.bytesOut),
                    color = PocketShellColors.TextSecondary,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                )
                Text(
                    text = "${formatBytes(tunnel.speedBps)}/s",
                    color = PocketShellColors.TextMuted,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                )
            } else {
                Text(
                    text = "-",
                    color = PocketShellColors.TextMuted,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButtonBox(label = if (forwarding) "Stop" else "Start", onClick = onToggle)
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.weight(weight),
        color = PocketShellColors.TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun RowScope.BodyCell(
    text: String,
    weight: Float,
    monospace: Boolean = false,
    color: androidx.compose.ui.graphics.Color = PocketShellColors.TextSecondary,
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        color = color,
        fontFamily = if (monospace) JetBrainsMonoFamily else null,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TextButtonBox(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(8.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatusDot(state: PortForwardConnectionState) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(state.color, CircleShape),
    )
}

@Composable
private fun ErrorBanner(error: String) {
    Text(
        text = error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(PocketShellColors.Red.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .border(1.dp, PocketShellColors.Red.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = PocketShellColors.Text,
        fontSize = 12.sp,
    )
}

@Composable
private fun EmptyScanningState(modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(10.dp))
            Text("Scanning ports...", color = PocketShellColors.TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DisabledState(scanning: Boolean, modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            if (scanning) "Discovering listening ports..." else "No listening ports discovered.",
            color = PocketShellColors.TextSecondary,
            fontSize = 13.sp,
        )
    }
}

private val PortForwardConnectionState.label: String
    get() = when (this) {
        PortForwardConnectionState.Idle -> "Idle"
        PortForwardConnectionState.Connecting -> "Connecting"
        PortForwardConnectionState.Connected -> "Connected"
        PortForwardConnectionState.Reconnecting -> "Reconnecting"
        PortForwardConnectionState.Error -> "Error"
    }

private val PortForwardConnectionState.color: androidx.compose.ui.graphics.Color
    get() = when (this) {
        PortForwardConnectionState.Idle -> PocketShellColors.TextMuted
        PortForwardConnectionState.Connecting -> PocketShellColors.Amber
        PortForwardConnectionState.Connected -> PocketShellColors.Green
        PortForwardConnectionState.Reconnecting -> PocketShellColors.Amber
        PortForwardConnectionState.Error -> PocketShellColors.Red
    }

private val TunnelInfo.Status.label: String
    get() = when (this) {
        TunnelInfo.Status.FORWARDING -> "Forwarding"
        TunnelInfo.Status.AVAILABLE -> "Available"
        TunnelInfo.Status.FAILED -> "Failed"
        TunnelInfo.Status.STOPPED -> "Stopped"
    }

private val TunnelInfo.Status.color: androidx.compose.ui.graphics.Color
    get() = when (this) {
        TunnelInfo.Status.FORWARDING -> PocketShellColors.Green
        TunnelInfo.Status.AVAILABLE -> PocketShellColors.TextSecondary
        TunnelInfo.Status.FAILED -> PocketShellColors.Red
        TunnelInfo.Status.STOPPED -> PocketShellColors.Amber
    }

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
