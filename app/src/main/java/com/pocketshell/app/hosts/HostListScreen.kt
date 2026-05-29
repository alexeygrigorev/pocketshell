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
import com.pocketshell.app.sessions.DASHBOARD_KILL_ERROR_BANNER_TAG
import com.pocketshell.app.sessions.SessionsDashboardDialog
import com.pocketshell.app.sessions.SessionsDashboardViewModel
import com.pocketshell.app.sessions.rememberSessionsDashboardSectionState
import com.pocketshell.app.sessions.sessionsDashboardItems
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.components.HostCard
import com.pocketshell.uikit.model.HostSetupState
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
    // Issue #129: live QR scanner destination. Optional so older callers
    // that haven't wired the destination yet keep compiling, but the
    // production nav graph in `MainActivity` always supplies it.
    onOpenScan: () -> Unit = {},
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
    onOpenTmuxSession: (ActiveTmuxClients.Entry, sessionName: String, startDirectory: String?) -> Unit =
        { _, _, _ -> },
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
    val sharePayload by viewModel.sharePayload.collectAsState()
    val shareMessage by viewModel.shareMessage.collectAsState()
    // Issue #157 polish item 2: import-conflict prompt surfaced when an
    // inbound host already exists (same hostname:port). The dialog
    // below renders against this flow; the ViewModel pauses the import
    // write until the user picks Overwrite / Skip / Add as new.
    val importConflict by viewModel.importConflict.collectAsState()
    val recheckMessage by viewModel.recheckMessage.collectAsState()
    // Issue #168: surface dashboard kill failures here so the banner sits
    // alongside the share/recheck banners (the dashboard ViewModel owns the
    // kill but HostListScreen owns the only Scaffold-shaped column).
    val killError by sessionsViewModel.killError.collectAsState()
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
    // Issue #206: kebab → "Watched folders" follows the same resolve-
    // key-then-navigate pattern as Ports so the discover probe can
    // authenticate with the same one-shot biometric / passphrase
    // unlock the user already cleared for a session start.
    val watchedFoldersRequests = remember { MutableSharedFlow<Long>(extraBufferCapacity = 4) }
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
    // Issue #206: watched-folders kebab item — same resolve-key-then-
    // navigate flow as Ports above.
    LaunchedEffect(Unit) {
        watchedFoldersRequests.collect { hostId ->
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

    // Issues #268 / #269: hoist the Sessions-section UI state out of the
    // (now removed) self-contained section composable so it survives across
    // the single screen-level LazyColumn's items and the dialog overlay.
    val sessionsSectionState = rememberSessionsDashboardSectionState()
    val createError by sessionsViewModel.createError.collectAsState()
    // System-clock-derived "now" for relative-time formatting. Recomputed
    // on each recomposition; the dashboard already recomposes on every
    // poll so this stays fresh enough for the `2m / 8m` cadence.
    val nowSec = System.currentTimeMillis() / 1000L

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // App bar stays pinned at the top — it carries navigation
            // chrome (title + tab row) that must remain reachable while
            // the content below scrolls.
            HostsAppBar(
                onKeysClick = onManageKeys,
                onImportHostClick = { hostSharePicker.launch("*/*") },
                onScanClick = onOpenScan,
                onSettingsClick = onOpenSettings,
            )

            // Issues #268 / #269: fold the banners, Sessions section, usage
            // strip, and Hosts list into ONE scrolling LazyColumn so the
            // whole dashboard scrolls as a single surface. Previously the
            // Sessions section was an unbounded non-scrolling Column that,
            // at high session counts, clipped its own overflow and starved
            // the Hosts LazyColumn (`weight(1f)`) to ~0 height. With a
            // single list every session row AND every host row is always
            // reachable regardless of session count, and the section-scoped
            // `+` FAB that used to overlap the screen FAB is gone.
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp),
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

                // Issue #168: surface dashboard kill failures inline so the
                // user can tell a silent failure apart from "kill worked but
                // the row didn't refresh yet". Reuses [ShareMessageBanner]
                // for visual consistency with the other one-shot banners.
                killError?.let { msg ->
                    item(key = "banner:kill-error") {
                        Box(modifier = Modifier.testTag(DASHBOARD_KILL_ERROR_BANNER_TAG)) {
                            ShareMessageBanner(
                                message = msg,
                                onDismiss = sessionsViewModel::clearKillError,
                            )
                        }
                    }
                }

                // Issue #46: cross-host session dashboard. Render the
                // Sessions section ABOVE the Hosts section per the mockup
                // at `docs/mockups/dashboard.html`, but only when there is
                // at least one live tmux session on at least one connected
                // host. Empty → the chrome (label + rows) collapses
                // entirely so the host list keeps the visual hierarchy of
                // an "empty workspace" landing.
                if (sessions.isNotEmpty()) {
                    item(key = "label:sessions") {
                        SectionLabel(
                            label = "Sessions",
                            count = sessionsCountLabel(sessions.size),
                        )
                    }
                }
                // Issues #268 / #269: emit the Sessions section as LazyColumn
                // items (header + legend + create-error banner + rows). The
                // helper itself returns early when there are no sessions and
                // no pending create error, so the label above and these items
                // collapse together.
                sessionsDashboardItems(
                    state = sessionsSectionState,
                    sessions = sessions,
                    createError = createError,
                    nowSec = nowSec,
                    entryFor = sessionsViewModel::entryFor,
                    onClearCreateError = sessionsViewModel::clearCreateError,
                    onOpenTmuxSession = onOpenTmuxSession,
                )

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
                                    // `pocketshellInstalled == false`, both of
                                    // which surface the sheet when the
                                    // probe re-runs.
                                    { tapRequests.tryEmit(host.id) }
                                } else null,
                                trailingContent = {
                                    HostOverflowMenuAnchor(
                                        expanded = menuOpen,
                                        onExpand = { menuOpen = true },
                                        onDismiss = { menuOpen = false },
                                        usageRecord = usageRecord,
                                        usageBadgeTestTag = HOST_USAGE_BADGE_TAG_PREFIX + host.id,
                                        onOpenPorts = {
                                            menuOpen = false
                                            portPanelRequests.tryEmit(host.id)
                                        },
                                        onOpenWatchedFolders = {
                                            menuOpen = false
                                            watchedFoldersRequests.tryEmit(host.id)
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

                // Footer: installed version. Sits at the bottom of the
                // list so the host list keeps its prominence; muted text
                // colour so it doesn't compete with the FAB.
                item(key = "footer:version") {
                    VersionFooter(versionName = versionName)
                }
            }
        }

        // Issues #268 / #269: the Sessions section's lifecycle dialog
        // (create / rename / kill) renders here, outside the LazyColumn, so
        // it floats above the whole screen rather than scrolling with a row.
        SessionsDashboardDialog(
            state = sessionsSectionState,
            entryFor = sessionsViewModel::entryFor,
            onCreateSession = { entry, name, startDirectory ->
                sessionsViewModel.createSession(
                    entry = entry,
                    name = name,
                    startDirectory = startDirectory,
                )
            },
            onRenameSession = { entry, oldName, newName ->
                sessionsViewModel.renameSession(
                    entry = entry,
                    oldName = oldName,
                    newName = newName,
                )
            },
            onKillSession = { entry, name ->
                sessionsViewModel.killSession(entry, name)
            },
        )

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
    onScanClick: () -> Unit,
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
            onScanClick = onScanClick,
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
    onScanClick: () -> Unit,
    onKeysClick: () -> Unit,
) {
    // Issue #129: a dedicated "Scan" tab sits next to "Import" so the
    // user can pick between camera-scan and pick-a-file flows from the
    // same row. The two tabs do related work but the user surfaces are
    // different enough — file picker vs camera permission prompt — that
    // bundling them under a single entry point would hide the camera
    // affordance.
    val tabs = listOf(
        "Hosts" to onHostsClick,
        "Settings" to onSettingsClick,
        "Import" to onImportClick,
        "Scan" to onScanClick,
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
                text = "Host already exists",
                color = PocketShellColors.Text,
            )
        },
        text = {
            Column(modifier = Modifier.testTag(IMPORT_CONFLICT_DIALOG_TAG)) {
                Text(
                    text = "A host with endpoint $endpoint is already saved as " +
                        "“${conflict.existing.name}”.",
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
                    text = "Overwrite replaces the existing row in place. " +
                        "Add as new keeps both. Skip discards the import.",
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
                Text("Overwrite", color = PocketShellColors.Accent)
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
                    Text("Skip", color = PocketShellColors.TextSecondary)
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
