package com.pocketshell.app.hosts

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.fragment.app.FragmentActivity
import com.pocketshell.app.release.ReleaseChecker
import com.pocketshell.app.bootstrap.HostBootstrapSheet
import com.pocketshell.app.release.ReleaseInfo
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionPickerRequest
import com.pocketshell.app.sessions.HostTmuxSessionPickerSheet
import com.pocketshell.app.sessions.HostTmuxSessionPickerViewModel
import com.pocketshell.app.sessions.SessionsDashboardViewModel
import com.pocketshell.app.sessions.SessionsSection
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.components.HostCard
import com.pocketshell.uikit.model.HostSetupState
import com.pocketshell.uikit.model.HostStatus
import com.pocketshell.uikit.theme.PocketShellColors
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Landing screen — the list of saved hosts. Visual target is
 * `docs/mockups/dashboard.html` under the "Hosts" section. Only the
 * Hosts section is rendered here; "Sessions" + "Scheduled" arrive in
 * later issues (#22 / #28).
 *
 * Layout (top-to-bottom, matching the mockup):
 *
 * - **App bar** — title "PocketShell" + a "Keys" affordance.
 * - **Section label** — uppercase "Hosts <count>" with a small chip.
 * - **Host cards** — each a `ui-kit` [HostCard]. Tapping invokes the
 *   `onOpenSession` callback with the resolved key path.
 * - **FAB** — bottom-right `+` opening `AddEditHostScreen`.
 *
 * Empty-state copy is intentionally terse — the FAB carries the action.
 *
 * The screen does not own a connection — it resolves the host's key on
 * tap and hands the tuple to the caller, which routes to the session
 * screen. This keeps the list a pure read surface; the
 * [SessionViewModel][com.pocketshell.app.session.SessionViewModel]
 * runs the connect under its own Hilt scope.
 *
 * `onEditHost` is retained on the public signature because the nav
 * graph (`MainActivity`) still routes to the edit screen by host id —
 * see issue #38 for the removal of the long-press affordance that used
 * to invoke it. Once a non-clobbering long-press hook lands on
 * `HostCard` (`ui-kit`) the wire-up returns; until then the parameter
 * is intentionally unused at this call site.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    onAddHost: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onEditHost: (Long) -> Unit,
    onManageKeys: () -> Unit,
    // Issue #112: Crashes affordance was moved off the top bar and now
    // lives under Settings → Diagnostics. The activity-level wiring still
    // passes this callback so the navigator can re-introduce a direct
    // entry point later (e.g. a deep link) without re-threading state.
    @Suppress("UNUSED_PARAMETER") onOpenCrashReports: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenSession: (HostEntity, keyPath: String, passphrase: CharArray?) -> Unit,
    onOpenTmuxHostSession: (
        HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        startDirectory: String?,
    ) -> Unit = { _, _, _, _, _ -> },
    onOpenPortForwardPanel: (HostEntity, keyPath: String, passphrase: CharArray?) -> Unit = { _, _, _ -> },
    /**
     * Issue #117 (usage Fix C): the bootstrap sheet's Success state can
     * route the user to the usage panel when `heru` was just installed.
     * The callback is optional because Fix A owns the actual
     * `AppDestination.Usage` destination and only wires the route once
     * its branch lands; until then the call site can pass `null` and the
     * Success row falls back to a Continue-only layout.
     */
    onOpenUsage: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: HostListViewModel = hiltViewModel(),
    sessionsViewModel: SessionsDashboardViewModel = hiltViewModel(),
    hostTmuxSessionPickerViewModel: HostTmuxSessionPickerViewModel = hiltViewModel(),
    onOpenTmuxSession: (ActiveTmuxClients.Entry, sessionName: String, startDirectory: String?) -> Unit =
        { _, _, _ -> },
) {
    val hosts by viewModel.hosts.collectAsState()
    val sessions by sessionsViewModel.sessions.collectAsState()
    val hostTmuxPickerState by hostTmuxSessionPickerViewModel.state.collectAsState()
    val updateInfo by viewModel.updateAvailable.collectAsState()
    val bootstrapState by viewModel.bootstrapState.collectAsState()
    val bootstrapHostName by viewModel.bootstrapHostName.collectAsState()
    val pendingNavigation by viewModel.pendingNavigation.collectAsState()
    val sharePayload by viewModel.sharePayload.collectAsState()
    val shareMessage by viewModel.shareMessage.collectAsState()
    val recheckMessage by viewModel.recheckMessage.collectAsState()
    val setupStates by viewModel.setupStates.collectAsState()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val hostSharePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { viewModel.importSharedHostUri(it) } }

    // Read the installed `versionName` once and cache it for the lifetime
    // of this composable — `versionName` is a build-time constant for the
    // running APK, so `remember` without a key is correct.
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    // Resolve-key-then-navigate is async (suspending DAO read) but the tap
    // originates from the main thread. The request is funneled through a
    // SharedFlow consumed by a LaunchedEffect — keeps suspending work out
    // of the click handler.
    //
    // Issue #38 item 2: keyed on `Unit` (not `hosts`) so the collector is
    // installed exactly once and is NOT recreated on every emission of the
    // hosts flow. `hosts` and `onOpenSession` are pulled in via
    // `rememberUpdatedState` so the collector body always sees the latest
    // values without forcing a new subscription on each change.
    //
    // Issue #49 inserts a bootstrap step: the tap kicks the ViewModel's
    // `bootstrapHost(...)`, which probes for tmux, optionally shows the
    // sheet, and signals readiness via `pendingNavigation`. The
    // navigation collector watches that StateFlow and fires
    // `onOpenSession` only once `ready == true`.
    val tapRequests = remember { MutableSharedFlow<Long>(extraBufferCapacity = 4) }
    val portPanelRequests = remember { MutableSharedFlow<Long>(extraBufferCapacity = 4) }
    val recheckRequests = remember { MutableSharedFlow<Long>(extraBufferCapacity = 4) }
    val currentHosts by rememberUpdatedState(hosts)
    val currentOpenSession by rememberUpdatedState(onOpenSession)
    val currentOpenTmuxHostSession by rememberUpdatedState(onOpenTmuxHostSession)
    val currentOpenPortForwardPanel by rememberUpdatedState(onOpenPortForwardPanel)
    var pendingPassphrase by remember { mutableStateOf<PendingPassphraseRequest?>(null) }
    var passphraseText by remember { mutableStateOf("") }
    var passphraseUnlockError by remember { mutableStateOf<String?>(null) }

    fun continueWithPassphrase(
        host: HostEntity,
        key: SshKeyEntity,
        action: PendingPassphraseAction,
        passphrase: CharArray?,
    ) {
        when (action) {
            PendingPassphraseAction.OpenSession -> viewModel.bootstrapHost(
                host = host,
                keyPath = key.privateKeyPath,
                passphrase = passphrase,
            )
            PendingPassphraseAction.OpenPorts -> currentOpenPortForwardPanel(
                host,
                key.privateKeyPath,
                passphrase,
            )
        }
    }

    fun requestProtectedConnection(host: HostEntity, key: SshKeyEntity, action: PendingPassphraseAction) {
        if (!key.hasPassphrase) {
            continueWithPassphrase(host, key, action, passphrase = null)
            return
        }
        val showPrompt = {
            passphraseText = ""
            passphraseUnlockError = null
            pendingPassphrase = PendingPassphraseRequest(host, key, action)
        }
        if (!isSshKeyUnlockRequired(context)) {
            showPrompt()
            return
        }
        launchSshKeyUnlock(
            activity = activity,
            title = "Unlock SSH key passphrase",
            subtitle = "Confirm it is you before entering this key's passphrase",
            onSuccess = showPrompt,
            onError = { passphraseUnlockError = it },
        )
    }

    LaunchedEffect(Unit) {
        tapRequests.collect { hostId ->
            val host = currentHosts.find { it.id == hostId } ?: return@collect
            val key = viewModel.keyFor(host.keyId) ?: return@collect
            requestProtectedConnection(host, key, PendingPassphraseAction.OpenSession)
        }
    }
    LaunchedEffect(Unit) {
        portPanelRequests.collect { hostId ->
            val host = currentHosts.find { it.id == hostId } ?: return@collect
            val key = viewModel.keyFor(host.keyId) ?: return@collect
            requestProtectedConnection(host, key, PendingPassphraseAction.OpenPorts)
        }
    }
    // Issue #120: "Re-check setup" kebab item. Resolves the host's key
    // path the same way the connect tap does, then asks the ViewModel
    // to invalidate the bootstrap cache and re-probe. The ViewModel
    // surfaces a one-shot acknowledgement message via [recheckMessage]
    // which renders in the share-banner slot.
    LaunchedEffect(Unit) {
        recheckRequests.collect { hostId ->
            val host = currentHosts.find { it.id == hostId } ?: return@collect
            val key = viewModel.keyFor(host.keyId) ?: return@collect
            viewModel.recheckSetup(host, key.privateKeyPath)
        }
    }
    // Issue #120 AC: when no bootstrap report exists yet, the badge
    // shows `unknown` and the ViewModel triggers a background reprobe
    // on first composition. `LaunchedEffect(Unit)` runs exactly once
    // per composition and the ViewModel guards against re-firing under
    // config changes.
    LaunchedEffect(Unit) {
        viewModel.reprobeUnknownHostsOnce()
    }

    // Fire `onOpenSession` once the ViewModel marks the pending
    // navigation ready. The ViewModel handles the cache-hit fast path
    // (immediate ready) as well as the sheet-driven slow path
    // (ready after Skip / Continue / Close).
    LaunchedEffect(pendingNavigation) {
        val pending = pendingNavigation
        if (pending != null && pending.ready) {
            hostTmuxSessionPickerViewModel.load(
                HostTmuxSessionPickerRequest(
                    host = pending.host,
                    keyPath = pending.keyPath,
                    passphrase = pending.passphrase,
                ),
            )
            viewModel.consumePendingNavigation()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HostsAppBar(
                onKeysClick = onManageKeys,
                onImportHostClick = { hostSharePicker.launch("*/*") },
                onSettingsClick = onOpenSettings,
            )

            // Issue #40: surface the upgrade prompt at the top so the
            // user sees it before the host list, but only when the
            // ViewModel has confirmed a strictly-newer release.
            updateInfo?.let { info ->
                UpdateBanner(
                    info = info,
                    onUpdate = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl)),
                        )
                    },
                )
            }

            shareMessage?.let { msg ->
                ShareMessageBanner(message = msg, onDismiss = viewModel::clearShareMessage)
            }

            // Issue #120: dedicated banner for the manual "Re-check
            // setup" acknowledgement. Reuses [ShareMessageBanner] for
            // visual consistency with the existing share-message banner;
            // a separate StateFlow keeps the two messages independent so
            // a host-share doesn't blow away a re-check ack and vice
            // versa.
            recheckMessage?.let { msg ->
                ShareMessageBanner(message = msg, onDismiss = viewModel::clearRecheckMessage)
            }

            // Issue #46: cross-host session dashboard. Render the
            // Sessions section ABOVE the Hosts section per the mockup
            // at `docs/mockups/dashboard.html`, but only when there is
            // at least one live tmux session on at least one connected
            // host. Empty → the chrome (label + rows) collapses
            // entirely so the host list keeps the visual hierarchy of
            // an "empty workspace" landing.
            if (sessions.isNotEmpty()) {
                SectionLabel(
                    label = "Sessions",
                    count = sessionsCountLabel(sessions.size),
                )
                SessionsSection(
                    viewModel = sessionsViewModel,
                    onOpenTmuxSession = onOpenTmuxSession,
                )
            }

            SectionLabel(
                label = "Hosts",
                count = "${hosts.size} saved",
            )

            if (hosts.isEmpty()) {
                EmptyHostList(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(hosts, key = { it.id }) { host ->
                        // Issue #113: the row used to carry three squared
                        // chips ("HostCard | Ports | Share") that visually
                        // competed with the primary "tap → connect" affordance.
                        // The secondary actions now live behind a kebab + an
                        // optional long-press on the card; the card itself
                        // owns the only tap target and gets the full row
                        // width so the `user@host:port` line can wrap at
                        // large font scales instead of truncating.
                        var menuOpen by remember(host.id) { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                        ) {
                            // Issue #120: derive the per-host setup state from
                            // the ViewModel's persisted-column projection.
                            // Default to Unknown if the row isn't yet in the
                            // map (race-free fallback while the DAO emission
                            // catches up).
                            val setupState = setupStates[host.id] ?: HostSetupState.Unknown
                            HostCard(
                                name = host.name,
                                subtitle = "${host.username}@${host.hostname}:${host.port}",
                                // Phase 1 does not track live connection state
                                // here — the host list is a static snapshot.
                                // Live "connected" chips return when
                                // session-state plumbing lands (#22).
                                status = HostStatus.Disconnected,
                                onClick = { tapRequests.tryEmit(host.id) },
                                // Issue #113: long-press now opens the same
                                // overflow menu the kebab exposes — gives
                                // users two equivalent ways to reach the
                                // secondary actions. Wired through
                                // `combinedClickable` inside `HostCard`.
                                onLongClick = { menuOpen = true },
                                setupState = setupState,
                                onSetupBadgeClick = if (setupState == HostSetupState.NeedsSetup) {
                                    // Issue #120: tapping a `needs setup`
                                    // badge opens the existing bootstrap
                                    // sheet through the standard tap-to-
                                    // connect path. `bootstrapHost` is
                                    // cache-aware: a `needsSetup` row has
                                    // either `tmuxInstalled == false` or
                                    // `heruInstalled == false`, both of
                                    // which surface the sheet when the
                                    // probe re-runs.
                                    { tapRequests.tryEmit(host.id) }
                                } else null,
                                trailingContent = {
                                    HostOverflowMenuAnchor(
                                        expanded = menuOpen,
                                        onExpand = { menuOpen = true },
                                        onDismiss = { menuOpen = false },
                                        onOpenPorts = {
                                            menuOpen = false
                                            portPanelRequests.tryEmit(host.id)
                                        },
                                        onShare = {
                                            menuOpen = false
                                            viewModel.createSharePayload(host)
                                        },
                                        onRecheckSetup = {
                                            menuOpen = false
                                            recheckRequests.tryEmit(host.id)
                                        },
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(HOST_ROW_TAG_PREFIX + host.id),
                            )
                        }
                    }
                }
            }

            // Footer: installed version. Sits at the bottom of the
            // column so the host list keeps its prominence; muted text
            // colour so it doesn't compete with the FAB.
            VersionFooter(versionName = versionName)
        }

        FloatingActionButton(
            onClick = onAddHost,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
                .size(56.dp),
            shape = CircleShape,
            containerColor = PocketShellColors.Accent,
            contentColor = PocketShellColors.OnAccent,
        ) {
            Text(
                text = "+",
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // Issue #49: bootstrap sheet — only attached while the ViewModel
        // holds a non-null state. Tapping Install / Skip / dismiss all
        // route through the ViewModel, which then nudges
        // `pendingNavigation.ready` and the `LaunchedEffect` above
        // navigates.
        bootstrapState?.let { state ->
            HostBootstrapSheet(
                state = state,
                hostName = bootstrapHostName,
                onInstall = { viewModel.installTmuxOnPendingHost() },
                onInstallTool = { tool -> viewModel.installBootstrapTool(tool) },
                onSetupDaemon = { viewModel.setupBootstrapDaemon() },
                onSkip = { viewModel.dismissBootstrapAndOpen() },
                onDismiss = { viewModel.dismissBootstrapAndOpen() },
                onOpenUsage = onOpenUsage?.let { route ->
                    {
                        viewModel.dismissBootstrapAndOpen()
                        route()
                    }
                },
            )
        }

        sharePayload?.let { share ->
            HostShareDialog(
                share = share,
                onDismiss = viewModel::dismissSharePayload,
                onShare = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, share.payload),
                            "Share host",
                        ),
                    )
                },
            )
        }

        pendingPassphrase?.let { request ->
            SshPassphraseDialog(
                keyName = request.key.name,
                unlockError = passphraseUnlockError,
                passphrase = passphraseText,
                onPassphraseChange = { passphraseText = it },
                onDismiss = {
                    pendingPassphrase = null
                    passphraseText = ""
                    passphraseUnlockError = null
                },
                onConnect = {
                    val passphrase = passphraseText.toCharArray()
                    pendingPassphrase = null
                    passphraseText = ""
                    passphraseUnlockError = null
                    continueWithPassphrase(
                        request.host,
                        request.key,
                        request.action,
                        passphrase,
                    )
                },
            )
        }

        HostTmuxSessionPickerSheet(
            state = hostTmuxPickerState,
            onAttach = { request, sessionName, startDirectory ->
                hostTmuxSessionPickerViewModel.dismiss()
                currentOpenTmuxHostSession(
                    request.host,
                    request.keyPath,
                    request.passphrase,
                    sessionName,
                    startDirectory,
                )
            },
            onRawSsh = { request ->
                hostTmuxSessionPickerViewModel.dismiss()
                currentOpenSession(request.host, request.keyPath, request.passphrase)
            },
            onDismiss = hostTmuxSessionPickerViewModel::dismiss,
            // Issue #109: Retry rebuilds the same SSH connect attempt
            // from the saved request; Cancel aborts the in-flight
            // connect coroutine and returns the sheet to Idle.
            onRetry = hostTmuxSessionPickerViewModel::retry,
            onCancel = hostTmuxSessionPickerViewModel::cancelLoading,
        )
    }
}

