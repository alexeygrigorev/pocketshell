package com.pocketshell.app.hosts

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.fragment.app.FragmentActivity
import com.pocketshell.app.bootstrap.HostBootstrapSheet
import com.pocketshell.app.release.ReleaseInfo
import com.pocketshell.app.sessions.SessionsDashboardViewModel
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.components.HostCard
import com.pocketshell.uikit.model.HostSetupState
import com.pocketshell.uikit.theme.PocketShellColors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Landing screen — the list of saved hosts. Visual target is
 * `docs/mockups/dashboard.html` under the "Hosts" section. Only the
 * Hosts section is rendered here; "Sessions" + "Scheduled" arrive in
 * later issues (#22 / #28).
 *
 * Layout (top-to-bottom, matching the mockup):
 *
 * - **App bar** — title "PocketShell" + settings/actions affordances.
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
    // Issue #112: Crashes affordance was moved off the top bar and now
    // lives under Settings → Diagnostics. The activity-level wiring still
    // passes this callback so the navigator can re-introduce a direct
    // entry point later (e.g. a deep link) without re-threading state.
    @Suppress("UNUSED_PARAMETER") onOpenCrashReports: () -> Unit,
    onOpenSettings: () -> Unit = {},
    /**
     * Issue #171: navigate to the per-host folder list — the default
     * destination after a host tap. Session navigation (attach /
     * continue-with-SSH) is owned by [FolderListScreen] and its onward
     * navigation in MainActivity.
     */
    onOpenFolderList: (HostEntity, keyPath: String, passphrase: CharArray?) -> Unit = { _, _, _ -> },
    onOpenPortForwardPanel: (HostEntity, keyPath: String, passphrase: CharArray?) -> Unit = { _, _, _ -> },
    /**
     * Issue #206: per-host watched-folders config screen. The kebab path
     * supplies SSH connection parameters so the discover-from-remote
     * probe can authenticate — this is the only navigation surface that
     * lights up the discover button. The Settings host picker provides
     * the same route minus credentials.
     */
    onOpenWatchedFolders: (HostEntity, keyPath: String, passphrase: CharArray?) -> Unit = { _, _, _ -> },
    /**
     * Issue #117 (usage Fix C): the bootstrap sheet's Success state can
     * route the user to the usage panel when `pocketshell` was just installed.
     * The callback is optional because Fix A owns the actual
     * `AppDestination.Usage` destination and only wires the route once
     * its branch lands; until then the call site can pass `null` and the
     * Success row falls back to a Continue-only layout.
     */
    onOpenUsage: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: HostListViewModel = hiltViewModel(),
    sessionsViewModel: SessionsDashboardViewModel = hiltViewModel(),
) {
    val hosts by viewModel.hosts.collectAsState()
    val sessions by sessionsViewModel.sessions.collectAsState()
    // Issue #201: the trailing host-card status chip needs the set of
    // hosts the app is currently attached to (i.e. has a live tmux -CC
    // client registered for). The ViewModel projects the singleton
    // [ActiveTmuxClients] registry onto a flat id-set so the derived
    // status reacts the moment a register / unregister happens.
    val attachedHostIds by viewModel.attachedHostIds.collectAsState()
    val updateInfo by viewModel.updateAvailable.collectAsState()
    val bootstrapState by viewModel.bootstrapState.collectAsState()
    val bootstrapHostName by viewModel.bootstrapHostName.collectAsState()
    val pendingNavigation by viewModel.pendingNavigation.collectAsState()
    val hostOpenProgress by viewModel.hostOpenProgress.collectAsState()
    val sharePayload by viewModel.sharePayload.collectAsState()
    val shareMessage by viewModel.shareMessage.collectAsState()
    // Issue #157 polish item 2: import-conflict prompt surfaced when an
    // inbound host already exists (same hostname:port). The dialog
    // below renders against this flow; the ViewModel pauses the import
    // write until the user picks Overwrite / Skip / Add as new.
    val importConflict by viewModel.importConflict.collectAsState()
    val recheckMessage by viewModel.recheckMessage.collectAsState()
    val setupStates by viewModel.setupStates.collectAsState()
    // Issue #116 (usage-panel Fix B): per-card chip + cross-host strip.
    val usageBadges by viewModel.usageBadges.collectAsState()
    val usageDashboardRows by viewModel.usageDashboardRows.collectAsState()
    val hasUsageInstalledHost by viewModel.hasUsageInstalledHost.collectAsState()
    // Issue #214: per-provider warning records + dismissed-this-session
    // set. The host list renders one banner per provider that warrants
    // a warning AND that the user hasn't dismissed for this app session.
    val usageWarningProviders by viewModel.usageWarningProviders.collectAsState()
    val dismissedBanners by viewModel.dismissedBanners.collectAsState()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    // Resolve-key-then-navigate is async (suspending DAO read) but the tap
    // originates from the main thread. The request is funneled through a
    // buffered Channel consumed by a LaunchedEffect — keeps suspending work out
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
    val tapRequests = remember { Channel<Long>(capacity = Channel.BUFFERED) }
    val portPanelRequests = remember { Channel<Long>(capacity = Channel.BUFFERED) }
    val recheckRequests = remember { Channel<Long>(capacity = Channel.BUFFERED) }
    // Issue #206: kebab → "Watched folders" follows the same resolve-
    // key-then-navigate pattern as Ports so the discover probe can
    // authenticate with the same one-shot biometric / passphrase
    // unlock the user already cleared for a session start.
    val watchedFoldersRequests = remember { Channel<Long>(capacity = Channel.BUFFERED) }
    val currentHosts by rememberUpdatedState(hosts)
    val currentOpenFolderList by rememberUpdatedState(onOpenFolderList)
    val currentOpenPortForwardPanel by rememberUpdatedState(onOpenPortForwardPanel)
    val currentOpenWatchedFolders by rememberUpdatedState(onOpenWatchedFolders)
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
            PendingPassphraseAction.OpenWatchedFolders -> currentOpenWatchedFolders(
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
            onError = {
                if (action == PendingPassphraseAction.OpenSession) {
                    viewModel.cancelHostOpen(host.id)
                }
                passphraseUnlockError = it
            },
        )
    }

    LaunchedEffect(Unit) {
        tapRequests.receiveAsFlow().collect { hostId ->
            val host = currentHosts.find { it.id == hostId } ?: return@collect
            val key = viewModel.keyFor(host.keyId)
            if (key == null) {
                viewModel.cancelHostOpen(host.id)
                return@collect
            }
            requestProtectedConnection(host, key, PendingPassphraseAction.OpenSession)
        }
    }
    LaunchedEffect(Unit) {
        portPanelRequests.receiveAsFlow().collect { hostId ->
            val host = currentHosts.find { it.id == hostId } ?: return@collect
            val key = viewModel.keyFor(host.keyId) ?: return@collect
            requestProtectedConnection(host, key, PendingPassphraseAction.OpenPorts)
        }
    }
    // Issue #206: watched-folders kebab item — same resolve-key-then-
    // navigate flow as Ports above.
    LaunchedEffect(Unit) {
        watchedFoldersRequests.receiveAsFlow().collect { hostId ->
            val host = currentHosts.find { it.id == hostId } ?: return@collect
            val key = viewModel.keyFor(host.keyId) ?: return@collect
            requestProtectedConnection(host, key, PendingPassphraseAction.OpenWatchedFolders)
        }
    }
    // Issue #120: "Re-check setup" kebab item. Resolves the host's key
    // path the same way the connect tap does, then asks the ViewModel
    // to invalidate the bootstrap cache and re-probe. The ViewModel
    // surfaces a one-shot acknowledgement message via [recheckMessage]
    // which renders in the share-banner slot.
    LaunchedEffect(Unit) {
        recheckRequests.receiveAsFlow().collect { hostId ->
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

    // Fire navigation once the ViewModel marks the pending route ready.
    // The ViewModel handles the cache-hit fast path (immediate ready)
    // as well as the sheet-driven slow path (ready after Skip /
    // Continue / Close).
    //
    // Issue #171: the post-tap surface is `FolderListScreen`. Its
    // "Show all sessions on this host" link expands an inline flat
    // session list in place.
    LaunchedEffect(pendingNavigation) {
        val pending = pendingNavigation
        if (pending != null && pending.ready) {
            currentOpenFolderList(pending.host, pending.keyPath, pending.passphrase)
            viewModel.consumePendingNavigation()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // App bar stays pinned at the top — it carries navigation
            // chrome and host-list actions that must remain reachable
            // while the content below scrolls.
            HostsAppBar(
                onSettingsClick = onOpenSettings,
            )

            // The landing body is one scrolling hosts-first list. Live
            // tmux sessions are still observed for host-card status chips,
            // but the old flat all-host Sessions dashboard is no longer a
            // landing surface; per-host sessions live under FolderList.
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(HOST_LIST_CONTENT_TAG),
                contentPadding = PaddingValues(
                    top = 4.dp,
                    bottom = HostListFabContentClearance,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Issue #40: surface the upgrade prompt at the top so the
                // user sees it before the host list, but only when the
                // ViewModel has confirmed a strictly-newer release.
                updateInfo?.let { info ->
                    item(key = "banner:update") {
                        UpdateBanner(
                            info = info,
                            onUpdate = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl)),
                                )
                            },
                        )
                    }
                }

                shareMessage?.let { msg ->
                    item(key = "banner:share") {
                        ShareMessageBanner(message = msg, onDismiss = viewModel::clearShareMessage)
                    }
                }

                // Issue #120: dedicated banner for the manual "Re-check
                // setup" acknowledgement. Reuses [ShareMessageBanner] for
                // visual consistency with the existing share-message banner;
                // a separate StateFlow keeps the two messages independent so
                // a host-share doesn't blow away a re-check ack and vice
                // versa.
                recheckMessage?.let { msg ->
                    item(key = "banner:recheck") {
                        ShareMessageBanner(message = msg, onDismiss = viewModel::clearRecheckMessage)
                    }
                }

                // Issue #214: dismissible in-app usage warnings, one per
                // provider that crossed the approaching / critical /
                // exceeded threshold AND that the user hasn't dismissed
                // for this app session. Banners sit above the Usage strip
                // so they read as the most prominent quota signal on the
                // host list. Tapping a banner routes to the Usage panel.
                val activeBanners = usageWarningProviders
                    .filterKeys { it !in dismissedBanners }
                    .entries
                    .sortedBy { it.key }
                if (activeBanners.isNotEmpty() && onOpenUsage != null) {
                    items(activeBanners, key = { "banner:usage-warning:" + it.key }) { entry ->
                        com.pocketshell.app.usage.UsageWarningBanner(
                            provider = entry.value,
                            onDismiss = { viewModel.dismissUsageBanner(entry.key) },
                            onTap = onOpenUsage,
                        )
                    }
                }

                // Issue #116 (usage-panel Fix B): cross-host usage strip
                // sits above the Hosts section header so the user sees the
                // at-a-glance quota state for every pocketshell-installed host
                // before scanning individual rows. The strip is gated on
                // `hasUsageInstalledHost` so a workspace with no pocketshell
                // hosts never renders an empty rail. Tapping routes to
                // `AppDestination.Usage` via `onOpenUsage`.
                if (hasUsageInstalledHost && onOpenUsage != null) {
                    item(key = "usage:strip") {
                        com.pocketshell.app.usage.UsageDashboardStrip(
                            rows = usageDashboardRows,
                            onClick = onOpenUsage,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .testTag(USAGE_DASHBOARD_STRIP_TAG),
                        )
                    }
                }

                item(key = "label:hosts") {
                    SectionLabel(
                        label = "Hosts",
                        count = "${hosts.size} saved",
                    )
                }

                if (hosts.isEmpty()) {
                    item(key = "hosts:empty") {
                        EmptyHostList()
                    }
                } else {
                    items(hosts, key = { "host:" + it.id }) { host ->
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
                            // Issue #155: the per-host usage record is
                            // no longer rendered as an inline chip in the
                            // primary status row — it would compete with
                            // the setup-state badge for attention while
                            // the cross-host Usage dashboard strip ABOVE
                            // this list already surfaces blocked state.
                            // Instead the record (when present) is
                            // surfaced inside the kebab overflow menu so
                            // a user who long-presses / taps the kebab
                            // sees the per-host quota status alongside
                            // Ports / Share / Re-check setup.
                            val usageRecord = usageBadges[host.id]
                            // Issue #201: resolve the per-host status from
                            // the setup-probe state, the cross-host
                            // session aggregate, and the registered tmux
                            // client set. The derivation is intentionally
                            // a pure function (see [resolveHostStatus] in
                            // HostListViewModel.kt) so unit tests can
                            // exercise every label without a UI.
                            val hostStatus = resolveHostStatus(
                                hostId = host.id,
                                setupState = setupState,
                                sessions = sessions,
                                attachedHostIds = attachedHostIds,
                            )
                            HostCard(
                                name = host.name,
                                subtitle = "${host.username}@${host.hostname}:${host.port}",
                                status = hostStatus,
                                onClick = {
                                    if (viewModel.beginHostOpen(host.id, host.name)) {
                                        tapRequests.trySend(host.id)
                                    }
                                },
                                // Issue #113: long-press now opens the same
                                // overflow menu the kebab exposes — gives
                                // users two equivalent ways to reach the
                                // secondary actions. Wired through
                                // `combinedClickable` inside `HostCard`.
                                onLongClick = { menuOpen = true },
                                setupState = setupState,
                                onSetupBadgeClick = if (
                                    setupState == HostSetupState.NeedsSetup ||
                                    setupState == HostSetupState.CliUpdateNeeded
                                ) {
                                    // Issue #120: tapping a `needs setup`
                                    // badge opens the existing bootstrap
                                    // sheet through the standard tap-to-
                                    // connect path. `bootstrapHost` is
                                    // cache-aware: a `needsSetup` row has
                                    // either `tmuxInstalled == false` or
                                    // `pocketshellInstalled == false`, both of
                                    // which surface the sheet when the
                                    // probe re-runs.
                                    {
                                        if (viewModel.beginHostOpen(host.id, host.name)) {
                                            tapRequests.trySend(host.id)
                                        }
                                    }
                                } else null,
                                connectingLabel = hostOpenProgress
                                    ?.takeIf { it.hostId == host.id }
                                    ?.phase
                                    ?.label,
                                trailingContent = {
                                    HostOverflowMenuAnchor(
                                        expanded = menuOpen,
                                        onExpand = { menuOpen = true },
                                        onDismiss = { menuOpen = false },
                                        usageRecord = usageRecord,
                                        usageBadgeTestTag = HOST_USAGE_BADGE_TAG_PREFIX + host.id,
                                        onOpenPorts = {
                                            menuOpen = false
                                            portPanelRequests.trySend(host.id)
                                        },
                                        onOpenWatchedFolders = {
                                            menuOpen = false
                                            watchedFoldersRequests.trySend(host.id)
                                        },
                                        onShare = {
                                            menuOpen = false
                                            viewModel.createSharePayload(host)
                                        },
                                        onRecheckSetup = {
                                            menuOpen = false
                                            recheckRequests.trySend(host.id)
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
        }

        FloatingActionButton(
            onClick = onAddHost,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
                .size(56.dp)
                .testTag(HOST_LIST_ADD_FAB_TAG),
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

        importConflict?.let { conflict ->
            ImportConflictDialog(
                conflict = conflict,
                onOverwrite = {
                    viewModel.resolveImportConflict(ImportConflictResolution.Overwrite)
                },
                onSkip = {
                    viewModel.resolveImportConflict(ImportConflictResolution.Skip)
                },
                onAddAsNew = {
                    viewModel.resolveImportConflict(ImportConflictResolution.AddAsNew)
                },
                onDismiss = viewModel::dismissImportConflict,
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
                    if (request.action == PendingPassphraseAction.OpenSession) {
                        viewModel.cancelHostOpen(request.host.id)
                    }
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

        // Issue #171: the post-tap surface is [FolderListScreen]; the
        // dashboard screen does not own a session picker. The in-session
        // "switch session" drawer lives in `TmuxSessionScreen`.
    }
}

private enum class PendingPassphraseAction {
    OpenSession,
    OpenPorts,

    /**
     * Issue #206: route to the per-host watched-folders config screen.
     * Same passphrase-protected unlock as [OpenPorts] so the user only
     * has to authenticate once for all per-host destinations that need
     * SSH credentials.
     */
    OpenWatchedFolders,
}

private data class PendingPassphraseRequest(
    val host: HostEntity,
    val key: SshKeyEntity,
    val action: PendingPassphraseAction,
)

private val HostListFabContentClearance = 104.dp

internal const val HOST_LIST_CONTENT_TAG = "host-list:content"
internal const val HOST_ROW_TAG_PREFIX = "host:row:"

/**
 * Issue #144: stable test tag for the bottom-right "+" FloatingActionButton
 * that opens the Add Host form. The FAB carries the only primary tap target
 * for "add a host" — the empty-state copy intentionally has no button — so
 * the cold-install E2E test depends on this tag rather than locating the
 * button by its `+` glyph.
 */
internal const val HOST_LIST_ADD_FAB_TAG = "host-list:add-fab"

/**
 * Issue #144: stable test tag wrapping the "No hosts yet" empty state. The
 * cold-install E2E test asserts the empty state is visible on first launch
 * (no hosts in the DB) before tapping the FAB.
 */
internal const val HOST_LIST_EMPTY_STATE_TAG = "host-list:empty-state"

/** Issue #116: stable test tag for the cross-host usage dashboard strip. */
internal const val USAGE_DASHBOARD_STRIP_TAG = "usage:dashboard-strip"

/**
 * Issue #157: stable test tags for the import-conflict dialog. The
 * dialog tag wraps the body Column; the per-button tags ride on the
 * confirm / dismiss `TextButton`s so a UI test can resolve a specific
 * resolution without depending on label wording.
 */
internal const val IMPORT_CONFLICT_DIALOG_TAG = "host-list:import-conflict"
internal const val IMPORT_CONFLICT_OVERWRITE_TAG = "host-list:import-conflict:overwrite"
internal const val IMPORT_CONFLICT_SKIP_TAG = "host-list:import-conflict:skip"
internal const val IMPORT_CONFLICT_ADD_AS_NEW_TAG = "host-list:import-conflict:add-as-new"

/**
 * Issue #116 / #155: stable test tag for the per-host blocked /
 * near-limit chip. Originally rendered next to the setup-state badge
 * on [HostCard] via the card's `usageBadge` slot. Issue #155 demoted
 * the chip OFF the primary status row to reduce scanning friction
 * (the cross-host Usage dashboard strip already surfaces blocked
 * state). The chip now lives inside the kebab overflow menu — see
 * [HostOverflowMenuAnchor] — and keeps the same test tag so existing
 * instrumentation that targets it stays valid.
 */
internal const val HOST_USAGE_BADGE_TAG_PREFIX = "host:usage-badge:"

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
 * Top app bar matching the original 60dp dashboard chrome: the
 * "PocketShell" wordmark stays on the left and Settings is the rightmost
 * gear button.
 * Issue #299 removes the previous pseudo-tab row because only "Hosts"
 * was a real selected destination; issue #290 moved QR scanning into
 * Add host, and issue #388 moved host import into Settings so it is not
 * mistaken for a generic app import.
 */
@Composable
private fun HostsAppBar(
    onSettingsClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(PocketShellColors.Background)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "PocketShell",
            color = PocketShellColors.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TopBarIconButton(
            contentDescription = "Settings",
            testTag = SETTINGS_BUTTON_TAG,
            onClick = onSettingsClick,
        ) {
            SettingsGearIcon()
        }
    }
}

// Stable tag for the host-list Settings affordance. The value is kept
// from the old pseudo-tab so existing connected tests can keep targeting
// Settings without churn.
internal const val SETTINGS_BUTTON_TAG = "hosts:tab:settings"

@Composable
private fun TopBarIconButton(
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color = PocketShellColors.SurfaceElev, shape = CircleShape)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun SettingsGearIcon() {
    val color = PocketShellColors.TextSecondary
    Canvas(modifier = Modifier.size(20.dp)) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val minSide = min(size.width, size.height)
        val toothRadius = minSide * 0.055f
        val toothDistance = minSide * 0.37f
        repeat(8) { index ->
            val angle = index * PI.toFloat() / 4f
            drawCircle(
                color = color,
                radius = toothRadius,
                center = androidx.compose.ui.geometry.Offset(
                    x = center.x + cos(angle) * toothDistance,
                    y = center.y + sin(angle) * toothDistance,
                ),
            )
        }
        drawCircle(
            color = color,
            radius = minSide * 0.28f,
            center = center,
            style = Stroke(width = minSide * 0.12f),
        )
        drawCircle(
            color = color,
            radius = minSide * 0.075f,
            center = center,
        )
    }
}

/**
 * Trailing kebab (vertical three-dot) button + the [DropdownMenu] it
 * anchors. Lives in the [HostCard]'s `trailingContent` slot. Tapping
 * the icon flips [expanded] via the caller; long-press on the card
 * also calls back to flip the same state so the menu is reachable both
 * ways. The icon is drawn directly with [Canvas] to avoid pulling in
 * `material-icons-extended` for a single glyph (`Icons.Filled.MoreVert`
 * is not part of `material-icons-core`, the only ramp on our
 * classpath).
 *
 * Issue #155: the 40 dp tap target now carries a permanent visible
 * affordance — a circular [PocketShellColors.SurfaceElev] background
 * with a 1 dp [PocketShellColors.BorderSoft] hairline border —
 * matching the design-system §8 requirement that the kebab read as
 * "always visible affordance". Previously the icon was drawn directly
 * on the card surface, which made it easy to miss against the host's
 * setup-state badge and avatar colour. The same issue also moves the
 * usage record into this menu so it no longer competes with the
 * setup-state badge in the row.
 */
@Composable
private fun HostOverflowMenuAnchor(
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    usageRecord: com.pocketshell.core.usage.UsageProviderRecord?,
    usageBadgeTestTag: String,
    onOpenPorts: () -> Unit,
    onOpenWatchedFolders: () -> Unit,
    onShare: () -> Unit,
    onRecheckSetup: () -> Unit,
) {
    Box {
        // Issue #155: render the kebab inside a 40 dp circular
        // SurfaceElev container with a 1 dp BorderSoft hairline border
        // so the affordance is visually obvious. The design-system §8
        // re-spec explicitly calls for a "circular (20 dp radius)"
        // kebab; the hairline border keeps it on the same chrome
        // language as the host card (Δ3 — borders, not shadows).
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color = PocketShellColors.SurfaceElev, shape = CircleShape)
                .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = CircleShape)
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
            // Issue #155: per-host usage status surfaced as the first
            // menu entry when the scheduler has a blocked / near-limit
            // record for the host. Rendered as a non-clickable header
            // row (no `onClick`) so it reads as state rather than an
            // action — the user can already drill into the cross-host
            // Usage dashboard from the strip above the host list. The
            // pill is wrapped in a Box carrying the existing
            // `host:usage-badge:<id>` test tag so instrumentation that
            // previously located the inline chip keeps working.
            if (usageRecord != null) {
                DropdownMenuItem(
                    enabled = false,
                    text = {
                        Box(modifier = Modifier.testTag(usageBadgeTestTag)) {
                            com.pocketshell.app.usage.UsageSessionBlockedBadge(
                                provider = usageRecord,
                            )
                        }
                    },
                    onClick = {},
                )
            }
            DropdownMenuItem(
                text = { Text("Ports") },
                onClick = onOpenPorts,
            )
            // Issue #206: per-host watched-folders configuration.
            // Placed above Share so the quick-access config row sits
            // next to Ports (other per-host config) rather than mixed
            // with sharing / diagnostics affordances.
            DropdownMenuItem(
                text = { Text("Watched folders") },
                onClick = onOpenWatchedFolders,
                modifier = Modifier.testTag(HOST_WATCHED_FOLDERS_ITEM_TAG),
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
// Issue #206: stable tag for the kebab → "Watched folders" entry so
// the connected E2E test can navigate from the host list to the
// config screen without depending on free-form text.
const val HOST_WATCHED_FOLDERS_ITEM_TAG: String = "host:overflow:watched-folders"
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

/**
 * Issue #157 polish item 2: confirmation dialog shown when an inbound
 * host import (QR scan, file pick, clipboard paste) matches the
 * `(hostname, port)` of an existing row. Mirrors the
 * [HostShareDialog]/[DiscardChangesDialog] visual conventions so the
 * three confirmation surfaces feel consistent.
 *
 * Three resolutions are exposed: Overwrite (update the existing row in
 * place, preserves its id), Skip (drop the inbound payload), Add as
 * new (insert anyway). The system-back / scrim tap dismisses without
 * writing — equivalent to Skip but without the "Skipped X" toast so a
 * mistaken open of the dialog leaves no audit trail.
 */
@Composable
private fun ImportConflictDialog(
    conflict: HostListViewModel.ImportConflict,
    onOverwrite: () -> Unit,
    onSkip: () -> Unit,
    onAddAsNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val endpoint = "${conflict.incoming.hostname}:${conflict.incoming.port}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Already added: ${conflict.existing.name}",
                color = PocketShellColors.Text,
            )
        },
        text = {
            Column(modifier = Modifier.testTag(IMPORT_CONFLICT_DIALOG_TAG)) {
                Text(
                    text = "This QR code matches the saved host “${conflict.existing.name}” at $endpoint.",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Importing as “${conflict.incoming.name}” " +
                        "(${conflict.incoming.username}@$endpoint).",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Update replaces the saved host in place. Add as new keeps both. " +
                        "Already added leaves the saved host unchanged.",
                    color = PocketShellColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onOverwrite,
                modifier = Modifier.testTag(IMPORT_CONFLICT_OVERWRITE_TAG),
            ) {
                Text("Update", color = PocketShellColors.Accent)
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onAddAsNew,
                    modifier = Modifier.testTag(IMPORT_CONFLICT_ADD_AS_NEW_TAG),
                ) {
                    Text("Add as new", color = PocketShellColors.TextSecondary)
                }
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.testTag(IMPORT_CONFLICT_SKIP_TAG),
                ) {
                    Text("Already added", color = PocketShellColors.TextSecondary)
                }
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
 * Empty-state when no hosts are saved. Single line + a hint to use the FAB.
 */
@Composable
private fun EmptyHostList(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag(HOST_LIST_EMPTY_STATE_TAG),
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
