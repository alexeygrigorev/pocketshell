package com.pocketshell.next.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** Stable test tags for the Account & sync page (issue #2633). */
const val SYNC_PAGE_TAG: String = "settings-account-page"
const val SYNC_BACK_TAG: String = "settings-account-back"
const val SYNC_SIGN_IN_TAG: String = "sync-sign-in"
const val SYNC_SIGN_OUT_TAG: String = "sync-sign-out"
const val SYNC_ACCOUNT_ROW_TAG: String = "sync-account-row"
const val SYNC_PASSPHRASE_TAG: String = "sync-passphrase"
const val SYNC_PUSH_TAG: String = "sync-push"
const val SYNC_PULL_TAG: String = "sync-pull"
const val SYNC_STATUS_TAG: String = "sync-status"
const val SYNC_UNCONFIGURED_TAG: String = "sync-unconfigured"
const val SYNC_HOSTS_EMPTY_TAG: String = "sync-hosts-empty"
const val SYNC_LIST_TAG: String = "sync-list"

fun syncHostRowTag(alias: String): String = "sync-host-$alias"

@Composable
fun AccountSyncRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountSyncViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountSyncScreen(
        state = state,
        onBack = onBack,
        onSignIn = { viewModel.signIn(context) },
        onSignOut = viewModel::signOut,
        onDismissSignInBanner = viewModel::acknowledgeSignIn,
        onHostChecked = viewModel::setHostChecked,
        onPush = viewModel::push,
        onPull = viewModel::pull,
        modifier = modifier,
    )
}

/**
 * "Account & sync" — the optional Google-login settings sync (issue #2633).
 *
 * The UX model is the desktop app's, unchanged (`docs/SYNC.md`, "What syncs:
 * the selection"): a checkbox per host, and ONLY ticked hosts are uploaded. It
 * is deliberately not an all-or-nothing switch — an unticked host never leaves
 * the device, encrypted or otherwise, and that is the privacy property the
 * whole feature rests on.
 *
 * The passphrase field is a plain `remember`, never a `rememberSaveable`: a
 * saveable would put the passphrase into saved instance state, which the
 * system writes to disk. It lives in composition memory for as long as this
 * screen is on screen and is gone with it.
 */