private enum class PendingPassphraseAction {
    OpenSession,
    OpenPorts,
}

private data class PendingPassphraseRequest(
    val host: HostEntity,
    val key: SshKeyEntity,
    val action: PendingPassphraseAction,
)

internal const val HOST_ROW_TAG_PREFIX = "host:row:"

/**
 * Top-of-screen banner advertising a newer GitHub Release. Tapping
 * "Update" fires `Intent.ACTION_VIEW` against the APK download URL —
 * the system browser / download manager handles the rest. We do NOT
 * silently install (no `REQUEST_INSTALL_PACKAGES` permission, no
 * background download).
 *
 * Style follows the PocketShell design language rather than vanilla
 * Material 3: the accent token tints the surface so the banner reads
 * as actionable without being alarming. A `Card` here would have
 * dragged in a Material elevation overlay that fights the dark theme.
 */
@Composable
private fun UpdateBanner(info: ReleaseInfo, onUpdate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(
                color = PocketShellColors.AccentSoft,
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.Accent,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "New version available",
                color = PocketShellColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = info.tagName,
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Pill-shaped "Update" button. Background uses the solid accent
        // so the tap target reads as primary action on top of the
        // accent-soft banner surface.
        Box(
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onUpdate)
                .background(
                    color = PocketShellColors.Accent,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Update",
                color = PocketShellColors.OnAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Footer carrying the installed `versionName`. Muted colour so the
 * marker is observable for support / debugging but doesn't compete
 * visually with the host list.
 */
@Composable
private fun VersionFooter(versionName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ReleaseChecker().renderDottedVersionLabel(versionName),
            color = PocketShellColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
        )
    }
}

/**
 * Top app bar matching `.appbar` in `docs/mockups/styles.css`. The
 * original mockup pictured a 60dp single-row bar with the "PocketShell"
 * title on the left and three text affordances on the right ("Crashes",
 * "Import", "Keys"). UI audit #108 / issue #110 found that those three
 * items read as static labels rather than tappable destinations because
 * they had no separator, underline, or active-state indicator.
 *
 * The bar is now stacked vertically:
 *
 * 1. A title row carrying the bold "PocketShell" wordmark (visual parity
 *    with the mockup).
 * 2. A Material 3 [TabRow] underneath, with "Hosts" as the always-active
 *    landing tab plus "Settings" / "Import" / "Keys" as navigation tabs.
 *    Per issue #112 the "Crashes" tab is replaced with a "Settings" tab
 *    (gear-style entry point) and the actual crash-reports surface is
 *    relocated under Settings → Diagnostics. The selected tab is rendered
 *    with the [PocketShellColors.Accent] indicator. Tapping a non-Hosts
 *    tab invokes the relevant navigation callback; the indicator does not
 *    move because the user leaves this screen entirely (and returns with
 *    "Hosts" selected again).
 *
 * `Tab` from Material 3 wraps its content in
 * `Modifier.selectable(role = Role.Tab)`, so each tab is announced to
 * TalkBack as "tab" with its selected state — no extra semantics
 * wiring needed.
 */
@Composable
private fun HostsAppBar(
    onKeysClick: () -> Unit,
    onImportHostClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Background)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "PocketShell",
                color = PocketShellColors.Text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                modifier = Modifier.weight(1f),
            )
        }

        HostsTabRow(
            selectedIndex = HOSTS_TAB_INDEX,
            onHostsClick = { /* already on Hosts; no-op */ },
            onSettingsClick = onSettingsClick,
            onImportClick = onImportHostClick,
            onKeysClick = onKeysClick,
        )
    }
}

