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
import com.pocketshell.app.projects.RepoBrowserScreen
import com.pocketshell.app.projects.WatchedFoldersScreen
import com.pocketshell.app.projects.WatchedFoldersViewModel
import com.pocketshell.app.session.LastSessionStore
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

    /**
     * Issue #177: persists the last in-session view so a return-to-
     * foreground (after an app-switch, or a process death while
     * backgrounded) restores the previous tmux session optimistically
     * instead of dumping the user on the host list.
     */
    @Inject
    lateinit var lastSessionStore: LastSessionStore

    /**
     * Issue #177: the navigator's current top destination, reported up by
     * [AppNavigator] so `onStop` can persist it. The session screen also
     * reports its current composer draft here so the restored view comes
     * back with the user's half-typed message intact.
     */
    private var currentTopDestination: AppDestination = AppDestination.HostList
    private var currentComposerDraft: String = ""

    /**
     * Issue #177: the composer draft restored alongside a recent
     * persisted session, handed to the session screen so the user's
     * half-typed message comes back. Consumed once by the navigator;
     * cleared after the restored destination has been seeded.
     */
    private var restoredComposerDraft by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Issue #177: prefer the explicit intent route (deep links, the
        // QS-tile forwarding chooser). Otherwise the launch lands on the
        // host list — UNLESS this `onCreate` is the system re-creating the
        // activity after it killed our process while backgrounded. In that
        // one case we restore the last in-session view so the user picks up
        // exactly where they left off (#177 fast resume), and the async SSH
        // reconnect fires from `TmuxSessionScreen`'s existing
        // connect-on-compose effect (the optimistic UI is usable
        // immediately while it handshakes; input affordances stay gated
        // until live per #249).
        //
        // Why gate on `savedInstanceState != null`:
        //
        // The DOMINANT app-switch case — Home then return while the process
        // is still alive — never re-enters `onCreate`. The activity instance
        // and the navigator's in-memory back stack survive the
        // background/foreground cycle, and the existing #235 `ON_START`
        // auto-reattach already returns the user straight to their live
        // session. So that case needs nothing from this restore route.
        //
        // `onCreate` only re-runs when the activity is genuinely (re)created.
        // Two flavours:
        //
        //  - A deliberate cold launch / explicit close + relaunch: the
        //    system passes `savedInstanceState == null`. The user chose to
        //    start fresh, so we MUST land them on the host list (this is the
        //    documented "close + relaunch lands on host list" contract that
        //    `ColdInstallE2eTest`, `RealAgentReleaseGateTest`, and
        //    `EmulatorWorkflowE2eTest` assert against).
        //
        //  - Process death while backgrounded, then the user returns: the
        //    system re-creates the activity with a NON-null
        //    `savedInstanceState` (the saved-state bundle). This is the only
        //    flavour where the restore route should fire — it is exactly the
        //    "I came back and my process had been reaped" experience #177
        //    targets.
        //
        // The recency cap inside [LastSessionStore.read] is the second guard:
        // even on a genuine process-death resume, a snapshot older than 24h
        // is not restored.
        val intentDestination = initialDestinationFromIntent(intent)
        val resumingFromProcessDeath = savedInstanceState != null
        // Only read the persisted snapshot on the process-death resume path;
        // a fresh launch must not even touch the store so the cold-launch
        // route is always the host list (see [resolveInitialDestination]).
        val restored =
            if (intentDestination == AppDestination.HostList && resumingFromProcessDeath) {
                lastSessionStore.read()
            } else {
                null
            }
        requestedDestination = resolveInitialDestination(
            intentDestination = intentDestination,
            resumingFromProcessDeath = resumingFromProcessDeath,
            restoredDestination = restored?.let { with(lastSessionStore) { it.toDestination() } },
        )
        restoredComposerDraft = if (requestedDestination is AppDestination.TmuxSession) {
            restored?.composerDraft.orEmpty()
        } else {
            ""
        }
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
                        // Issue #177: the navigator reports its current top
                        // destination + composer draft so `onStop` can
                        // persist the in-session view for fast resume; the
                        // restored draft flows the other way so the session
                        // comes back with the user's half-typed message.
                        onCurrentDestinationChanged = { dest -> currentTopDestination = dest },
                        onComposerDraftChanged = { draft -> currentComposerDraft = draft },
                        initialComposerDraft = restoredComposerDraft,
                        onInitialComposerDraftConsumed = { restoredComposerDraft = "" },
                    )
                }
            }
        }
    }

    /**
     * Issue #177: persist the last in-session view on the way out (D21 —
     * persistence happens at `onStop` time, nothing runs while
     * backgrounded). When the user is sitting on a tmux session we record
     * the destination + draft so the next foreground restores it; when
     * they are anywhere else we clear the snapshot so a stale session is
     * never silently restored after the user navigated away on purpose.
     */
    override fun onStop() {
        val dest = currentTopDestination
        if (dest is AppDestination.TmuxSession) {
            lastSessionStore.save(
                LastSessionStore.LastSession(
                    hostId = dest.hostId,
                    hostName = dest.hostName,
                    hostname = dest.hostname,
                    port = dest.port,
                    username = dest.username,
                    keyPath = dest.keyPath,
                    sessionName = dest.sessionName,
                    startDirectory = dest.startDirectory,
                    composerDraft = currentComposerDraft,
                    savedAtMillis = System.currentTimeMillis(),
                ),
            )
        } else {
            lastSessionStore.clear()
        }
        super.onStop()
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
    // Issue #177: report the current top destination + the active session
    // composer draft up to the activity so `onStop` can persist the
    // in-session view for fast resume.
    onCurrentDestinationChanged: (AppDestination) -> Unit = {},
    onComposerDraftChanged: (String) -> Unit = {},
    // Issue #177: the composer draft restored from the persisted session,
    // seeded into the session screen on the restore path. Consumed once;
    // [onInitialComposerDraftConsumed] clears it so a later in-session
    // navigation does not re-seed a stale draft.
    initialComposerDraft: String = "",
    onInitialComposerDraftConsumed: () -> Unit = {},
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

    // Volatile in-memory back-stack state. The initial destination comes
    // from [requestedDestination], which the activity seeds with either an
    // intent route or — for a plain launch that would otherwise land on
    // the host list — the persisted last tmux session (#177 fast resume).
    // The back stack itself is still volatile by design (a back gesture
    // from a restored session returns the user to the host list).
    var current: AppDestination by remember {
        mutableStateOf(requestedDestination)
    }

    // Issue #177: report the current top destination up to the activity
    // so `onStop` can persist the in-session view. Fires on every
    // navigation, including the initial restored destination.
    LaunchedEffect(current) {
        onCurrentDestinationChanged(current)
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
        // envelope payload through the existing host-list import path.
        // The view model is the activity-scoped
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
        // local Room log rather than server-side `pocketshell usage` output.
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
            // Issue #230: route to the GitHub repos browser, reusing the
            // SSH credentials this folder screen already holds.
            onBrowseRepos = {
                navigate(
                    AppDestination.RepoBrowser(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                    ),
                )
            },
        )

        // Issue #230: GitHub repos browser. Lists the user's GitHub
        // repos joined with the host's cloned repos; tapping a repo
        // clones it (if needed) and opens a tmux session in the clone
        // folder. Reached from the FolderList "Repos" action.
        is AppDestination.RepoBrowser -> RepoBrowserScreen(
            hostName = dest.hostName,
            hostname = dest.hostname,
            port = dest.port,
            username = dest.username,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            onBack = ::back,
            onOpenRepo = { path ->
                navigate(
                    AppDestination.TmuxSession(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        sessionName = RepoSessionName(path),
                        startDirectory = path,
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
            // Issue #177: seed the restored composer draft into the agent
            // composer so a fast-resumed session comes back with the
            // user's half-typed message, and report draft edits up so the
            // next `onStop` persists them.
            initialComposerDraft = initialComposerDraft,
            onInitialComposerDraftConsumed = onInitialComposerDraftConsumed,
            onComposerDraftChanged = onComposerDraftChanged,
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
 * Issue #177: decide the activity's initial destination, distinguishing a
 * genuine app-switch / process-death resume from a deliberate cold launch.
 *
 * Pulled out as a pure function so the route-restore decision — the part the
 * reviewer flagged as hijacking every plain relaunch — is unit-testable
 * without an Activity. The rules, in priority order:
 *
 *  1. An explicit intent route (deep link, QS-tile forwarding chooser) always
 *     wins; we never override it with a restored session.
 *  2. Otherwise the base destination is the host list. We replace it with the
 *     restored tmux session ONLY when [resumingFromProcessDeath] is true AND a
 *     fresh [restoredDestination] was supplied. `resumingFromProcessDeath`
 *     maps to `savedInstanceState != null` in `onCreate`: the system passes a
 *     non-null bundle only when it re-creates the activity after reaping our
 *     backgrounded process. A user-driven cold launch / explicit close +
 *     relaunch passes a null bundle, so this returns the host list — the
 *     documented contract `ColdInstallE2eTest`, `RealAgentReleaseGateTest`,
 *     and `EmulatorWorkflowE2eTest` assert against.
 *
 * The dominant app-switch case (Home then return while the process is alive)
 * never re-enters `onCreate`, so it never reaches this function; the existing
 * #235 `ON_START` reattach handles it from the surviving navigator state.
 */
internal fun resolveInitialDestination(
    intentDestination: AppDestination,
    resumingFromProcessDeath: Boolean,
    restoredDestination: AppDestination?,
): AppDestination {
    if (intentDestination != AppDestination.HostList) return intentDestination
    if (!resumingFromProcessDeath) return AppDestination.HostList
    return restoredDestination ?: AppDestination.HostList
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

/**
 * Issue #230: derive a tmux session name for a repo opened from the
 * GitHub repos browser. tmux session names must not contain colons (the
 * folder-list field separator) and stay short, so we take the trailing
 * path segment, sanitise it to `[A-Za-z0-9_-]`, and fall back to the
 * default when nothing usable remains.
 */
internal fun RepoSessionName(path: String): String {
    val tail = path.trim().trimEnd('/').substringAfterLast('/')
    val safe = tail.replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-').take(24)
    return safe.ifBlank { DefaultTmuxSessionName }
}
