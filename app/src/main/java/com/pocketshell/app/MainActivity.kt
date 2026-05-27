package com.pocketshell.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.fragment.app.FragmentActivity
import com.pocketshell.app.costs.CostsScreen
import com.pocketshell.app.crash.CrashReportsScreen
import com.pocketshell.app.hosts.AddEditHostScreen
import com.pocketshell.app.hosts.HostListScreen
import com.pocketshell.app.hosts.HostListViewModel
import com.pocketshell.app.hosts.QrScannerScreen
import com.pocketshell.app.hosts.SshKeysScreen
import com.pocketshell.app.jobs.RecurringJobsScreen
import com.pocketshell.app.jobs.RecurringJobsViewModel
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.portfwd.PortForwardPanelScreen
import com.pocketshell.app.projects.FolderListScreen
import com.pocketshell.app.projects.WatchedFoldersScreen
import com.pocketshell.app.projects.WatchedFoldersViewModel
import com.pocketshell.app.session.SessionScreen
import com.pocketshell.app.session.SessionViewModel
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.settings.SettingsScreen
import com.pocketshell.app.settings.ThemePreference
import com.pocketshell.app.systemsurfaces.ForwardingChooserScreen
import com.pocketshell.app.systemsurfaces.ForwardingTileService
import com.pocketshell.app.tmux.TmuxSessionScreen
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.usage.UsageScheduler
import com.pocketshell.app.usage.UsageScreen
import com.pocketshell.app.usage.UsageViewModel
import com.pocketshell.app.usage.worstBadgeRecord
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Phase 1 entry point.
 *
 * Hosts a hand-rolled state-based navigator (see
 * [com.pocketshell.app.nav.AppDestination] for rationale on not pulling
 * in `androidx.navigation:navigation-compose` — the brief for #18
 * forbids new catalog entries and we have a small finite destination
 * set). The current destination lives in a `mutableStateOf`, with a
 * small back-stack as a list for the back gesture.
 *
 * Destinations:
 *
 * - [AppDestination.HostList] (landing) — list of saved SSH hosts.
 * - [AppDestination.AddHost] / [AppDestination.EditHost] — host form.
 * - [AppDestination.SshKeys] — SSH key list / add / delete.
 * - [AppDestination.Session] — live SSH session for a selected host.
 *
 * The Phase 0 `ProofOfLifeScreen` is kept on disk (the
 * `ProofPipelineTest` still imports its helper functions) but is no
 * longer the launcher entry. The host picker landed here in #18 owns
 * that role now.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    // SessionViewModel stays activity-scoped: we only have one live
    // session at a time today; multi-pane lifecycle arrives with #22.
    private val sessionViewModel: SessionViewModel by viewModels()
    private var requestedDestination by mutableStateOf<AppDestination>(AppDestination.HostList)

    /**
     * Issue #129: payload pulled out of a `pocketshell://import?...`
     * deep link. Consumed exactly once by the [AppNavigator] root
     * composition so a re-launch from `onNewIntent` with a different
     * payload is routed without the previous payload being re-imported.
     */
    private var pendingImportPayload by mutableStateOf<String?>(null)

    // Issue #112: theme preference observed at the composable root so a
    // tap in Settings re-themes the entire activity with no restart. The
    // repository is `@Singleton` so the Settings view model and this
    // activity share the same instance.
    @Inject
    lateinit var settingsRepository: SettingsRepository

    // Issue #116 (usage-panel Fix B): the session screens need the
    // per-host worst-case usage record so the in-session blocked /
    // near-limit chip can render in the status area. Injecting the
    // scheduler at the activity lets the navigator pass a snapshot
    // map down to each session destination without coupling
    // SessionViewModel / TmuxSessionViewModel to the scheduler.
    @Inject
    lateinit var usageScheduler: UsageScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedDestination = initialDestinationFromIntent(intent)
        pendingImportPayload = importPayloadFromIntent(intent)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(DarkSystemBarColor))
        window.decorView.setBackgroundColor(DarkSystemBarColor)
        @Suppress("DEPRECATION")
        window.statusBarColor = DarkSystemBarColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = DarkSystemBarColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(DarkSystemBarColor),
            navigationBarStyle = SystemBarStyle.dark(DarkSystemBarColor),
        )
        @Suppress("DEPRECATION")
        window.statusBarColor = DarkSystemBarColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = DarkSystemBarColor
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            val settings by settingsRepository.settings.collectAsState()
            PocketShellTheme(mode = settings.theme.toThemeMode()) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavigator(
                        sessionViewModel = sessionViewModel,
                        usageScheduler = usageScheduler,
                        usageWarnPercent = settings.usageWarnThresholdPercent.toDouble(),
                        requestedDestination = requestedDestination,
                        pendingImportPayload = pendingImportPayload,
                        onImportPayloadConsumed = { pendingImportPayload = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDestination = initialDestinationFromIntent(intent)
        importPayloadFromIntent(intent)?.let { pendingImportPayload = it }
    }
}