// Issue #112: convenience alias so tests can target the Settings entry
// without depending on [HostsTabRow]'s tag-construction. Must stay
// equal to `HOSTS_TAB_TAG_PREFIX + "settings"` (see [HostsTabRow]).
internal const val SETTINGS_BUTTON_TAG = "hosts:tab:settings"

/**
 * Material 3 [TabRow] for the top-bar navigation. "Hosts" is the
 * landing tab and is selected whenever the host list is on screen;
 * tapping any other tab routes to the matching screen (Settings,
 * Import, Keys) without flipping the indicator first — the indicator
 * follows the actual visible surface.
 *
 * Issue #112 swaps the previous "Crashes" tab for a "Settings" tab
 * that opens the app-level settings surface. Crash reports are now
 * reachable under Settings → Diagnostics, matching the AC: "gear icon
 * replacing the 'Crashes' text label, with Crashes moved INSIDE
 * Settings". The text label "Settings" is intentional — the app does
 * not yet have a vector icon set; a true gear glyph follows once the
 * icon set lands.
 *
 * Custom indicator and divider colors keep the row on-brand
 * ([PocketShellColors.Accent] underline; muted divider) while the
 * [Tab] composable handles `Role.Tab` semantics and TalkBack's
 * "selected" announcement out of the box.
 */
