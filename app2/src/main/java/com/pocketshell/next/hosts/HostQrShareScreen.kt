package com.pocketshell.next.hosts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Stable test tags. */
const val HOST_QR_IMAGE_TAG: String = "host-qr-image"

/** Route-level entry point for host QR export. */
@Composable
fun HostQrShareRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HostQrShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    HostQrShareScreen(
        state = state,
        onBack = onBack,
        onNext = viewModel::next,
        onPrevious = viewModel::previous,
        modifier = modifier,
    )
}

/**
 * Shows one host as a QR code for another device to scan (rewrite task P-6).
 *
 * A payload that does not fit one QR is displayed as a sequence the user steps
 * through; the scanner reassembles them by transmission id. The caption states
 * plainly that the code carries no key material, because "is my private key on
 * this screen" is the first thing anyone pointing a camera at it should know —
 * and here the answer is no (see [HostQrShareViewModel]).
 */
@Composable
fun HostQrShareScreen(
    state: HostQrShareUiState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = state.hostName.ifBlank { "Share host" },
            subtitle = state.hostSubtitle.takeIf { it.isNotBlank() },
            trailing = {
                PocketShellButton(
                    text = "Done",
                    onClick = onBack,
                    variant = ButtonVariant.Text,
                    compact = true,
                )
            },
        )

        state.error?.let { error ->
            Column(modifier = Modifier.padding(PocketShellSpacing.lg)) {
                Banner(text = error, role = BannerRole.Error)
            }
            return@Column
        }

        val payload = state.current ?: return@Column

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(PocketShellSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        ) {
            // Keyed on the payload: re-rendering the same code on every
            // recomposition would re-run the encoder and allocate a fresh
            // bitmap for a picture that has not changed.
            val bitmap = remember(payload) { HostQrCode.encode(payload) }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR code for ${state.hostName}",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .testTag(HOST_QR_IMAGE_TAG),
            )

            Text(
                text = "Scan this from another device's Hosts screen. " +
                    "It carries the connection details only — no private key.",
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )

            if (state.parts.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PocketShellButton(
                        text = "Previous",
                        onClick = onPrevious,
                        variant = ButtonVariant.Text,
                        enabled = state.hasPrevious,
                    )
                    Text(
                        text = "Part ${state.index + 1} of ${state.parts.size}",
                        color = PocketShellColors.Text,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    PocketShellButton(
                        text = "Next",
                        onClick = onNext,
                        variant = ButtonVariant.Text,
                        enabled = state.hasNext,
                    )
                }
            }
        }
    }
}
