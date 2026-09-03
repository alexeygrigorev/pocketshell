package com.pocketshell.next.connect

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Stable test tags for the gate's own chrome. */
const val CONNECT_BUSY_BANNER_TAG: String = "connect-busy-banner"
const val CONNECT_ERROR_BANNER_TAG: String = "connect-error-banner"
const val CONNECT_ERROR_RETRY_TAG: String = "connect-error-retry"

/**
 * Puts a real connection between the host list and the rest of the app
 * (rewrite task U-2).
 *
 * Before this, tapping a host navigated straight to `Tree(hostId)` — a
 * placeholder edge from U-1 that could never fail, because nothing was dialled.
 * Now the tap runs the actual dial and the screen shows one of the three
 * outcomes:
 *
 * - connected → [onConnected] (the caller navigates)
 * - host key needs a decision → [TrustPromptSheet]
 * - failed → an error banner with Retry, staying on the list
 *
 * The gate WRAPS the host list rather than replacing it: a failed connect must
 * leave the user looking at their hosts, not at a dead-end error screen, so the
 * banner is chrome above a still-live list.
 *
 * [content] receives the tap callback to attach to each row, so the host list
 * itself keeps knowing nothing about connections (its non-goal from U-1).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ConnectGate(
    onConnected: (Long) -> Unit,
    viewModel: ConnectViewModel,
    modifier: Modifier = Modifier,
    content: @Composable (onOpenHost: (Long) -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    // Keyed on the id so the effect re-runs for a second host after the first
    // navigation consumed the signal. `consumeNavigation` runs BEFORE the
    // navigate call so returning to this screen via Back cannot re-trigger it.
    LaunchedEffect(state.navigateToHostId) {
        val hostId = state.navigateToHostId ?: return@LaunchedEffect
        viewModel.consumeNavigation()
        onConnected(hostId)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Deliberately a text banner and NOT an indeterminate spinner: an
        // infinite animation never lets Compose's test clock go idle, which
        // turns every journey that waits on this screen into a hang. The
        // dial is bounded by the transport's connect timeout anyway.
        state.busyHostId?.let {
            Banner(
                text = "Connecting…",
                role = BannerRole.Info,
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(CONNECT_BUSY_BANNER_TAG),
            )
        }

        state.error?.let { error ->
            Banner(
                text = error.message,
                role = BannerRole.Error,
                maxLines = 4,
                trailingContent = {
                    PocketShellButton(
                        text = "Retry",
                        onClick = viewModel::retry,
                        variant = ButtonVariant.Text,
                        compact = true,
                        modifier = Modifier.testTag(CONNECT_ERROR_RETRY_TAG),
                    )
                },
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(CONNECT_ERROR_BANNER_TAG),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            content { hostId -> viewModel.connect(hostId) }
        }
    }

    state.prompt?.let { prompt ->
        TrustPromptSheet(
            prompt = prompt.state,
            hostLabel = prompt.hostLabel,
            onTrust = viewModel::trust,
            onReject = viewModel::reject,
        )
    }
}