@Composable
private fun HostsTabRow(
    selectedIndex: Int,
    onHostsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onImportClick: () -> Unit,
    onKeysClick: () -> Unit,
) {
    val tabs = listOf(
        "Hosts" to onHostsClick,
        "Settings" to onSettingsClick,
        "Import" to onImportClick,
        "Keys" to onKeysClick,
    )
    TabRow(
        selectedTabIndex = selectedIndex,
        containerColor = PocketShellColors.Background,
        contentColor = PocketShellColors.TextSecondary,
        indicator = { tabPositions ->
            if (selectedIndex < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                    height = 2.dp,
                    color = PocketShellColors.Accent,
                )
            }
        },
        divider = {
            // Suppress the default Material 3 divider — the parent
            // already draws a 1dp border around the whole app bar.
        },
        modifier = Modifier.testTag(HOSTS_TAB_ROW_TAG),
    ) {
        tabs.forEachIndexed { index, (label, onClick) ->
            val selected = index == selectedIndex
            Tab(
                selected = selected,
                onClick = onClick,
                selectedContentColor = PocketShellColors.Accent,
                unselectedContentColor = PocketShellColors.TextSecondary,
                modifier = Modifier.testTag(HOSTS_TAB_TAG_PREFIX + label.lowercase()),
                text = {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        letterSpacing = 0.2.sp,
                    )
                },
            )
        }
    }
}

