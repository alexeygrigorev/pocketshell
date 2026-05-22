package com.pocketshell.app.hosts

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.app.bootstrap.HostBootstrapSheet
import com.pocketshell.app.release.ReleaseInfo
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.SessionsDashboardViewModel
import com.pocketshell.app.sessions.SessionsSection
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.components.HostCard
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
    onOpenSession: (HostEntity, keyPath: String) -> Unit,
    onOpenPortForwardPanel: (HostEntity, keyPath: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: HostListViewModel = hiltViewModel(),
    sessionsViewModel: SessionsDashboardViewModel = hiltViewModel(),
    onOpenTmuxSession: (ActiveTmuxClients.Entry, sessionName: String) -> Unit =
        { _, _ -> },
) {
    val hosts by viewModel.hosts.collectAsState()
    val sessions by sessionsViewModel.sessions.collectAsState()
    val updateInfo by viewModel.updateAvailable.collectAsState()
    val bootstrapState by viewModel.bootstrapState.collectAsState()
    val bootstrapHostName by viewModel.bootstrapHostName.collectAsState()
    val pendingNavigation by viewModel.pendingNavigation.collectAsState()
    val context = LocalContext.current

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
    val currentHosts by rememberUpdatedState(hosts)
    val currentOpenSession by rememberUpdatedState(onOpenSession)
    val currentOpenPortForwardPanel by rememberUpdatedState(onOpenPortForwardPanel)
    LaunchedEffect(Unit) {
        tapRequests.collect { hostId ->
            val host = currentHosts.find { it.id == hostId } ?: return@collect
            val key = viewModel.keyFor(host.keyId) ?: return@collect
            viewModel.bootstrapHost(host, key.privateKeyPath)
        }
    }
    LaunchedEffect(Unit) {
        portPanelRequests.collect { hostId ->
            val host = currentHosts.find { it.id == hostId } ?: return@collect
            val key = viewModel.keyFor(host.keyId) ?: return@collect
            currentOpenPortForwardPanel(host, key.privateKeyPath)
        }
    }

    // Fire `onOpenSession` once the ViewModel marks the pending
    // navigation ready. The ViewModel handles the cache-hit fast path
    // (immediate ready) as well as the sheet-driven slow path
    // (ready after Skip / Continue / Close).
    LaunchedEffect(pendingNavigation) {
        val pending = pendingNavigation
        if (pending != null && pending.ready) {
            currentOpenSession(pending.host, pending.keyPath)
            viewModel.consumePendingNavigation()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HostsAppBar(onKeysClick = onManageKeys)

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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HostCard(
                                name = host.name,
                                subtitle = "${host.username}@${host.hostname}:${host.port}",
                                // Phase 1 does not track live connection state
                                // here — the host list is a static snapshot.
                                // Live "connected" dots return when session-state
                                // plumbing lands (#22).
                                status = HostStatus.Disconnected,
                                onClick = { tapRequests.tryEmit(host.id) },
                                // Issue #38 item 1: only `HostCard`'s own
                                // `.clickable` handles taps. Wrapping it in an
                                // outer `combinedClickable` here used to layer
                                // a long-press hook on top, but the inner
                                // clickable always consumed the gesture first
                                // so the long-press never fired in practice.
                                // Until `HostCard` exposes a long-press
                                // callback we route edit via the nav graph
                                // alone (see `MainActivity`).
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            HostPortButton(onClick = { portPanelRequests.tryEmit(host.id) })
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
            )
        }
    }
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
            text = "v$versionName",
            color = PocketShellColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
        )
    }
}

/**
 * Top app bar matching `.appbar` in `docs/mockups/styles.css`: 60dp tall,
 * bold 22sp title, trailing 40dp icon buttons. The trailing affordance
 * for #18 is the SSH-keys manager — the global Settings sheet the mockup
 * gestures at is not in scope for this issue.
 */
@Composable
private fun HostsAppBar(onKeysClick: () -> Unit) {
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
            letterSpacing = (-0.4).sp,
            modifier = Modifier.weight(1f),
        )

        AppBarIconButton(label = "Keys", onClick = onKeysClick)
    }
}

/**
 * 40dp tap target with a label centred. The mockup uses Unicode glyphs
 * for the icon ramp (`⌕`, `⚙`); here we keep "Keys" as a 12sp text label
 * since we have no proper icon set yet. Swapping to vector icons is a
 * pure ui-kit move once those land.
 */
@Composable
private fun AppBarIconButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick),
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
private fun HostPortButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Ports",
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
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
