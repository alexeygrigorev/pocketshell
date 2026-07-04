package com.pocketshell.app.hosts

import android.content.Context
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

import androidx.compose.foundation.Canvas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.fragment.app.FragmentActivity
import com.pocketshell.app.bootstrap.HostBootstrapSheet
import com.pocketshell.app.portfwd.ForwardingGlyph
import com.pocketshell.app.release.ReleaseInfo
import com.pocketshell.app.release.launchUpdateDownload
import com.pocketshell.app.sessions.SessionsDashboardViewModel
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.HostCard
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.Pill
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.model.HostSetupState
import com.pocketshell.uikit.model.HostStatus
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import com.pocketshell.uikit.theme.PocketShellTypography
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
 * [TmuxSessionViewModel][com.pocketshell.app.tmux.TmuxSessionViewModel]
 * runs the connect under its own Hilt scope.
 *
 * `onEditHost` routes to the edit-host screen by host id. The
 * long-press → Edit affordance was dropped in #38 when long-press
 * became "open the kebab overflow menu" (#113), which silently
 * orphaned this route. Issue #519 restores a discoverable entry point
 * via the kebab → "Edit" item; the callback fires when that item is
 * tapped.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    onAddHost: () -> Unit,
    onEditHost: (Long) -> Unit,
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
    /**
     * Issue #446 (epic #432 slice D): open the port-forward panel entry
     * from the global "ports forwarding" indicator in the app bar. The
     * indicator only renders when ≥1 host is actively forwarding, so this
     * routes to the same chooser the QS tile + notification deep-link use.
     */
    onOpenPortForwarding: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HostListViewModel = hiltViewModel(),
    sessionsViewModel: SessionsDashboardViewModel = hiltViewModel(),
    forwardingIndicatorViewModel: com.pocketshell.app.portfwd.ForwardingIndicatorViewModel = hiltViewModel(),
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
    // Issue #476: one-shot feedback for the update-banner tap so a working
    // download no longer looks like a silent no-op.
    val updateMessage by viewModel.updateMessage.collectAsState()
    // Issue #515: surfaces a "couldn't check for updates — Retry" note when
    // the GitHub-Releases poll genuinely failed (vs simply finding no newer
    // release), so a cold-launch rate-limit / network blip no longer vanishes
    // silently.
    val updateCheckFailed by viewModel.updateCheckFailed.collectAsState()
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
    val appUpdateWarning by viewModel.appUpdateWarning.collectAsState()
    val setupStates by viewModel.setupStates.collectAsState()
    // Issue #116 (usage-panel Fix B): per-card warning record in the kebab.
    val usageBadges by viewModel.usageBadges.collectAsState()
    // Issue #214: per-provider warning records + dismissed-this-session
    // set. The host list renders one banner per provider that warrants
    // a warning AND that the user hasn't dismissed for this app session.
    val usageWarningProviders by viewModel.usageWarningProviders.collectAsState()
    val dismissedBanners by viewModel.dismissedBanners.collectAsState()
    val forwardingIndicator by forwardingIndicatorViewModel.state.collectAsState()
    // Issue #1241: the glanceable most-constraining usage percent, read from
    // the SAME cached scheduler snapshots the warning banners use (no new
    // fetch). Null while there's no usable reading — the pill is then hidden.
    val usageGlancePill by viewModel.usageGlancePill.collectAsState()
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
                hostCount = hosts.size,
                activeSessionCount = sessions.count { it.attached },
                onSettingsClick = onOpenSettings,
                forwardingIndicator = forwardingIndicator,
                onForwardingIndicatorClick = onOpenPortForwarding,
                // Issue #1241: the glance pill only lights up when there's a
                // cached reading AND a Usage route is wired (mirrors the
                // banner / kebab gate).
                usageGlancePill = usageGlancePill,
                onOpenUsage = onOpenUsage,
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
                    top = PocketShellSpacing.xs,
                    bottom = HostListFabContentClearance,
                ),
                verticalArrangement = Arrangement.spacedBy(PocketShellDensity.sectionGap),
            ) {
                // Issue #418 (dashboard declutter): the top chrome
                // (update / share / re-check / usage-warning banners and
                // the cross-host usage strip) used to be one LazyColumn
                // `item` each, so every banner also paid the column's
                // inter-item gap. With up to five of them stacking, the
                // first host card was pushed below the fold on a Pixel 7
                // — the maintainer's "too crowded" complaint. We keep
                // every functional affordance but collapse them into a
                // single compact notices block with tight internal
                // spacing so they read as one strip above the Hosts
                // label instead of a tall stack of separate cards. The
                // mockup (`dashboard.html`) has none of this chrome, so
                // condensing — not removing — is the conservative middle
                // ground.
                val activeUsageBanners = usageWarningProviders
                    .filterKeys { it !in dismissedBanners }
                    .entries
                    .sortedBy { it.key }
                val hasUpdateBanner = updateInfo != null
                val hasUpdateMessageBanner = updateMessage != null
                // Issue #515: the check failed (vs found no update) — show a
                // visible retry instead of a silent no-op.
                val hasUpdateCheckFailedBanner = updateCheckFailed != null
                val hasShareBanner = shareMessage != null
                val hasRecheckBanner = recheckMessage != null
                val hasUsageWarningBanners = activeUsageBanners.isNotEmpty() && onOpenUsage != null
                // Issue #514: remote pocketshell CLI is newer than this app
                // build. A minor delta rarely breaks anything, so the host
                // stays fully usable — this banner is the only surfaced
                // difference (dismissible, non-blocking, no setup framing).
                val hasAppUpdateWarning = appUpdateWarning != null
                val hasAnyNotice = hasUpdateBanner || hasUpdateMessageBanner ||
                    hasUpdateCheckFailedBanner ||
                    hasShareBanner || hasRecheckBanner ||
                    hasUsageWarningBanners || hasAppUpdateWarning
                if (hasAnyNotice) {
                    item(key = "notices") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(HOST_LIST_NOTICES_TAG),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // Issue #40: upgrade prompt, only when the
                            // ViewModel confirmed a strictly-newer release.
                            updateInfo?.let { info ->
                                UpdateBanner(
                                    info = info,
                                    // Issue #515: the ACTION_VIEW → APK launch
                                    // (with download-started / failure feedback
                                    // and the release-page fallback) is shared
                                    // with the #514 "app is behind" banner so
                                    // both offer the exact same sideload path.
                                    onUpdate = { launchApkDownload(context, info, viewModel) },
                                )
                            }

                            // Issue #476: result of the most recent update
                            // tap. Reuses the dismissible banner so the
                            // "download started" / "couldn't start" feedback
                            // reads consistently with the other notices.
                            updateMessage?.let { msg ->
                                ShareMessageBanner(
                                    message = msg,
                                    onDismiss = viewModel::clearUpdateMessage,
                                )
                            }

                            // Issue #515: the GitHub-Releases poll genuinely
                            // failed (non-200 / rate-limit / network blip /
                            // unparseable body) rather than finding no newer
                            // release. Surface a visible, dismissible note with
                            // a Retry that re-runs the check, so a single bad
                            // cold-launch moment is no longer a silent no-op.
                            updateCheckFailed?.let { failure ->
                                Box(modifier = Modifier.testTag(HOST_LIST_UPDATE_CHECK_FAILED_TAG)) {
                                    UpdateCheckFailedBanner(
                                        reason = failure.reason,
                                        onRetry = viewModel::checkForUpdates,
                                        onDismiss = viewModel::dismissUpdateCheckFailure,
                                    )
                                }
                            }

                            shareMessage?.let { msg ->
                                ShareMessageBanner(
                                    message = msg,
                                    onDismiss = viewModel::clearShareMessage,
                                )
                            }

                            // Issue #120: manual "Re-check setup"
                            // acknowledgement. Reuses [ShareMessageBanner]
                            // for visual consistency; a separate StateFlow
                            // keeps the two messages independent.
                            recheckMessage?.let { msg ->
                                ShareMessageBanner(
                                    message = msg,
                                    onDismiss = viewModel::clearRecheckMessage,
                                )
                            }

                            // Issue #514: soft, dismissible "remote
                            // pocketshell CLI newer than this app" note.
                            // Reuses [ShareMessageBanner] so it reads as a
                            // quiet inline notice, not a sheet. The host
                            // stays fully usable; this is the only surfaced
                            // difference. No installer, no setup framing.
                            appUpdateWarning?.let { warning ->
                                Box(modifier = Modifier.testTag(HOST_LIST_APP_UPDATE_WARNING_TAG)) {
                                    // Issue #515: the host probe proved this
                                    // app is behind the remote CLI — the
                                    // strongest "you should update" signal. If
                                    // a real downloadable GitHub release was
                                    // resolved, offer an actionable OPTIONAL
                                    // "Update" (same ACTION_VIEW → APK path as
                                    // the standalone UpdateBanner). The host is
                                    // already fully usable, so this never
                                    // blocks; when resolution fails the
                                    // banner shows a visible Retry instead of
                                    // passive text.
                                    AppUpdateWarningBanner(
                                        warning = warning,
                                        onUpdate = warning.releaseInfo?.let { info ->
                                            { launchApkDownload(context, info, viewModel) }
                                        },
                                        onRetry = viewModel::retryAppUpdateWarningRelease,
                                        onDismiss = viewModel::dismissAppUpdateWarning,
                                    )
                                }
                            }

                            // Issue #214: dismissible in-app usage
                            // warnings, one per provider over threshold
                            // that the user hasn't dismissed this session.
                            if (hasUsageWarningBanners) {
                                activeUsageBanners.forEach { entry ->
                                    com.pocketshell.app.usage.UsageWarningBanner(
                                        provider = entry.value,
                                        onDismiss = { viewModel.dismissUsageBanner(entry.key) },
                                        onTap = onOpenUsage,
                                    )
                                }
                            }

                            // Issue #483 / #506: the cross-host usage strip
                            // that used to sit here was removed, and the
                            // per-host usage chip that briefly replaced it
                            // (under each host card) was also dropped — both
                            // read as a cryptic floating row. Usage is
                            // server-tied and stays reachable per-host via the
                            // kebab → "Usage" item.
                        }
                    }
                }

                item(key = "label:hosts") {
                    // #479 Slice A: the host group label rides the shared
                    // SectionHeader (title-case `Hosts · N` inline) instead of
                    // the old UPPERCASE label + count pill.
                    SectionHeader(
                        label = "Hosts",
                        count = hosts.size,
                        modifier = Modifier.padding(horizontal = PocketShellSpacing.xs),
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
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PocketShellSpacing.lg),
                            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
                        ) {
                            // Issue #120: derive the per-host setup state from
                            // the ViewModel's persisted-column projection.
                            // Default to Unknown if the row isn't yet in the
                            // map (race-free fallback while the DAO emission
                            // catches up).
                            val setupState = setupStates[host.id] ?: HostSetupState.Unknown
                            // Issue #155: the per-host usage *warning* record
                            // (blocked / near-limit) is surfaced inside the
                            // kebab overflow menu so it doesn't compete with
                            // the setup-state badge in the primary status row.
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
                            val openingLabel = hostOpenProgress
                                ?.takeIf { it.hostId == host.id }
                                ?.phase
                                ?.label
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
                                connectingLabel = openingLabel,
                                trailingContent = {
                                    HostOverflowMenuAnchor(
                                        expanded = menuOpen,
                                        onExpand = { menuOpen = true },
                                        onDismiss = { menuOpen = false },
                                        hostStatus = hostStatus,
                                        setupState = setupState,
                                        openingLabel = openingLabel,
                                        usageRecord = usageRecord,
                                        usageBadgeTestTag = HOST_USAGE_BADGE_TAG_PREFIX + host.id,
                                        // Issue #519: restore the host-edit
                                        // entry point — routes to the
                                        // (already wired) EditHost destination.
                                        onEdit = {
                                            menuOpen = false
                                            onEditHost(host.id)
                                        },
                                        // Issue #483: discoverable, labelled
                                        // per-host entry to the Usage detail.
                                        // Issue #506: this kebab item is now
                                        // the sole host-list usage affordance.
                                        // Only wired when the nav graph
                                        // supplied a route (mirrors the banner
                                        // gate).
                                        onOpenUsage = onOpenUsage?.let { route ->
                                            {
                                                menuOpen = false
                                                route()
                                            }
                                        },
                                        usageMenuItemTestTag = HOST_USAGE_MENU_ITEM_TAG_PREFIX + host.id,
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
                            // Issue #506: the per-host usage chip that used to
                            // render here was dropped — it read as a cryptic
                            // floating row under the card. Usage stays one tap
                            // away via the kebab → "Usage" item.
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
                                // Issue #1230: a payload may split into several
                                // QR envelope parts; share every one (newline
                                // separated) so a text import can reassemble.
                                .putExtra(
                                    Intent.EXTRA_TEXT,
                                    share.payloads.joinToString("\n"),
                                ),
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
 * Issue #418: stable test tag for the single compact "notices" block
 * that collapses the update / share / re-check / usage-warning banners
 * above the Hosts label, so they no longer stack as separate full-gap
 * LazyColumn items. Issue #483 removed the cross-host usage strip from
 * this block — usage is now surfaced per-host on each host card.
 */
internal const val HOST_LIST_NOTICES_TAG = "host-list:notices"

/**
 * Issue #514: stable test tag for the soft "remote pocketshell CLI is
 * newer than this app — consider updating the app" banner. The connected
 * scenario asserts this banner appears (NOT a takeover sheet) while the
 * host stays fully usable.
 */
internal const val HOST_LIST_APP_UPDATE_WARNING_TAG = "host-list:app-update-warning"

/**
 * Issue #515: stable test tag for the "couldn't check for updates — Retry"
 * banner shown when the GitHub-Releases poll genuinely failed (vs found no
 * newer release). The connected scenario asserts this surfaces a visible
 * retry rather than a silent no-op.
 */
internal const val HOST_LIST_UPDATE_CHECK_FAILED_TAG = "host-list:update-check-failed"

/**
 * Issue #515: stable test tag riding the actionable "Update" button on the
 * #514 "app is behind" banner once a downloadable GitHub release has been
 * resolved. Connected/instrumented tests target this to fire the APK open
 * path.
 */
internal const val HOST_LIST_APP_UPDATE_ACTION_TAG = "host-list:app-update-warning:update"

/**
 * Issue #515: stable test tag riding the "Retry" button on the
 * update-check-failed banner.
 */
internal const val HOST_LIST_UPDATE_CHECK_RETRY_TAG = "host-list:update-check-failed:retry"

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

/**
 * Issue #483: stable test-tag prefix for the kebab "Usage" item that
 * opens the Usage detail for a specific host. The full tag is
 * `host:overflow:usage:<hostId>`.
 */
const val HOST_USAGE_MENU_ITEM_TAG_PREFIX = "host:overflow:usage:"

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
 * on the host card. Issue #155 demoted the chip OFF the primary status
 * row to reduce scanning friction (the cross-host Usage dashboard strip
 * already surfaces blocked state), and issue #418 removed the host
 * card's inline badge slots entirely in favour of a single status dot.
 * The chip now lives inside the kebab overflow menu — see
 * [HostOverflowMenuAnchor] — and keeps the same test tag so existing
 * instrumentation that targets it stays valid.
 */
internal const val HOST_USAGE_BADGE_TAG_PREFIX = "host:usage-badge:"

/**
 * Issue #515: the single shared "download the update APK" path. Fires
 * `Intent.ACTION_VIEW` against [ReleaseInfo.apkUrl] so the system browser /
 * download manager handles the sideload — we never silently install — then
 * records a download-started confirmation. On failure it falls back to the
 * release page and records a concrete reason. Used by BOTH the standalone
 * [UpdateBanner] (#40/#476) and the #514 "app is behind" banner so the two
 * offer exactly the same user-driven sideload UX.
 */
internal fun launchApkDownload(
    context: Context,
    info: ReleaseInfo,
    viewModel: HostListViewModel,
) {
    launchUpdateDownload(
        context = context,
        info = info,
        onStarted = { tag -> viewModel.onUpdateDownloadStarted(tag) },
        onFailed = { reason -> viewModel.onUpdateDownloadFailed(reason) },
    )
}

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
internal fun UpdateBanner(info: ReleaseInfo, onUpdate: () -> Unit) {
    Banner(
        text = "New version available (${info.tagName})",
        role = BannerRole.Info,
        modifier = Modifier
            // Issue #418: the outer vertical padding was dropped — the
            // shared "notices" Column now owns inter-banner spacing, so
            // the banner no longer double-pads above/below itself.
            .padding(horizontal = PocketShellSpacing.md),
        trailingContent = {
            PocketShellButton(
                text = "Update",
                onClick = onUpdate,
                variant = ButtonVariant.Text,
                compact = true,
            )
        },
    )
}

/**
 * Host-list header, routed through the shared [ScreenHeader] (#479 Slice A)
 * so the dashboard reads as the tight dev-tool block — `bodyDense` SemiBold
 * title + the live `N hosts · M active` count subtitle — instead of the old
 * 60dp / 22.sp "PocketShell" marketing bar. The trailing slot keeps the
 * existing affordances: the global port-forward indicator pill (#446) and the
 * Settings gear (the only header actions this screen has carried since #299 /
 * #290 / #388 moved tabs, QR, and import elsewhere).
 *
 * The subtitle is built here, not in [ScreenHeader] — the component does not
 * pluralise (#479 §4); `1 host`/`N hosts` and the active-session count come
 * from the caller's live state.
 */
@Composable
internal fun HostsAppBar(
    hostCount: Int,
    activeSessionCount: Int,
    onSettingsClick: () -> Unit = {},
    forwardingIndicator: com.pocketshell.app.portfwd.ForwardingIndicatorState =
        com.pocketshell.app.portfwd.ForwardingIndicatorState(),
    onForwardingIndicatorClick: () -> Unit = {},
    // Issue #1241: the glanceable usage pill. Null (or a null route) hides it.
    usageGlancePill: com.pocketshell.app.usage.UsageGlancePillState? = null,
    onOpenUsage: (() -> Unit)? = null,
) {
    ScreenHeader(
        title = "Hosts",
        subtitle = hostsHeaderSubtitle(hostCount, activeSessionCount),
        modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
        trailing = {
            // Issue #1241: the most-constraining usage percent, tapping into
            // UsageScreen. Leftmost of the trailing affordances so the number
            // is the first thing scanned; the shared trailing Row's inter-item
            // spacing keeps it clear of the forwarding pill + Settings gear.
            if (usageGlancePill != null && onOpenUsage != null) {
                com.pocketshell.app.usage.UsageGlancePill(
                    state = usageGlancePill,
                    onClick = onOpenUsage,
                )
            }
            // Issue #446: the global "ports forwarding" indicator only appears
            // while ≥1 host is actively auto-forwarding. Tapping it opens the
            // port-forward panel entry (same chooser as the QS tile +
            // notification deep-link).
            if (forwardingIndicator.visible) {
                ForwardingIndicatorPill(
                    state = forwardingIndicator,
                    onClick = onForwardingIndicatorClick,
                )
            }
            TopBarIconButton(
                contentDescription = "Settings",
                testTag = SETTINGS_BUTTON_TAG,
                onClick = onSettingsClick,
            ) {
                SettingsGearIcon()
            }
        },
    )
}

/**
 * Builds the host-list header subtitle (`N hosts · M active`). Pluralisation
 * lives here because [ScreenHeader] is count-agnostic (#479 §4): the caller
 * owns the `1 host` vs `N hosts` wording and the active-session facet.
 */
internal fun hostsHeaderSubtitle(hostCount: Int, activeSessionCount: Int): String {
    val hostsLabel = if (hostCount == 1) "1 host" else "$hostCount hosts"
    return "$hostsLabel · $activeSessionCount active"
}

// Stable tag for the global port-forward indicator pill. Connected tests
// assert it appears only while ≥1 host is forwarding and routes to the
// panel on tap.
internal const val FORWARDING_INDICATOR_TAG = "hosts:indicator:forwarding"

@Composable
private fun ForwardingIndicatorPill(
    state: com.pocketshell.app.portfwd.ForwardingIndicatorState,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = PocketShellShapes.large,
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = PocketShellShapes.large,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = state.contentDescription }
            .testTag(FORWARDING_INDICATOR_TAG)
            .padding(horizontal = PocketShellSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A small forwarding glyph (two opposed arrows) drawn inline so the
        // pill carries no extra drawable dependency.
        ForwardingGlyph()
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = state.label,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
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
 * Trailing kebab (vertical three-dot) overflow for the host card. Renders the
 * shared [com.pocketshell.uikit.components.Kebab] component (#461 design-system
 * consolidation), which draws the trigger glyph + the menu it anchors. Lives in
 * the [HostCard]'s `trailingContent` slot. Tapping the trigger flips [expanded]
 * via the caller; long-press on the card also calls back to flip the same state
 * so the menu is reachable both ways.
 *
 * Issue #155: the 40 dp tap target carries a permanent visible affordance — a
 * circular [PocketShellColors.SurfaceElev] background with a 1 dp
 * [PocketShellColors.BorderSoft] hairline border — matching the design-system §8
 * requirement that the kebab read as "always visible affordance". The shared
 * Kebab draws exactly this chrome. Read-only status/setup rows keep state
 * discoverable in the menu without adding painted chips back onto the card; the
 * usage record stays in the menu so it no longer competes with primary row
 * scanning.
 */
@Composable
internal fun HostOverflowMenuAnchor(
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    hostStatus: HostStatus = HostStatus.Unknown,
    setupState: HostSetupState = HostSetupState.Unknown,
    openingLabel: String? = null,
    usageRecord: com.pocketshell.core.usage.UsageProviderRecord?,
    usageBadgeTestTag: String,
    onEdit: () -> Unit,
    onOpenUsage: (() -> Unit)?,
    usageMenuItemTestTag: String,
    onOpenPorts: () -> Unit,
    onOpenWatchedFolders: () -> Unit,
    onShare: () -> Unit,
    onRecheckSetup: () -> Unit,
) {
    // Issue #461: render the shared Kebab component instead of a hand-rolled
    // trigger + DropdownMenu. Expansion is driven externally (the host card's
    // long-press also opens this menu), so [expanded] / [onExpandedChange] are
    // wired through. The trigger keeps its stable HOST_OVERFLOW_BUTTON_TAG; the
    // 40 dp circular SurfaceElev + 1 dp BorderSoft hairline chrome the shared
    // Kebab draws is the same affordance #155 specced here.
    val items = buildList {
        openingLabel?.let { label ->
            add(
                KebabItem(
                    label = label,
                    onClick = {},
                    enabled = false,
                    content = {
                        HostOverflowStateRow(
                            label = "Opening",
                            value = label,
                            kind = PillKind.Warn,
                        )
                    },
                ),
            )
        }
        add(
            KebabItem(
                label = "Status",
                onClick = {},
                enabled = false,
                content = {
                    val display = hostStatusDisplay(hostStatus)
                    HostOverflowStateRow(
                        label = "Status",
                        value = display.label,
                        kind = display.kind,
                    )
                },
            ),
        )
        add(
            KebabItem(
                label = "Setup",
                onClick = {},
                enabled = false,
                content = {
                    val display = setupStateDisplay(setupState)
                    HostOverflowStateRow(
                        label = "Setup",
                        value = display.label,
                        kind = display.kind,
                    )
                },
            ),
        )
        // Issue #155: per-host usage status is surfaced in the menu when the
        // scheduler has a blocked / near-limit record for the host.
        // Rendered as a non-clickable header row (disabled, no-op onClick) so it
        // reads as state rather than an action. The pill is wrapped in a Box
        // carrying the existing `host:usage-badge:<id>` test tag so
        // instrumentation that previously located the inline chip keeps working.
        if (usageRecord != null) {
            add(
                KebabItem(
                    label = "",
                    onClick = {},
                    enabled = false,
                    content = {
                        Box(modifier = Modifier.testTag(usageBadgeTestTag)) {
                            com.pocketshell.app.usage.UsageSessionBlockedBadge(
                                provider = usageRecord,
                            )
                        }
                    },
                ),
            )
        }
        // Issue #519: restore the host-edit entry point. The long-press → Edit
        // affordance was dropped in #38 when long-press became "open kebab"
        // (#113), which silently orphaned the `onEditHost` route. Edit is the
        // primary per-host config action, so it sits at the top of the action
        // list, above the read/glance items (Usage/Ports) and well clear of
        // destructive Share/re-check.
        add(
            KebabItem(
                label = "Edit",
                onClick = onEdit,
                testTag = HOST_EDIT_ITEM_TAG,
            ),
        )
        // Issue #483: discoverable, labelled per-host entry to the Usage detail.
        // Only rendered when the nav graph supplied a route.
        if (onOpenUsage != null) {
            add(
                KebabItem(
                    label = "Usage",
                    onClick = onOpenUsage,
                    testTag = usageMenuItemTestTag,
                ),
            )
        }
        add(KebabItem(label = "Ports", onClick = onOpenPorts))
        // Issue #206: per-host watched-folders configuration. Placed above Share
        // so the quick-access config row sits next to Ports (other per-host
        // config) rather than mixed with sharing / diagnostics affordances.
        add(
            KebabItem(
                label = "Watched folders",
                onClick = onOpenWatchedFolders,
                testTag = HOST_WATCHED_FOLDERS_ITEM_TAG,
            ),
        )
        add(KebabItem(label = "Share", onClick = onShare))
        // Issue #120: manual re-probe entry point. Sits below the existing items
        // so the placement is visually stable when long-press users learn the
        // menu layout.
        add(
            KebabItem(
                label = RECHECK_SETUP_LABEL,
                onClick = onRecheckSetup,
                testTag = HOST_RECHECK_SETUP_ITEM_TAG,
            ),
        )
    }
    Kebab(
        items = items,
        triggerTestTag = HOST_OVERFLOW_BUTTON_TAG,
        triggerSize = PocketShellDensity.tapTargetMin,
        expanded = expanded,
        onExpandedChange = { next -> if (next) onExpand() else onDismiss() },
    )
}

private data class HostOverflowDisplay(
    val label: String,
    val kind: PillKind,
)

@Composable
private fun HostOverflowStateRow(
    label: String,
    value: String,
    kind: PillKind,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
        Pill(label = value, kind = kind)
    }
}

private fun hostStatusDisplay(status: HostStatus): HostOverflowDisplay =
    when (status) {
        HostStatus.Unknown -> HostOverflowDisplay(label = "Checking", kind = PillKind.Error)
        HostStatus.NoActiveSessions -> HostOverflowDisplay(label = "No sessions", kind = PillKind.Error)
        is HostStatus.ActiveSessions -> HostOverflowDisplay(
            label = if (status.count == 1) "1 session" else "${status.count} sessions",
            kind = PillKind.Ok,
        )
        HostStatus.Attached -> HostOverflowDisplay(label = "Attached", kind = PillKind.Ok)
        HostStatus.NeedsSetup -> HostOverflowDisplay(label = "Needs setup", kind = PillKind.Warn)
        HostStatus.ConnectionError -> HostOverflowDisplay(label = "Connection error", kind = PillKind.Blocked)
    }

private fun setupStateDisplay(setupState: HostSetupState): HostOverflowDisplay =
    when (setupState) {
        HostSetupState.Ready -> HostOverflowDisplay(label = "Ready", kind = PillKind.Ok)
        HostSetupState.NeedsSetup -> HostOverflowDisplay(label = "Needs setup", kind = PillKind.Warn)
        HostSetupState.CliUpdateNeeded -> HostOverflowDisplay(label = "Update needed", kind = PillKind.Warn)
        HostSetupState.OptionalUnavailable -> HostOverflowDisplay(label = "Partial", kind = PillKind.Warn)
        HostSetupState.DaemonDisabled -> HostOverflowDisplay(label = "Daemon off", kind = PillKind.Warn)
        HostSetupState.Unknown -> HostOverflowDisplay(label = "Checking", kind = PillKind.Error)
    }

internal const val HOST_OVERFLOW_BUTTON_TAG: String = "host:overflow:button"
// Issue #519: stable tag for the kebab → "Edit" entry so instrumentation
// can reach the edit-host route without depending on free-form text.
const val HOST_EDIT_ITEM_TAG: String = "host:overflow:edit"
internal const val HOST_RECHECK_SETUP_ITEM_TAG: String = "host:overflow:recheck-setup"
// Issue #206: stable tag for the kebab → "Watched folders" entry so
// the connected E2E test can navigate from the host list to the
// config screen without depending on free-form text.
const val HOST_WATCHED_FOLDERS_ITEM_TAG: String = "host:overflow:watched-folders"
internal const val RECHECK_SETUP_LABEL: String = "Re-check setup"

@Composable
internal fun ShareMessageBanner(message: String, onDismiss: () -> Unit) {
    Banner(
        text = message,
        role = BannerRole.Info,
        modifier = Modifier.padding(horizontal = PocketShellSpacing.md),
        trailingContent = {
            PocketShellButton(
                text = "Dismiss",
                onClick = onDismiss,
                variant = ButtonVariant.Text,
                compact = true,
            )
        },
    )
}

/**
 * Issue #515: quiet inline note shown when the GitHub-Releases poll failed
 * (vs simply found no newer release). The failure used to vanish into a
 * silent `null`; this surfaces a concrete cause plus a Retry that re-runs
 * the check, and a Dismiss to clear it. Styled like [ShareMessageBanner]
 * (not the alarming accent banner) so a transient network blip reads as a
 * gentle "tap to retry", not an error takeover.
 */
@Composable
internal fun UpdateCheckFailedBanner(
    reason: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Banner(
        text = "Couldn't check for updates ($reason)",
        role = BannerRole.Warning,
        modifier = Modifier.padding(horizontal = PocketShellSpacing.md),
        trailingContent = {
            PocketShellButton(
                text = "Retry",
                onClick = onRetry,
                variant = ButtonVariant.Text,
                compact = true,
                modifier = Modifier.testTag(HOST_LIST_UPDATE_CHECK_RETRY_TAG),
            )
            PocketShellButton(
                text = "Dismiss",
                onClick = onDismiss,
                variant = ButtonVariant.Text,
                compact = true,
            )
        },
    )
}

/**
 * Issue #514 + #515: the soft "remote pocketshell CLI is newer than this
 * app" note. The host stays fully usable, so this is a quiet dismissible
 * banner — never a sheet. #515 adds an OPTIONAL "Update" action when the
 * host probe has been backed by a resolved downloadable GitHub release
 * ([onUpdate] non-null): tapping it fires the same ACTION_VIEW → APK path
 * as the standalone [UpdateBanner]. When APK resolution fails ([onUpdate]
 * null with [HostListViewModel.AppUpdateWarning.releaseResolutionFailure]
 * set), the banner shows a visible Retry action instead of silently
 * degrading to passive text.
 */
@Composable
internal fun AppUpdateWarningBanner(
    warning: HostListViewModel.AppUpdateWarning,
    onUpdate: (() -> Unit)?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Banner(
        text = when {
            warning.releaseResolutionFailure != null ->
                "${warning.message} Couldn't prepare the update download (${warning.releaseResolutionFailure})."
            warning.isResolvingRelease ->
                "${warning.message} Preparing the update download..."
            else -> warning.message
        },
        role = BannerRole.Warning,
        modifier = Modifier.padding(horizontal = PocketShellSpacing.md),
        trailingContent = {
            if (onUpdate != null) {
                PocketShellButton(
                    text = "Update",
                    onClick = onUpdate,
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(HOST_LIST_APP_UPDATE_ACTION_TAG),
                )
            } else if (warning.releaseResolutionFailure != null) {
                PocketShellButton(
                    text = "Retry",
                    onClick = onRetry,
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(HOST_LIST_APP_UPDATE_ACTION_TAG),
                )
            }
            PocketShellButton(
                text = "Dismiss",
                onClick = onDismiss,
                variant = ButtonVariant.Text,
                compact = true,
            )
        },
    )
}

@Composable
private fun HostShareDialog(
    share: HostListViewModel.HostSharePayload,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
) {
    // Issue #1230: a large payload (long name/hostname/username, long key
    // name) splits into several QR envelope parts. Page through them so the
    // in-app scanner can reassemble; a single-part payload shows just its QR.
    val total = share.payloads.size
    var index by remember(share) { mutableStateOf(0) }
    val safeIndex = index.coerceIn(0, (total - 1).coerceAtLeast(0))
    val current = share.payloads[safeIndex]
    val qr = remember(current) { HostQrCode.encode(current, sizePx = 640) }
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
                if (total > 1) {
                    Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        PocketShellButton(
                            text = "Prev",
                            onClick = { if (safeIndex > 0) index = safeIndex - 1 },
                            variant = ButtonVariant.Text,
                            compact = true,
                        )
                        Text(
                            text = "QR ${safeIndex + 1} of $total",
                            color = PocketShellColors.Text,
                            style = PocketShellTypography.labelSmall,
                        )
                        PocketShellButton(
                            text = "Next",
                            onClick = { if (safeIndex < total - 1) index = safeIndex + 1 },
                            variant = ButtonVariant.Text,
                            compact = true,
                        )
                    }
                    Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
                    Text(
                        text = "This host is large, so it splits across $total codes. Scan all of them with the in-app QR scanner to combine.",
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellTypography.labelSmall,
                    )
                }
                Spacer(modifier = Modifier.height(PocketShellSpacing.md))
                Text(
                    text = "Private keys and passphrases are never included. Import requires a local key with the same name.",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellTypography.labelSmall,
                )
            }
        },
        confirmButton = {
            PocketShellButton(
                text = "Share text",
                onClick = onShare,
                variant = ButtonVariant.Primary,
            )
        },
        dismissButton = {
            PocketShellButton(
                text = "Close",
                onClick = onDismiss,
                variant = ButtonVariant.Text,
            )
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
                    style = PocketShellType.bodyDense,
                )
                Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
                Text(
                    text = "Importing as “${conflict.incoming.name}” " +
                        "(${conflict.incoming.username}@$endpoint).",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellTypography.labelSmall,
                )
                Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
                Text(
                    text = "Update replaces the saved host in place. Add as new keeps both. " +
                        "Already added leaves the saved host unchanged.",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellTypography.labelSmall,
                )
            }
        },
        confirmButton = {
            PocketShellButton(
                text = "Update",
                onClick = onOverwrite,
                variant = ButtonVariant.Primary,
                modifier = Modifier.testTag(IMPORT_CONFLICT_OVERWRITE_TAG),
            )
        },
        dismissButton = {
            Row {
                PocketShellButton(
                    text = "Add as new",
                    onClick = onAddAsNew,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.testTag(IMPORT_CONFLICT_ADD_AS_NEW_TAG),
                )
                PocketShellButton(
                    text = "Already added",
                    onClick = onSkip,
                    variant = ButtonVariant.Text,
                    modifier = Modifier.testTag(IMPORT_CONFLICT_SKIP_TAG),
                )
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
                    style = PocketShellTypography.labelSmall,
                )
                Spacer(modifier = Modifier.height(PocketShellSpacing.md))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = onPassphraseChange,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Passphrase") },
                    modifier = Modifier.fillMaxWidth(),
                )
                unlockError?.let {
                    Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
                    Text(text = it, color = PocketShellColors.Red, style = PocketShellTypography.labelSmall)
                }
            }
        },
        confirmButton = {
            PocketShellButton(
                text = "Connect",
                onClick = onConnect,
                enabled = passphrase.isNotEmpty(),
                variant = ButtonVariant.Primary,
            )
        },
        dismissButton = {
            PocketShellButton(
                text = "Cancel",
                onClick = onDismiss,
                variant = ButtonVariant.Text,
            )
        },
        containerColor = PocketShellColors.Surface,
    )
}

/**
 * Empty-state when no hosts are saved. Single line + a hint to use the FAB.
 */
@Composable
private fun EmptyHostList(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellSpacing.lg)
            .background(
                color = PocketShellColors.Surface,
                shape = PocketShellShapes.medium,
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = PocketShellShapes.medium,
            )
            .testTag(HOST_LIST_EMPTY_STATE_TAG),
    ) {
        ListRow(
            title = "No hosts yet",
            subtitle = "Use + to add an SSH host",
        )
    }
}