internal const val HOSTS_TAB_ROW_TAG = "hosts:tabrow"
internal const val HOSTS_TAB_TAG_PREFIX = "hosts:tab:"
private const val HOSTS_TAB_INDEX = 0

/**
 * Trailing kebab (vertical three-dot) button + the [DropdownMenu] it
 * anchors. Lives in the [HostCard]'s `trailingContent` slot. Tapping
 * the icon flips [expanded] via the caller; long-press on the card
 * also calls back to flip the same state so the menu is reachable both
 * ways. The icon is drawn directly with [Canvas] to avoid pulling in
 * `material-icons-extended` for a single glyph (`Icons.Filled.MoreVert`
 * is not part of `material-icons-core`, the only ramp on our
 * classpath).
 */
@Composable
private fun HostOverflowMenuAnchor(
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onOpenPorts: () -> Unit,
    onShare: () -> Unit,
    onRecheckSetup: () -> Unit,
) {
    Box {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(role = Role.Button, onClick = onExpand)
                .testTag(HOST_OVERFLOW_BUTTON_TAG),
            contentAlignment = Alignment.Center,
        ) {
            KebabIcon()
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
        ) {
            DropdownMenuItem(
                text = { Text("Ports") },
                onClick = onOpenPorts,
            )
            DropdownMenuItem(
                text = { Text("Share") },
                onClick = onShare,
            )
            // Issue #120: manual re-probe entry point. Sits below the
            // existing items so the placement is visually stable when
            // long-press users learn the menu layout.
            DropdownMenuItem(
                text = { Text(RECHECK_SETUP_LABEL) },
                onClick = onRecheckSetup,
                modifier = Modifier.testTag(HOST_RECHECK_SETUP_ITEM_TAG),
            )
        }
    }
}

