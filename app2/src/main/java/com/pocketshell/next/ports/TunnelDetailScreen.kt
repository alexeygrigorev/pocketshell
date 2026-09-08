package com.pocketshell.next.ports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val TUNNEL_DETAIL_TAG = "tunnel_detail"
const val TUNNEL_COPY_ADDRESS_TAG = "tunnel_copy_address"
const val TUNNEL_STOP_TAG = "tunnel_stop"
const val TUNNEL_OPEN_BROWSER_TAG = "tunnel_open_browser"

@Composable
fun TunnelDetailRoute(
    remotePort: Int,
    onBack: () -> Unit,
    viewModel: PortForwardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // Services deliberately exposes the complete discovery snapshot, including
    // ports hidden by the legacy "interesting ports" filter used by the old
    // port-forward screen. Resolve detail from that same source or a service
    // such as sshd:22 would open a false "unavailable" state after navigation.
    val discovered = state.discoveredRows.ifEmpty { state.rows }
    val tunnel = discovered.firstOrNull { it.remotePort == remotePort }
    val manualName = state.manualTunnelNames[remotePort]
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(tunnel?.remotePort, tunnel?.localPort, tunnel?.status) {
        viewModel.verifyHttpServices(listOfNotNull(tunnel))
    }
    TunnelDetailScreen(
        hostName = state.hostName,
        tunnel = tunnel,
        manual = remotePort in state.manualRemotePorts,
        manualName = manualName,
        verifiedUrl = state.verifiedHttpServices[remotePort],
        onBack = onBack,
        onCopyAddress = { localPort -> clipboard.setText(AnnotatedString("127.0.0.1:$localPort")) },
        onOpenBrowser = { launchServiceUrl(context, it) },
        onStop = {
            if (remotePort in state.manualRemotePorts) {
                coroutineScope.launch {
                    viewModel.removeManualTunnelNow(remotePort)
                    ForwardService.resume(context)
                    withContext(Dispatchers.Main.immediate) { onBack() }
                }
            } else {
                viewModel.togglePort(remotePort)
                onBack()
            }
        },
    )
}

@Composable
fun TunnelDetailScreen(
    hostName: String,
    tunnel: TunnelInfo?,
    manual: Boolean = false,
    manualName: String? = null,
    onBack: () -> Unit,
    onCopyAddress: (Int) -> Unit,
    onStop: () -> Unit,
    verifiedUrl: String? = null,
    onOpenBrowser: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(TUNNEL_DETAIL_TAG),
    ) {
        ScreenHeader(
            title = manualName?.trim().takeUnless { it.isNullOrEmpty() }
                ?: tunnel?.process?.ifBlank { "Port ${tunnel.remotePort}" }
                ?: "Tunnel",
            subtitle = hostName,
            onBack = onBack,
        )
        if (tunnel == null) {
            EmptyState(
                title = "Tunnel unavailable",
                description = "This service is no longer active on the host.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            TunnelDetailRow("Remote", "${hostName}:${tunnel.remotePort}")
            TunnelDetailRow("On this phone", "127.0.0.1:${tunnel.localPort}")
            if (manual) {
                TunnelDetailRow("Name", manualName?.ifBlank { "Port ${tunnel.remotePort}" } ?: "Port ${tunnel.remotePort}")
                TunnelDetailRow("Mode", "Manual tunnel")
            }
            TunnelDetailRow("State", tunnel.status.detailLabel)
            TunnelDetailRow("Traffic", "${formatBytes(tunnel.bytesIn + tunnel.bytesOut)} total")
            PocketShellButton(
                text = "Copy local address",
                onClick = { onCopyAddress(tunnel.localPort) },
                variant = ButtonVariant.Secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TUNNEL_COPY_ADDRESS_TAG),
            )
            if (verifiedUrl != null) {
                PocketShellButton(
                    text = "Open in browser",
                    onClick = { onOpenBrowser(verifiedUrl) },
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TUNNEL_OPEN_BROWSER_TAG),
                )
            }
            PocketShellButton(
                text = if (manual) "Remove tunnel" else "Stop tunnel",
                onClick = onStop,
                variant = ButtonVariant.Destructive,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TUNNEL_STOP_TAG),
            )
        }
    }
}

@Composable
private fun TunnelDetailRow(label: String, value: String) {
    ListRow(
        title = label,
        subtitle = value,
        modifier = Modifier.fillMaxWidth(),
    )
}

private val TunnelInfo.Status.detailLabel: String
    get() = when (this) {
        TunnelInfo.Status.FORWARDING -> "Forwarding"
        TunnelInfo.Status.AVAILABLE -> "Available"
        TunnelInfo.Status.FAILED -> "Failed"
        TunnelInfo.Status.STOPPED -> "Stopped"
    }
