package com.pocketshell.next.connect

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Stable test tags for the gate's own chrome. */
const val CONNECT_BUSY_BANNER_TAG: String = "connect-busy-banner"
const val CONNECT_BUSY_CANCEL_TAG: String = "connect-busy-cancel"
const val CONNECT_ERROR_BANNER_TAG: String = "connect-error-banner"
const val CONNECT_ERROR_RETRY_TAG: String = "connect-error-retry"
const val CONNECT_PASSPHRASE_TAG: String = "connect-passphrase-sheet"
const val CONNECT_PASSPHRASE_FIELD_TAG: String = "connect-passphrase-field"
const val CONNECT_PASSPHRASE_SUBMIT_TAG: String = "connect-passphrase-submit"
const val CONNECT_PASSPHRASE_CANCEL_TAG: String = "connect-passphrase-cancel"

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
    var navigationInFlight by remember { mutableStateOf(false) }

    // NavHost keeps the previous destination composed while the new one is
    // handed its lifecycle. A successful dial must hide this route's stale
    // Connecting banner during that handoff; otherwise the user can see a
    // false in-flight state above the already-connected tree. The lifecycle
    // event resets the transparent handoff when Back makes Hosts visible.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (navigationInFlight) {
            Log.i(TAG, "Hosts route resumed; clearing navigation handoff")
        }
        navigationInFlight = false
    }

    // Keyed on the id so the effect re-runs for a second host after the first
    // navigation consumed the signal. `consumeNavigation` runs BEFORE the
    // navigate call so returning to this screen via Back cannot re-trigger it.
    LaunchedEffect(state.navigateToHostId) {
        val hostId = state.navigateToHostId ?: return@LaunchedEffect
        Log.i("PocketShell.Connect", "navigation effect host=$hostId")
        navigationInFlight = true
        Log.i(TAG, "Hosts route hiding during navigation handoff host=$hostId")
        viewModel.consumeNavigation()
        onConnected(hostId)
    }

    if (navigationInFlight || state.navigateToHostId != null) {
        // Keep this transparent so the destination being entered remains
        // visible beneath the old back-stack entry while it is still composed.
        Box(modifier = modifier.fillMaxSize())
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            // Deliberately a text banner and NOT an indeterminate spinner: an
            // infinite animation never lets Compose's test clock go idle, which
            // turns every journey that waits on this screen into a hang. The
            // dial is bounded by the transport's connect timeout anyway.
            state.busyHostId?.let {
                Column(
                    modifier = Modifier
                        .padding(horizontal = PocketShellSpacing.md)
                        .padding(bottom = PocketShellSpacing.sm)
                        .testTag(CONNECT_BUSY_BANNER_TAG),
                ) {
                    Banner(
                        text = "Connecting to ${state.busyHostLabel ?: "this host"}",
                        role = BannerRole.Info,
                        trailingContent = {
                            PocketShellButton(
                                text = "Cancel",
                                onClick = viewModel::cancel,
                                variant = ButtonVariant.Text,
                                compact = true,
                                modifier = Modifier.testTag(CONNECT_BUSY_CANCEL_TAG),
                            )
                        },
                    )
                    Text(
                        text = "Checking the server and SSH credentials.",
                        color = PocketShellColors.TextSecondary,
                        modifier = Modifier.padding(
                            horizontal = PocketShellSpacing.sm,
                            vertical = PocketShellSpacing.xs,
                        ),
                    )
                }
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
    }

    state.prompt?.let { prompt ->
        TrustPromptSheet(
            prompt = prompt.state,
            hostLabel = prompt.hostLabel,
            onTrust = viewModel::trust,
            onReject = viewModel::reject,
        )
    }

    state.passphrasePrompt?.let { prompt ->
        ConnectPassphraseSheet(
            prompt = prompt,
            onSubmit = viewModel::submitPassphrase,
            onDismiss = viewModel::dismissPassphrase,
        )
    }
}

private const val TAG = "PocketShell.Connect"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectPassphraseSheet(
    prompt: PassphrasePrompt,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember(prompt.keyId) { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PocketShellColors.Surface,
        shape = PocketShellShapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(bottom = PocketShellSpacing.lg)
                .testTag(CONNECT_PASSPHRASE_TAG),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                PocketShellSpacing.sm,
            ),
        ) {
            SheetHeader(title = "Unlock SSH key", onClose = onDismiss)
            Text(
                text = "${prompt.hostLabel} needs ${prompt.keyName}.",
                color = PocketShellColors.Text,
            )
            Text(
                text = "The key stays encrypted on this device. Enter its passphrase once for this connection; PocketShell does not store it.",
                color = PocketShellColors.TextSecondary,
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Key passphrase") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CONNECT_PASSPHRASE_FIELD_TAG),
            )
            PocketShellButton(
                text = "Unlock and connect",
                enabled = passphrase.isNotEmpty(),
                onClick = {
                    val chars = passphrase.toCharArray()
                    passphrase = ""
                    onSubmit(chars)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CONNECT_PASSPHRASE_SUBMIT_TAG),
            )
            PocketShellButton(
                text = "Cancel",
                onClick = onDismiss,
                variant = ButtonVariant.Text,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CONNECT_PASSPHRASE_CANCEL_TAG),
            )
        }
    }
}