internal const val HOST_OVERFLOW_BUTTON_TAG: String = "host:overflow:button"
internal const val HOST_RECHECK_SETUP_ITEM_TAG: String = "host:overflow:recheck-setup"
internal const val RECHECK_SETUP_LABEL: String = "Re-check setup"

/**
 * Three small dots stacked vertically — the classic Android "more"
 * affordance. Drawn with [Canvas] (3 filled circles) because
 * `material-icons-core` (the only icon ramp on the classpath; see the
 * comment on `DictateDotIcon` in `SessionScreen.kt`) does not ship
 * `MoreVert`. Coloured `TextSecondary` so it reads as chrome, not a
 * primary affordance.
 */
@Composable
private fun KebabIcon() {
    val color = PocketShellColors.TextSecondary
    Canvas(modifier = Modifier.size(width = 4.dp, height = 18.dp)) {
        val r = size.width / 2f
        val gap = (size.height - 6f * r) / 2f
        drawCircle(color = color, radius = r, center = androidx.compose.ui.geometry.Offset(r, r))
        drawCircle(
            color = color,
            radius = r,
            center = androidx.compose.ui.geometry.Offset(r, 3f * r + gap),
        )
        drawCircle(
            color = color,
            radius = r,
            center = androidx.compose.ui.geometry.Offset(r, 5f * r + 2f * gap),
        )
    }
}