@Composable
fun AccountSyncScreen(
    state: AccountSyncUiState,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDismissSignInBanner: () -> Unit,
    onHostChecked: (String, Boolean) -> Unit,
    onPush: (String) -> Unit,
    onPull: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var passphrase by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(SYNC_PAGE_TAG),
    ) {
        ScreenHeader(title = "Account & sync", onBack = onBack, backTestTag = SYNC_BACK_TAG)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(SYNC_LIST_TAG),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        ) {
            if (!state.clientConfigured) {
                item {
                    Banner(
                        text = "Google sign-in is not configured in this build yet. " +
                            "Everything else works as usual; sync stays off.",
                        role = BannerRole.Warning,
                        leadingIcon = PocketShellIcons.Warning,
                        modifier = Modifier
                            .padding(horizontal = PocketShellDensity.rowPadH)
                            .testTag(SYNC_UNCONFIGURED_TAG),
                    )
                }
            }

            item { SectionHeader(label = "Account") }
            item {
                when {
                    state.signedIn -> ListRow(
                        title = state.email ?: "Signed in",
                        subtitle = "Your host list syncs to this Google account.",
                        leading = {
                            Icon(PocketShellIcons.Shield, null, tint = PocketShellColors.TextSecondary)
                        },
                        modifier = Modifier.testTag(SYNC_ACCOUNT_ROW_TAG),
                    )

                    else -> Description(
                        title = "Sign in to sync your hosts",
                        body = "Optional. Signed out, PocketShell behaves exactly as it does " +
                            "today — nothing leaves this device.",
                    )
                }
            }
            item {
                PocketShellButton(
                    text = if (state.signedIn) "Sign out" else "Sign in with Google",
                    onClick = if (state.signedIn) onSignOut else onSignIn,
                    variant = if (state.signedIn) ButtonVariant.Secondary else ButtonVariant.Primary,
                    enabled = state.clientConfigured || state.signedIn,
                    modifier = Modifier
                        .padding(horizontal = PocketShellDensity.rowPadH)
                        .testTag(if (state.signedIn) SYNC_SIGN_OUT_TAG else SYNC_SIGN_IN_TAG),
                )
            }
            signInPhaseBanner(state.signInPhase, onDismissSignInBanner)

            if (state.signedIn) {
                item { SectionHeader(label = "Passphrase") }
                item {
                    Description(
                        title = "Your hosts are encrypted before they leave the phone.",
                        body = "The passphrase never reaches the server and is never stored. " +
                            "If you lose it, the synced copy cannot be recovered.",
                    )
                }
                item {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Sync passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PocketShellDensity.rowPadH)
                            .testTag(SYNC_PASSPHRASE_TAG),
                    )
                }

                item { SectionHeader(label = "Hosts to sync") }
                item {
                    Description(
                        title = "Only ticked hosts are uploaded.",
                        body = "An unticked host never leaves this device. Removing a host from " +
                            "your account is untick, then Sync now.",
                    )
                }
                if (state.hosts.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No saved hosts",
                            description = "Add a host first, then choose which ones to sync.",
                            icon = PocketShellIcons.Server,
                            modifier = Modifier
                                .height(180.dp)
                                .testTag(SYNC_HOSTS_EMPTY_TAG),
                        )
                    }
                } else {
                    items(state.hosts, key = { it.name }) { host ->
                        ListRow(
                            title = host.name,
                            subtitle = host.subtitle,
                            leading = {
                                Icon(
                                    if (host.accountOnly) PocketShellIcons.Download else PocketShellIcons.Server,
                                    contentDescription = null,
                                    tint = PocketShellColors.TextSecondary,
                                )
                            },
                            trailing = {
                                Checkbox(
                                    checked = host.checked,
                                    onCheckedChange = { onHostChecked(host.name, it) },
                                )
                            },
                            onClick = { onHostChecked(host.name, !host.checked) },
                            modifier = Modifier.testTag(syncHostRowTag(host.name)),
                        )
                    }
                }

                item { SectionHeader(label = "Sync") }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PocketShellDensity.rowPadH),
                        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                    ) {
                        PocketShellButton(
                            text = "Sync now",
                            onClick = { onPush(passphrase) },
                            variant = ButtonVariant.Primary,
                            enabled = state.outcome != SyncOutcome.Running,
                            modifier = Modifier.testTag(SYNC_PUSH_TAG),
                        )
                        PocketShellButton(
                            text = "Restore from account",
                            onClick = { onPull(passphrase) },
                            variant = ButtonVariant.Secondary,
                            enabled = state.outcome != SyncOutcome.Running,
                            modifier = Modifier.testTag(SYNC_PULL_TAG),
                        )
                    }
                }
                outcomeBanner(state.outcome)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.signInPhaseBanner(
    phase: SyncSignInCoordinator.State,
    onDismiss: () -> Unit,
) {
    when (phase) {
        SyncSignInCoordinator.State.Idle -> Unit
        SyncSignInCoordinator.State.AwaitingRedirect -> item {
            Banner(
                text = "Finish signing in with Google in your browser.",
                role = BannerRole.Info,
                leadingIcon = PocketShellIcons.External,
                modifier = Modifier.padding(horizontal = PocketShellDensity.rowPadH),
            )
        }
        SyncSignInCoordinator.State.Exchanging -> item {
            Banner(
                text = "Completing sign-in…",
                role = BannerRole.Info,
                leadingIcon = PocketShellIcons.Refresh,
                modifier = Modifier.padding(horizontal = PocketShellDensity.rowPadH),
            )
        }
        is SyncSignInCoordinator.State.Failed -> item {
            Banner(
                text = "Sign-in failed: ${phase.message}",
                role = BannerRole.Error,
                leadingIcon = PocketShellIcons.Warning,
                onClick = onDismiss,
                modifier = Modifier.padding(horizontal = PocketShellDensity.rowPadH),
            )
        }
        is SyncSignInCoordinator.State.SignedIn -> item {
            Banner(
                text = "Signed in as ${phase.email ?: "your Google account"}.",
                role = BannerRole.Info,
                leadingIcon = PocketShellIcons.Check,
                onClick = onDismiss,
                modifier = Modifier.padding(horizontal = PocketShellDensity.rowPadH),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.outcomeBanner(outcome: SyncOutcome) {
    val (text, role) = when (outcome) {
        SyncOutcome.None -> return
        SyncOutcome.Running -> "Syncing…" to BannerRole.Info
        is SyncOutcome.Pushed ->
            "Uploaded ${outcome.uploaded} host${plural(outcome.uploaded)} (version ${outcome.version})." to
                BannerRole.Info
        is SyncOutcome.Pulled ->
            "Your account holds ${outcome.hosts} host${plural(outcome.hosts)}." to BannerRole.Info
        SyncOutcome.AccountEmpty ->
            "Your account has no synced hosts yet. Tick some and Sync now." to BannerRole.Info
        is SyncOutcome.Failed -> outcome.message to BannerRole.Error
    }
    item {
        Banner(
            text = text,
            role = role,
            leadingIcon = if (role == BannerRole.Error) PocketShellIcons.Warning else PocketShellIcons.Info,
            modifier = Modifier
                .padding(horizontal = PocketShellDensity.rowPadH)
                .testTag(SYNC_STATUS_TAG),
        )
    }
}

private fun plural(count: Int): String = if (count == 1) "" else "s"

@Composable
private fun Description(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellDensity.rowPadH),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        Text(text = title, color = PocketShellColors.Text, style = PocketShellType.body)
        Text(text = body, color = PocketShellColors.TextSecondary, style = PocketShellType.body)
    }
}