private val DarkSystemBarColor: Int = android.graphics.Color.rgb(13, 17, 23)

private fun ThemePreference.toThemeMode(): PocketShellThemeMode = when (this) {
    ThemePreference.System -> PocketShellThemeMode.System
    ThemePreference.Light -> PocketShellThemeMode.Light
    ThemePreference.Dark -> PocketShellThemeMode.Dark
}

/**
 * Sealed-class destination state machine. The back-stack is a `List<AppDestination>`
 * we push / pop; rendering branches on the head.
 *
 * Saveable: we don't persist the back stack across process death today.
 * Phase 1 acceptance only requires that hosts + keys persist across app
 * restarts; the back stack is volatile by design (cold-launch returns
 * the user to `HostList`).
 */
@Composable
private fun AppNavigator(
    sessionViewModel: SessionViewModel,
    usageScheduler: UsageScheduler,
    usageWarnPercent: Double,
    requestedDestination: AppDestination,
    pendingImportPayload: String? = null,
    onImportPayloadConsumed: () -> Unit = {},
) {
    // Issue #116: per-host worst-case usage record map, derived from
    // the scheduler's snapshot flow. Session destinations look up the
    // active host id in this map to decide whether to render the
    // in-session blocked / near-limit chip.
    //
    // Issue #214: the worst-case derivation now consults the user-
    // configurable warn threshold so the in-session chip respects the
    // same "approaching limit" point as the cross-host strip + Settings
    // surface.
    val usageSnapshots by usageScheduler.snapshots.collectAsState()
    val usageBadgesByHost = remember(usageSnapshots, usageWarnPercent) {
        usageSnapshots.mapNotNull { (id, snap) ->
            snap.worstBadgeRecord(warnPercent = usageWarnPercent)?.let { id to it }
        }.toMap()
    }
    // Issue #129: the activity scrapes the import payload out of a
    // `pocketshell://import?...` deep link before composition starts
    // and stores it here. We hand it to the host-list view model the
    // moment that VM materialises (host list is the landing
    // destination, so this is always one composition away), then call
    // `onImportPayloadConsumed` so the activity clears the state and
    // a future re-launch with a fresh payload is processed correctly.
    val hostListViewModelForImport: HostListViewModel = hiltViewModel()
    LaunchedEffect(pendingImportPayload) {
        val payload = pendingImportPayload ?: return@LaunchedEffect
        hostListViewModelForImport.importSharedHostPayload(payload)
        onImportPayloadConsumed()
    }

    // Volatile in-memory state. Cold-launch (process death) always lands
    // on `HostList`; full saved-state restoration arrives when
    // navigation-compose lands and brings its own saver machinery.
    var current: AppDestination by remember {
        mutableStateOf(requestedDestination)
    }

    val backStack = remember { mutableListOf<AppDestination>() }

    LaunchedEffect(requestedDestination) {
        if (requestedDestination == AppDestination.PortForwardChooser && current != requestedDestination) {
            backStack += current
            current = requestedDestination
        }
    }

    fun navigate(dest: AppDestination) {
        backStack += current
        current = dest
    }

    fun replace(dest: AppDestination) {
        current = dest
    }

    fun back() {
        current = backStack.removeLastOrNull() ?: AppDestination.HostList
    }

    when (val dest = current) {
        AppDestination.HostList -> HostListScreen(
            onAddHost = { navigate(AppDestination.AddHost) },
            onEditHost = { id -> navigate(AppDestination.EditHost(id)) },
            onManageKeys = { navigate(AppDestination.SshKeys) },
            onOpenCrashReports = { navigate(AppDestination.CrashReports) },
            onOpenSettings = { navigate(AppDestination.Settings) },
            onOpenScan = { navigate(AppDestination.Scan) },
            // Issue #116 (usage-panel Fix B): wire the cross-host usage
            // strip's tap target to the same Usage destination the
            // bootstrap-success CTA opens. The bootstrap path keeps the
            // existing wiring (the strip uses the same callback so the
            // two routes always agree).
            onOpenUsage = { navigate(AppDestination.Usage) },
            // Issue #171: host-tap now routes to the new folder list
            // (the default destination after a host tap). The folder
            // list owns its own probe + grouping; it surfaces the
            // existing flat session list via the "Show all sessions on
            // this host" link. The previous `onOpenSession` /
            // `onOpenTmuxHostSession` callbacks (picker-sheet routes)
            // were removed from `HostListScreen` per D22 hard-cut.
            onOpenFolderList = { host, keyPath, passphrase ->
                navigate(
                    AppDestination.FolderList(
                        hostId = host.id,
                        hostName = host.name,
                        hostname = host.hostname,
                        port = host.port,
                        username = host.username,
                        keyPath = keyPath,
                        passphrase = passphrase,
                    ),
                )
            },
            onOpenPortForwardPanel = { host, keyPath, passphrase ->
                navigate(AppDestination.PortForwardPanel(hostId = host.id, keyPath = keyPath, passphrase = passphrase))
            },
            // Issue #206: kebab → "Watched folders". Pass the SSH
            // connection parameters so the discover-from-remote
            // probe can authenticate. The destination carries them
            // as optional fields because the Settings host-picker
            // path arrives without them.
            onOpenWatchedFolders = { host, keyPath, passphrase ->
                navigate(
                    AppDestination.WatchedFolders(
                        hostId = host.id,
                        hostName = host.name,
                        hostname = host.hostname,
                        port = host.port,
                        username = host.username,
                        keyPath = keyPath,
                        passphrase = passphrase,
                    ),
                )
            },
            onOpenTmuxSession = { entry, sessionName, startDirectory ->
                navigate(
                    AppDestination.TmuxSession(
                        hostId = entry.hostId,
                        hostName = entry.hostName,
                        hostname = entry.hostname,
                        port = entry.port,
                        username = entry.username,
                        keyPath = entry.keyPath,
                        passphrase = null,
                        sessionName = sessionName,
                        startDirectory = startDirectory,
                    ),
                )
            },
        )

        AppDestination.AddHost -> AddEditHostScreen(
            hostId = null,
            onDone = ::back,
            onManageKeys = { navigate(AppDestination.SshKeys) },
        )

        is AppDestination.EditHost -> AddEditHostScreen(
            hostId = dest.hostId,
            onDone = ::back,
            onManageKeys = { navigate(AppDestination.SshKeys) },
        )

        AppDestination.SshKeys -> SshKeysScreen(onBack = ::back)

        // Issue #129: live camera QR scanner. Dispatches the decoded
        // payload through the existing host-list import path so both
        // the legacy single-QR (`pocketshell.ssh-import.v1` JSON) and
        // the new multi-QR envelope (`pocketshell.qr.v1?...`) share one
        // code path. The view model is the activity-scoped
        // [HostListViewModel] so the resulting "Imported …" banner /
        // host insertion are observable in the host list when the
        // navigator pops back.
        AppDestination.Scan -> {
            val hostListViewModel: HostListViewModel = hiltViewModel()
            QrScannerScreen(
                onDecoded = { payload ->
                    hostListViewModel.importSharedHostPayload(payload)
                    back()
                },
                onPickFile = { uri ->
                    hostListViewModel.importSharedHostUri(uri)
                    back()
                },
                onClose = ::back,
            )
        }

        AppDestination.CrashReports -> CrashReportsScreen(onBack = ::back)

        AppDestination.Settings -> SettingsScreen(
            onBack = ::back,
            onOpenCrashReports = { navigate(AppDestination.CrashReports) },
            onOpenUsage = { navigate(AppDestination.Usage) },
            onOpenAiCosts = { navigate(AppDestination.AiCosts) },
            // Issue #206: Settings → Watched folders host picker routes
            // here without SSH credentials. The destination's SSH
            // fields stay null so the discover-from-remote button is
            // hidden — the user can still add / edit / delete / reorder
            // folders manually.
            onOpenWatchedFoldersForHost = { hostId, hostName ->
                navigate(
                    AppDestination.WatchedFolders(
                        hostId = hostId,
                        hostName = hostName,
                    ),
                )
            },
        )

        // Issue #181: AI Costs screen — client-side OpenAI spend
        // tracker. Sister of the Usage screen but sourced from the
        // local Room log rather than server-side `quse` output.
        AppDestination.AiCosts -> CostsScreen(onBack = ::back)

        // Issue #114 Fix A: Usage / quota panel. The view model loads
        // every bootstrapped host on construction and pull-to-refresh
        // (the breadcrumb "more" action on UsageScreen) re-runs
        // `fetchUsage` for each host. A fresh ViewModel is materialised
        // every visit so missing-tool / failed-fetch state can't leak
        // across navigations.
        AppDestination.Usage -> {
            val usageViewModel = hiltViewModel<UsageViewModel>()
            val usageState by usageViewModel.state.collectAsState()
            UsageScreen(
                state = usageState,
                onBack = ::back,
                onRefresh = usageViewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AppDestination.PortForwardChooser -> ForwardingChooserScreen(
            onBack = ::back,
            onOpenPortForwardPanel = { host, keyPath, passphrase ->
                navigate(
                    AppDestination.PortForwardPanel(
                        hostId = host.id,
                        keyPath = keyPath,
                        passphrase = passphrase,
                    ),
                )
            },
        )

        is AppDestination.Session -> SessionScreen(
            viewModel = sessionViewModel,
            host = dest.hostname,
            port = dest.port,
            user = dest.username,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            // Issue #17: the session screen surfaces the snippet picker
            // off the chip row + the composer's Snippets button. Both
            // need the persisted host id to scope the library.
            hostId = dest.hostId,
            onBack = ::back,
            onOpenJobs = {
                navigate(
                    AppDestination.RecurringJobs(
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        sessionName = DefaultTmuxSessionName,
                    ),
                )
            },
            onOpenUsage = { navigate(AppDestination.Usage) },
            // Issue #116: in-session blocked / near-limit chip for the
            // active host. Look up by [HostEntity.id]; absence means the
            // scheduler has no recent record warranting a chip.
            usageBadgeProvider = usageBadgesByHost[dest.hostId],
        )

        is AppDestination.PortForwardPanel -> PortForwardPanelScreen(
            hostId = dest.hostId,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            onBack = ::back,
        )

        // Issue #206: per-host watched-folders config screen. SSH
        // connection parameters are optional on the destination — only
        // the host-list kebab path supplies them (so the discover
        // probe can authenticate); the Settings host-picker path
        // arrives with them null and the screen hides the discover
        // button accordingly.
        is AppDestination.WatchedFolders -> {
            val creds = if (
                dest.hostname != null &&
                dest.port != null &&
                dest.username != null &&
                dest.keyPath != null
            ) {
                WatchedFoldersViewModel.SshCredentials(
                    hostname = dest.hostname,
                    port = dest.port,
                    username = dest.username,
                    keyPath = dest.keyPath,
                    passphrase = dest.passphrase,
                )
            } else {
                null
            }
            WatchedFoldersScreen(
                hostId = dest.hostId,
                hostName = dest.hostName,
                sshCredentials = creds,
                onBack = ::back,
            )
        }

        // Issue #171: per-host folder list — the default destination
        // after a host tap. Replaces the inline picker sheet for the
        // post-tap surface; routes onward to `TmuxSession` (via tap)
        // and the existing `HostTmuxSessionPickerSheet` (via the
        // "Show all sessions on this host" fallback link).
        is AppDestination.FolderList -> FolderListScreen(
            hostId = dest.hostId,
            hostName = dest.hostName,
            hostname = dest.hostname,
            port = dest.port,
            username = dest.username,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            onBack = ::back,
            onOpenSession = { sessionName, startDirectory ->
                navigate(
                    AppDestination.TmuxSession(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        sessionName = sessionName,
                        startDirectory = startDirectory,
                    ),
                )
            },
            onSessionCreated = { sessionName, cwd ->
                // Issue #171 round 2: the SessionTypePickerSheet has
                // already created the tmux session on the remote (and
                // optionally `send-keys`'d the agent CLI), so the only
                // remaining step is to attach. We route to TmuxSession
                // with `-A` semantics — if the create path collided on
                // an existing session name, the attach falls through.
                navigate(
                    AppDestination.TmuxSession(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        sessionName = sessionName,
                        startDirectory = cwd,
                    ),
                )
            },
        )

        is AppDestination.RecurringJobs -> {
            val jobsViewModel = hiltViewModel<RecurringJobsViewModel>()
            androidx.compose.runtime.LaunchedEffect(dest) {
                jobsViewModel.load(
                    hostName = dest.hostName,
                    hostname = dest.hostname,
                    port = dest.port,
                    username = dest.username,
                    keyPath = dest.keyPath,
                    passphrase = dest.passphrase,
                    sessionName = dest.sessionName,
                )
            }
            val state by jobsViewModel.state.collectAsState()
            RecurringJobsScreen(
                state = state,
                onBack = ::back,
                onRefresh = jobsViewModel::refresh,
                onAdd = jobsViewModel::add,
                onEdit = jobsViewModel::edit,
                onRemove = jobsViewModel::remove,
            )
        }

        // Issue #45: tmux control-mode session. The view model is
        // obtained via `hiltViewModel()` — distinct from the
        // activity-scoped `sessionViewModel` above so each navigation to
        // a tmux destination spins up its own client (and tears it down
        // when the user navigates away). Once the host bootstrap from
        // #49 detects tmux on a host, the picker can route here in place
        // of [AppDestination.Session]; today the picker still routes to
        // plain SSH and this branch is reached only by future deep-link
        // / explicit-route wiring.
        is AppDestination.TmuxSession -> TmuxSessionScreen(
            viewModel = hiltViewModel<TmuxSessionViewModel>(),
            hostId = dest.hostId,
            hostName = dest.hostName,
            host = dest.hostname,
            port = dest.port,
            user = dest.username,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            sessionName = dest.sessionName,
            startDirectory = dest.startDirectory,
            onBack = ::back,
            onOpenTmuxSession = { sessionName, startDirectory ->
                navigate(
                    dest.copy(sessionName = sessionName, startDirectory = startDirectory),
                )
            },
            onReplaceTmuxSession = { sessionName ->
                replace(dest.copy(sessionName = sessionName, startDirectory = null))
            },
            onOpenJobs = {
                navigate(
                    AppDestination.RecurringJobs(
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        sessionName = dest.sessionName,
                    ),
                )
            },
            onOpenUsage = { navigate(AppDestination.Usage) },
            // Issue #116: same per-host chip as the plain-SSH route.
            usageBadgeProvider = usageBadgesByHost[dest.hostId],
        )
    }
}

internal fun initialDestinationFromIntent(intent: Intent?): AppDestination =
    if (intent?.getBooleanExtra(ForwardingTileService.EXTRA_OPEN_PORT_FORWARDING, false) == true) {
        AppDestination.PortForwardChooser
    } else {
        AppDestination.HostList
    }

/**
 * Issue #129: pull a `pocketshell://import?payload=...` (or
 * `pocketshell://import?uri=...`) intent into a UTF-8 payload string
 * the host-list import path can consume.
 *
 * Returns `null` if the intent is unrelated to import or is missing
 * both query parameters. The MainActivity wires the result through
 * `HostListViewModel.importSharedHostPayload` on `onCreate` /
 * `onNewIntent`.
 *
 * Reusing the existing import path means both deep-links and QR
 * scans share the same envelope-aware logic — multi-part envelopes
 * delivered via deep link still trigger the same "scan from the QR
 * scanner" hint that the camera path uses, which is the right
 * behaviour because a deep link can't transport more than one part
 * at a time anyway.
 */
internal fun importPayloadFromIntent(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    val data = intent.data ?: return null
    if (data.scheme != "pocketshell") return null
    if (data.host != "import") return null
    val payload = data.getQueryParameter("payload")
    if (!payload.isNullOrBlank()) return payload
    return null
}

private const val DefaultTmuxSessionName = "pocketshell"