@Composable
private fun ShareMessageBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = PocketShellColors.Accent, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HostShareDialog(
    share: HostListViewModel.HostSharePayload,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
) {
    val qr = remember(share.payload) { HostQrCode.encode(share.payload, sizePx = 640) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share ${share.hostName}", color = PocketShellColors.Text) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "Host share QR code",
                    modifier = Modifier.size(220.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Private keys and passphrases are never included. Import requires a local key with the same name.",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onShare) {
                Text("Share text", color = PocketShellColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = PocketShellColors.TextSecondary)
            }
        },
        containerColor = PocketShellColors.Surface,
    )
}

@Composable
private fun SshPassphraseDialog(
    keyName: String,
    unlockError: String?,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SSH key passphrase", color = PocketShellColors.Text) },
        text = {
            Column {
                Text(
                    text = "Enter the passphrase for $keyName. It is used for this connection and is not saved.",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = onPassphraseChange,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Passphrase") },
                    modifier = Modifier.fillMaxWidth(),
                )
                unlockError?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, color = PocketShellColors.Red, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConnect,
                enabled = passphrase.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PocketShellColors.Accent,
                    contentColor = PocketShellColors.OnAccent,
                ),
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = PocketShellColors.TextSecondary)
            }
        },
        containerColor = PocketShellColors.Surface,
    )
}

/**
 * Section header matching `.section-label` in `docs/mockups/styles.css`:
 * 11sp uppercase letter-spaced label with a small pill carrying a count.
 */
@Composable
private fun SectionLabel(label: String, count: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            color = PocketShellColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .background(
                    color = PocketShellColors.Surface,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = count,
                color = PocketShellColors.TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Pluralised count chip for the Sessions section label — matches the
 * mockup's "4 active" / "1 active" wording. Singular vs plural is the
 * only variation; zero is unreachable here because the section is
 * gated on `sessions.isNotEmpty()` upstream.
 */
private fun sessionsCountLabel(count: Int): String =
    if (count == 1) "1 active" else "$count active"

/**
 * Empty-state when no hosts are saved. Single line + a hint to use the FAB.
 */
@Composable
private fun EmptyHostList(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No hosts yet",
                color = PocketShellColors.Text,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tap + to add an SSH host",
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
