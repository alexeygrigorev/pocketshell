package com.pocketshell.next

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.fragment.app.FragmentActivity
import androidx.core.view.WindowCompat
import androidx.navigation.navArgument
import com.pocketshell.next.connect.ConnectGate
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.connect.ConnectViewModel
import com.pocketshell.next.crash.DiagnosticReportScreen
import com.pocketshell.next.crash.DiagnosticsScreen
import com.pocketshell.next.files.FileExplorerRoute
import com.pocketshell.next.files.ViewerRoute
import com.pocketshell.next.hosts.AddEditHostRoute
import com.pocketshell.next.hosts.HOST_FORM_SELECTED_KEY_RESULT
import com.pocketshell.next.hosts.HostListRoute
import com.pocketshell.next.hosts.SshKeysRoute
import com.pocketshell.next.nav.Destination
import com.pocketshell.next.ports.AddTunnelRoute
import com.pocketshell.next.ports.PortForwardRoute
import com.pocketshell.next.ports.ServicesRoute
import com.pocketshell.next.ports.TunnelDetailRoute
import com.pocketshell.next.settings.LocalAppSettings
import com.pocketshell.next.settings.AboutRoute
import com.pocketshell.next.sync.AccountSyncRoute
import com.pocketshell.next.settings.AdvancedSettingsRoute
import com.pocketshell.next.settings.ConnectionSettingsRoute
import com.pocketshell.next.settings.GraceSettingsRoute
import com.pocketshell.next.settings.LanguageSettingsRoute
import com.pocketshell.next.settings.SettingsNavigation
import com.pocketshell.next.settings.SettingsRoute
import com.pocketshell.next.settings.SettingsViewModel
import com.pocketshell.next.settings.TerminalSettingsRoute
import com.pocketshell.next.settings.UpdateRoute
import com.pocketshell.next.settings.VoiceSettingsRoute
import com.pocketshell.next.settings.AddWorkspaceRootRoute
import com.pocketshell.next.settings.WorkspaceRootsRoute
import com.pocketshell.next.terminal.GraceCoordinator
import com.pocketshell.next.terminal.LastSessionStore
import com.pocketshell.next.terminal.SessionRoute
import com.pocketshell.next.usage.UsageRoute
import com.pocketshell.next.workspaces.HostWorkspacesRoute
import com.pocketshell.next.workspaces.ReorderWorkspacesRoute
import com.pocketshell.next.workspaces.WorkspaceStartRoute
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.uikit.theme.PocketShellTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The single Activity of app2 (plan §A.1). Everything is Compose; there are no
 * fragments and no second Activity.
 *
 * The graph was wired before the screens existed, so each U-task was a one-line
 * swap inside [AppNavHost] rather than a navigation change. Every route now
 * resolves to a REAL screen; the `RoutePlaceholder` scaffold those swaps
 * replaced is gone (D22 — superseded code is deleted, not left dark). It had to
 * go: journey J04 was still asserting on the placeholder's
 * `Session(hostId=…, name=…)` label long after U-4 stopped rendering it, and a
 * dead composable is exactly what lets an oracle like that look alive (#2478).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    /**
     * Task U-8. The background-grace policy has no other consumer, so something
     * has to create the singleton and wire it to the process/activity
     * lifecycles; this Activity is the moment the app first has a UI, and it is
     * launched identically in production and under instrumentation (where
     * `App` is replaced by `HiltTestApplication` and its `onCreate` never runs).
     * [GraceCoordinator.register] is idempotent, so a recreate is free.
     */
    @Inject
    lateinit var grace: GraceCoordinator

    @Inject
    lateinit var connections: ConnectionsRegistry

    @Inject
    lateinit var hostDao: HostDao

    /**
     * Issue #2632. Injected HERE rather than into [SessionViewModel] so the
     * recording edge is the NAVIGATION edge: "the user opened this session" is
     * exactly the moment the route composes, and nothing about the transport
     * (attach, reconnect, stop) should be able to change what we resume into.
     */
    @Inject
    lateinit var lastSessions: LastSessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        grace.register(application)
        // #887/#2533: after edge-to-edge, SOFT_INPUT_ADJUST_NOTHING so the OS
        // neither resizes nor pans the window when the keyboard shows.
        // enableEdgeToEdge already sets setDecorFitsSystemWindows(false), which
        // left the default ADJUST_UNSPECIFIED resolving to PAN — the black-top
        // / empty-void screenshot. ADJUST_NOTHING keeps the window FIXED: the
        // keyboard overlays the terminal. Because decorFitsSystemWindows is
        // still false, the IME inset is STILL dispatched to Compose as
        // WindowInsets.ime, so sheets/forms that opt into imePadding keep
        // working. The session column must not consume those insets.
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.statusBarColor = AndroidColor.rgb(16, 23, 30)
        window.navigationBarColor = AndroidColor.rgb(16, 23, 30)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        setContent {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // The window draws edge to edge (enableEdgeToEdge above;
                    // targetSdk 35 also makes that non-optional), so content
                    // must be inset out from under the status/navigation bars
                    // or the first row of any screen renders under the clock.
                    // IME insets are deliberately NOT consumed here: the
                    // session column stays full-bleed under the keyboard
                    // (#887/#2533); sheets and forms that need lifting apply
                    // their own imePadding.
                    //
                    // Task P-6: the settings snapshot is collected ONCE here and
                    // provided through `LocalAppSettings` (see that file's class
                    // doc for why a CompositionLocal rather than another
                    // ViewModel threaded through every screen). `hiltViewModel()`
                    // resolves against this Activity, which is the one thing a
                    // Robolectric `AppNavHost`-only composition (the nav tests)
                    // cannot provide — those compose `AppNavHost` directly and so
                    // never reach this line, which is why they still see
                    // `LocalAppSettings`'s default value rather than a crash.
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val appSettings by settingsViewModel.state.collectAsState()
                    CompositionLocalProvider(LocalAppSettings provides appSettings) {
                            AppNavHost(
                                modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
                                connections = connections,
                                startupHostId = appSettings.defaultHostId,
                                startupHostExists = { hostDao.getById(it) != null },
                                onHostOpened = settingsViewModel::setDefaultHostId,
                                onSessionOpened = { hostId, sessionName, workspacePath ->
                                    lastSessions.record(hostId, sessionName, workspacePath)
                                },
                            )
                    }
                }
            }
        }
    }
}

/**
 * Every non-dial action the host list can start. Grouped into one type because
 * the list is the app's landing screen and now carries four of them — passing
 * them as four positional lambdas through the [AppNavHost] seam made both the
 * production call and every test stand-in unreadable.
 */
/**
 * The session screen's navigation edges, as one object (#2635 N1/N2).
 *
 * Same reason [HostListActions] exists: a `@Composable (...) -> Unit` seam
 * parameter has a hard arity cliff in the Compose compiler, and N1/N2 pushed
 * this one past it (tunnels reach the terminal's actions sheet, and the
 * switcher sheet can now open a session in a NEIGHBOURING workspace). Bundling
 * is also simply better here — the call site names each edge instead of relying
 * on ten positional lambdas lining up.
 */
data class SessionScreenActions(
    val onBack: () -> Unit,
    val onOpenUsage: () -> Unit,
    val onOpenFiles: () -> Unit,
    val onOpenPorts: () -> Unit,
    val onOpenSession: (SessionRow) -> Unit,
    val onOpenNewSession: () -> Unit,
    /** #2635 N2: open a session in another workspace on the same host. */
    val onOpenWorkspaceSession: (workspacePath: String, entrySessionName: String) -> Unit,
)

data class HostListActions(
    val onOpenHost: (Long) -> Unit,
    val onAddHost: () -> Unit,
    val onEditHost: (Long) -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenSshKeys: () -> Unit,
    /** Issue #2632: the landing usage pill's destination. */
    val onOpenUsage: () -> Unit = {},
)

/**
 * Session switcher navigation reuses the existing concrete route when it is
 * already on the back stack. That keeps one terminal/ViewModel per
 * host-session-workspace identity and makes switching back return to the
 * existing terminal instead of stacking another copy of it.
 */
private fun NavHostController.openSession(
    hostId: Long,
    sessionName: String,
    workspacePath: String? = null,
) {
    val route = Destination.Session.route(hostId, sessionName, workspacePath)
    val existing = runCatching { getBackStackEntry(route) }.getOrNull()
    if (existing == null) {
        navigate(route)
        return
    }
    while (currentBackStackEntry !== existing) {
        if (!popBackStack()) {
            navigate(route) { launchSingleTop = true }
            return
        }
    }
}

/**
 * The app2 navigation graph. Routes come from [Destination] — no literal route
 * strings live here.
 *
 * The `*Screen` / `connectViewModel` parameters are seams, not feature flags:
 * the real screens (host list, connect gate, host workspaces, workspace,
 * terminal,
 * port-forward panel, file explorer, file viewer, host add/edit form, SSH
 * keys, crash reports) resolve their ViewModels through
 * `hiltViewModel()`, which needs a Hilt-managed Activity, so a plain
 * Robolectric `createComposeRule()` composition could not host them. The
 * parameters let a test supply the same screen / the same ViewModel built by
 * hand (over an in-memory database and a scripted connection factory) and
 * still exercise the real navigation edge — the production defaults are the
 * real ones.
 */
data class WorkspaceScreenLaunch(
    val initialRootPath: String? = null,
    val initialRootAction: String? = null,
    /**
     * Issue #2632: this visit was started by opening the HOST, so the host's
     * last session should reopen once the live listing confirms it is still
     * there. False for every other way of reaching the screen (a Back from a
     * session, a "new session" hop, a workspace-root action) — otherwise
     * leaving a terminal would immediately throw the user back into it.
     */
    val resumeLastSession: Boolean = false,
)

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
    connections: ConnectionsRegistry? = null,
    /** Last host to resume; null keeps the Hosts landing screen. */
    startupHostId: Long? = null,
    /** Drops a stale resume id when the host was removed since the last launch. */
    startupHostExists: suspend (Long) -> Boolean = { true },
    /** Persists the host selection without making the host list own settings. */
    onHostOpened: (Long) -> Unit = {},
    /**
     * Issue #2632: records the session a route just opened, so the next time
     * this host is opened it resumes there instead of the workspace list.
     * Defaulted to a no-op so a Robolectric nav test needs no store.
     */
    onSessionOpened: (hostId: Long, sessionName: String, workspacePath: String?) -> Unit =
        { _, _, _ -> },
    hostsScreen: @Composable (HostListActions) -> Unit = { actions ->
        HostListRoute(
            onOpenHost = actions.onOpenHost,
            onAddHost = actions.onAddHost,
            onEditHost = actions.onEditHost,
            onOpenSettings = actions.onOpenSettings,
            onOpenSshKeys = actions.onOpenSshKeys,
            onOpenUsage = actions.onOpenUsage,
            updateCheckViewModel = hiltViewModel(),
        )
    },
    connectViewModel: @Composable () -> ConnectViewModel = { hiltViewModel() },
    workspacesScreen: @Composable (
        hostId: Long,
        onOpenSession: (SessionRow) -> Unit,
        // #2635 N1: the empty-workspace edge, on the seam so a navigation test
        // can drive it. It replaced `onOpenWorkspace`, which pointed at the
        // deleted `Destination.Workspace`.
        onStartSessionAtPath: (String) -> Unit,
        onOpenFiles: () -> Unit,
        onOpenFilesAtPath: (String) -> Unit,
        onOpenPorts: () -> Unit,
        onBack: () -> Unit,
        onOpenUsage: () -> Unit,
        launch: WorkspaceScreenLaunch,
    ) -> Unit = {
        hostId, onOpenSession, onStartSessionAtPath, onOpenFiles, onOpenFilesAtPath,
        onOpenPorts, onBack, onOpenUsage, launch,
        ->
        val scope = rememberCoroutineScope()
        HostWorkspacesRoute(
            onOpenSession = onOpenSession,
            onOpenFiles = onOpenFiles,
            onOpenFilesAtPath = onOpenFilesAtPath,
            onOpenPorts = onOpenPorts,
            onBack = onBack,
            onOpenUsage = onOpenUsage,
            onOpenReorder = { navController.navigate(Destination.ReorderWorkspaces.route(hostId)) },
            onOpenProjectRoots = { navController.navigate(Destination.WorkspaceRoots.route(hostId)) },
            onOpenConnectionDetails = { navController.navigate(Destination.HostForm.route(hostId)) },
            onDisconnect = {
                scope.launch {
                    connections?.close(hostId)
                    navController.popBackStack()
                }
            },
            onStartSessionAtPath = onStartSessionAtPath,
            initialRootPath = launch.initialRootPath,
            initialRootAction = launch.initialRootAction,
            resumeLastSession = launch.resumeLastSession,
            usageGlanceViewModel = hiltViewModel(),
        )
    },
    /**
     * #2635 N1: the `workspaceScreen` seam is gone with the screen it injected.
     * This one replaced it — the create-sheet page an EMPTY workspace opens —
     * and it is a seam for the same reason the others are: the real route
     * resolves a Hilt ViewModel, which a plain navigation test has no graph for.
     */
    workspaceStartScreen: @Composable (
        hostId: Long,
        workspacePath: String,
        onOpenSession: (String) -> Unit,
        onBack: () -> Unit,
    ) -> Unit = { _, workspacePath, onOpenSession, onBack ->
        WorkspaceStartRoute(
            workspacePath = workspacePath,
            onOpenSession = onOpenSession,
            onBack = onBack,
        )
    },
    sessionScreen: @Composable (
        hostId: Long,
        sessionName: String,
        workspacePath: String?,
        actions: SessionScreenActions,
    ) -> Unit = { hostId, sessionName, workspacePath, actions ->
        SessionRoute(
            hostId = hostId,
            sessionName = sessionName,
            workspacePath = workspacePath,
            onBack = actions.onBack,
            onOpenUsage = actions.onOpenUsage,
            onOpenFiles = actions.onOpenFiles,
            onOpenPorts = actions.onOpenPorts,
            onOpenSession = actions.onOpenSession,
            onOpenNewSession = actions.onOpenNewSession,
            onOpenWorkspaceSession = actions.onOpenWorkspaceSession,
        )
    },
    portsScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        PortForwardRoute(onBack = onBack)
    },
    servicesScreen: @Composable (
        onBack: () -> Unit,
        onOpenTunnel: (Int) -> Unit,
        onAddTunnel: (Int?) -> Unit,
    ) -> Unit = { onBack, onOpenTunnel, onAddTunnel ->
        ServicesRoute(
            onBack = onBack,
            onOpenTunnel = onOpenTunnel,
            onAddTunnel = onAddTunnel,
        )
    },
    tunnelDetailScreen: @Composable (remotePort: Int, onBack: () -> Unit) -> Unit =
        { remotePort, onBack -> TunnelDetailRoute(remotePort = remotePort, onBack = onBack) },
    addTunnelScreen: @Composable (remotePort: Int?, onDone: () -> Unit) -> Unit =
        { remotePort, onDone -> AddTunnelRoute(initialRemotePort = remotePort, onDone = onDone) },
    filesScreen: @Composable (
        hostId: Long,
        path: String?,
        onOpenFile: (String) -> Unit,
        onBack: () -> Unit,
    ) -> Unit = { _, _, onOpenFile, onBack ->
        FileExplorerRoute(onOpenFile = onOpenFile, onBack = onBack)
    },
    viewerScreen: @Composable (hostId: Long, path: String?, onBack: () -> Unit) -> Unit =
        { _, _, onBack -> ViewerRoute(onBack = onBack) },
    hostFormScreen: @Composable (
        hostId: Long?,
        onDone: () -> Unit,
        onAddKey: () -> Unit,
        onTestConnection: (Long) -> Unit,
    ) -> Unit =
        { hostId, onDone, onAddKey, onTestConnection ->
            AddEditHostRoute(
                hostId = hostId,
                onDone = onDone,
                onAddKey = onAddKey,
                onTestConnection = onTestConnection,
            )
        },
    sshKeysScreen: @Composable (
        onBack: () -> Unit,
        onUseKey: ((Long) -> Unit)?,
    ) -> Unit = { onBack, onUseKey ->
        SshKeysRoute(onBack = onBack, onUseKey = onUseKey)
    },
    settingsScreen: @Composable (SettingsNavigation) -> Unit = { navigation ->
        SettingsRoute(navigation = navigation)
    },
    terminalSettingsScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        TerminalSettingsRoute(onBack = onBack)
    },
    voiceSettingsScreen: @Composable (onBack: () -> Unit, onOpenLanguage: () -> Unit) -> Unit =
        { onBack, onOpenLanguage ->
            VoiceSettingsRoute(onBack = onBack, onOpenLanguage = onOpenLanguage)
        },
    languageSettingsScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        LanguageSettingsRoute(onBack = onBack)
    },
    connectionSettingsScreen: @Composable (
        onBack: () -> Unit,
        onOpenGrace: () -> Unit,
        onOpenWorkspaceRoots: (Long) -> Unit,
    ) -> Unit = { onBack, onOpenGrace, onOpenWorkspaceRoots ->
        ConnectionSettingsRoute(
            onBack = onBack,
            onOpenGrace = onOpenGrace,
            onOpenWorkspaceRoots = onOpenWorkspaceRoots,
        )
    },
    graceSettingsScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        GraceSettingsRoute(onBack = onBack)
    },
    advancedSettingsScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        AdvancedSettingsRoute(onBack = onBack)
    },
    accountSyncScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        AccountSyncRoute(onBack = onBack)
    },
    diagnosticsScreen: @Composable (
        onBack: () -> Unit,
        onOpenReport: (String) -> Unit,
    ) -> Unit = { onBack, onOpenReport ->
        DiagnosticsScreen(onBack = onBack, onOpenReport = onOpenReport)
    },
    diagnosticReportScreen: @Composable (reportId: String, onBack: () -> Unit) -> Unit =
        { reportId, onBack ->
            DiagnosticReportScreen(reportId = reportId, onBack = onBack)
        },
    aboutScreen: @Composable (onBack: () -> Unit, onOpenUpdate: () -> Unit) -> Unit =
        { onBack, onOpenUpdate ->
            AboutRoute(onBack = onBack, onOpenUpdate = onOpenUpdate)
        },
    updateScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        UpdateRoute(onBack = onBack)
    },
    workspaceRootsScreen: @Composable (hostId: Long, onBack: () -> Unit) -> Unit =
        { hostId, onBack ->
            WorkspaceRootsRoute(
                onBack = onBack,
                onOpenAddRoot = { navController.navigate(Destination.AddWorkspaceRoot.route(hostId)) },
                onRootAction = { root, action ->
                    when (action) {
                        com.pocketshell.next.settings.WorkspaceRootMenuAction.ADD_WORKSPACE,
                        com.pocketshell.next.settings.WorkspaceRootMenuAction.CREATE_FOLDER,
                        -> navController.navigate(
                            Destination.WorkspaceRootAction.route(
                                hostId = hostId,
                                rootPath = root.path,
                                action = action.name.lowercase().replace('_', '-'),
                            ),
                        )
                        com.pocketshell.next.settings.WorkspaceRootMenuAction.START_SESSION ->
                            navController.navigate(Destination.WorkspaceStart.route(hostId, root.path))
                        com.pocketshell.next.settings.WorkspaceRootMenuAction.BROWSE_ROOT ->
                            navController.navigate(Destination.Files.route(hostId, root.path))
                        com.pocketshell.next.settings.WorkspaceRootMenuAction.REMOVE_ROOT -> Unit
                    }
                },
            )
        },
    workspaceRootAddScreen: @Composable (
        hostId: Long,
        onBack: () -> Unit,
        onAdded: () -> Unit,
    ) -> Unit = { _, onBack, onAdded ->
        AddWorkspaceRootRoute(onBack = onBack, onAdded = onAdded)
    },
    usageScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        UsageRoute(onBack = onBack)
    },
    hostUsageScreen: @Composable (hostId: Long, onBack: () -> Unit) -> Unit = { hostId, onBack ->
        UsageRoute(onBack = onBack, selectedHostId = hostId)
    },
) {
    // The startup id is resolved once per Activity. A host-row tap updates the
    // live preference, but that update must never become another startup dial.
    val initialStartupHostId = remember { startupHostId }
    val startupHostToConnect = remember { mutableStateOf<Long?>(null) }

    // Issue #2632: which host, if any, the CURRENT navigation was started for
    // by opening that host — a cold-launch resume or a host-row tap. The
    // workspace destination consumes it exactly once, which is what keeps
    // "resume my last session" from turning into "you can never leave that
    // session": pressing Back returns to a workspace entry whose arm is spent.
    val hostOpenedForResume = remember { mutableStateOf<Long?>(null) }

    NavHost(
        navController = navController,
        startDestination = Destination.start.pattern,
        modifier = modifier,
        // Navigation Compose 2.9 fades destinations for 700 ms by default.
        // That leaves the outgoing Hosts layer visibly on top after the tree
        // destination has already composed, which makes a successful trust
        // handoff look stuck on "Connecting…". Hosts and the tree are full
        // screens, so an atomic handoff is both clearer and the settled state
        // the connection gate promises to the user.
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Destination.Hosts.pattern) {
            // Task U-2: a host tap DIALS. Only a connected host reaches the
            // tree; an unknown/changed host key raises the trust sheet first
            // and a failed dial keeps the user on the list with a retry.
            ConnectGate(
                onConnected = { hostId -> navController.navigate(Destination.Workspaces.route(hostId)) },
                viewModel = connectViewModel(),
                initialHostId = startupHostToConnect.value,
                onInitialHostConsumed = { startupHostToConnect.value = null },
            ) { onOpenHost ->
                hostsScreen(
                    HostListActions(
                        onOpenHost = { hostId ->
                            onHostOpened(hostId)
                            // Issue #2632: a host tap is a "take me back to
                            // what I was doing" gesture, so it arms the resume
                            // for the workspace destination the gate opens.
                            hostOpenedForResume.value = hostId
                            onOpenHost(hostId)
                        },
                        onOpenUsage = { navController.navigate(Destination.Usage.route()) },
                        // Task P-6: the management routes are plain
                        // navigations, deliberately NOT gated by the connect
                        // gate — editing a host must work while the host is
                        // unreachable, which is exactly when a user goes
                        // looking for the form.
                        onAddHost = { navController.navigate(Destination.HostForm.route()) },
                        onEditHost = { hostId ->
                            navController.navigate(Destination.HostForm.route(hostId))
                        },
                        onOpenSshKeys = { navController.navigate(Destination.SshKeys.route()) },
                        // Task P-6 fast-follow: the only UI entry point into
                        // Settings, deliberately on the landing screen rather
                        // than a mid-session terminal action.
                        onOpenSettings = { navController.navigate(Destination.Settings.route()) },
                    ),
                )
            }
        }
        composable(
            route = Destination.HostForm.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) {
                    type = NavType.LongType
                    defaultValue = Destination.NO_HOST_ID
                },
            ),
        ) { entry ->
            // Task P-6. The sentinel is normalised to `null` HERE, once, so the
            // form's "am I editing?" question has a single answer derived from
            // the route rather than a `-1` leaking into the ViewModel.
            val raw = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: Destination.NO_HOST_ID
            ConnectGate(
                onConnected = { connectedHostId ->
                    navController.navigate(Destination.Workspaces.route(connectedHostId)) {
                        // A successful form test is the access boundary. Keep
                        // Hosts below the new tree, but do not leave a stale
                        // form on the Back stack.
                        popUpTo(Destination.Hosts.pattern)
                    }
                },
                viewModel = connectViewModel(),
            ) { onOpenHost ->
                hostFormScreen(
                    raw.takeIf { it > 0L },
                    { navController.popBackStack() },
                    { navController.navigate(Destination.SshKeys.route()) },
                    // #2635 N3: the form's "Test connection" is the same
                    // gesture as a host-row tap — "take me to this machine" —
                    // so it arms the resume too. Without this, saving a host
                    // you already use landed you on the workspace list while
                    // tapping the same host from the list landed you in your
                    // terminal, which is one flow behaving two ways.
                    { hostId ->
                        hostOpenedForResume.value = hostId
                        onOpenHost(hostId)
                    },
                )
            }
        }
        composable(Destination.SshKeys.pattern) {
            val previous = navController.previousBackStackEntry
            val canSelectForHostForm = previous?.destination?.route == Destination.HostForm.pattern
            sshKeysScreen(
                { navController.popBackStack() },
                if (canSelectForHostForm) {
                    { keyId ->
                        previous?.savedStateHandle?.set(HOST_FORM_SELECTED_KEY_RESULT, keyId)
                        navController.popBackStack()
                    }
                } else {
                    null
                },
            )
        }
        composable(
            route = Destination.Workspaces.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) { entry ->
            // Quiet redesign: the host workspaces screen. The hostId is read from the
            // route here only to hand it to the seam; the ViewModel resolves it
            // from its own SavedStateHandle, so the screen keeps working under
            // process death without the navigation layer re-supplying it.
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            val onOpenSession: (SessionRow) -> Unit = { session ->
                navController.openSession(hostId, session.name, session.workspace)
            }
            val onOpenFiles: () -> Unit = { navController.navigate(Destination.Files.route(hostId)) }
            val onOpenPorts: () -> Unit = { navController.navigate(Destination.Ports.route(hostId)) }
            val onBack: () -> Unit = { navController.popBackStack() }
            val onOpenUsage: () -> Unit = { navController.navigate(Destination.HostUsage.route(hostId)) }
            // Issue #2632: consumed once, in the composition of THIS entry. A
            // Back from the resumed session recomposes this entry with the arm
            // already spent, so the user lands on the workspace list.
            val resumeLastSession = remember {
                (hostOpenedForResume.value == hostId).also {
                    if (it) hostOpenedForResume.value = null
                }
            }
            workspacesScreen(
                hostId,
                onOpenSession,
                { path -> navController.navigate(Destination.WorkspaceStart.route(hostId, path)) },
                onOpenFiles,
                { path -> navController.navigate(Destination.Files.route(hostId, path)) },
                onOpenPorts,
                onBack,
                onOpenUsage,
                WorkspaceScreenLaunch(resumeLastSession = resumeLastSession),
            )
        }
        composable(
            route = Destination.WorkspaceRootAction.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_ROOT_PATH) { type = NavType.StringType },
                navArgument(Destination.ARG_ROOT_ACTION) { type = NavType.StringType },
            ),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            val rootPath = entry.arguments?.getString(Destination.ARG_ROOT_PATH).orEmpty()
            val action = entry.arguments?.getString(Destination.ARG_ROOT_ACTION).orEmpty()
            val onOpenSession: (SessionRow) -> Unit = { session ->
                navController.openSession(hostId, session.name, session.workspace)
            }
            workspacesScreen(
                hostId,
                onOpenSession,
                { path -> navController.navigate(Destination.WorkspaceStart.route(hostId, path)) },
                { navController.navigate(Destination.Files.route(hostId)) },
                { path -> navController.navigate(Destination.Files.route(hostId, path)) },
                { navController.navigate(Destination.Ports.route(hostId)) },
                { navController.popBackStack() },
                { navController.navigate(Destination.HostUsage.route(hostId)) },
                WorkspaceScreenLaunch(rootPath, action),
            )
        }
        // #2635 N1 (maintainer-approved route change): there is no
        // `Destination.Workspace`. A workspace tap opens its terminal
        // directly, and a workspace with nothing running opens its create
        // sheet — the page that listed a workspace's sessions as rows to tap a
        // second time is deleted, not hidden behind a flag (D22).
        composable(
            route = Destination.WorkspaceStart.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_WORKSPACE_PATH) { type = NavType.StringType },
            ),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            val path = entry.arguments?.getString(Destination.ARG_WORKSPACE_PATH).orEmpty()
            workspaceStartScreen(
                hostId,
                path,
                { sessionName -> navController.openSession(hostId, sessionName, path) },
                { navController.popBackStack() },
            )
        }
        composable(
            route = Destination.ReorderWorkspaces.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) {
            ReorderWorkspacesRoute(onBack = { navController.popBackStack() })
        }
        composable(
            route = Destination.Session.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_SESSION_NAME) { type = NavType.StringType },
                navArgument(Destination.ARG_WORKSPACE_PATH) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            // Task U-4: the real terminal. The session name arrives already
            // percent-decoded by the navigation library, so a session called
            // `my project:review` reaches `sessions attach` byte-identical —
            // which matters, because the name IS the identity the host CLI
            // resolves against (plan §B.0).
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            val name = entry.arguments?.getString(Destination.ARG_SESSION_NAME).orEmpty()
            val workspacePath = entry.arguments?.getString(Destination.ARG_WORKSPACE_PATH)
            // Issue #2632: reaching this route IS "the user opened this
            // session", including via the switcher, the tab strip, a deep
            // link, and a process-death restoration of the back stack.
            LaunchedEffect(hostId, name, workspacePath) {
                onSessionOpened(hostId, name, workspacePath)
            }
            sessionScreen(
                hostId,
                name,
                workspacePath,
                SessionScreenActions(
                    onBack = { navController.popBackStack() },
                    // Task P-5: the top bar's usage glance pill navigates here.
                    onOpenUsage = { navController.navigate(Destination.HostUsage.route(hostId)) },
                    onOpenFiles = {
                        navController.navigate(Destination.Files.route(hostId, workspacePath))
                    },
                    // #2635 N1: tunnels reach the terminal's own actions sheet.
                    // They were on the deleted workspace page, which left them
                    // unreachable from a terminal at all.
                    onOpenPorts = { navController.navigate(Destination.Ports.route(hostId)) },
                    onOpenSession = { session ->
                        navController.openSession(hostId, session.name, session.workspace)
                    },
                    onOpenNewSession = {
                        if (workspacePath.isNullOrBlank()) {
                            navController.navigate(Destination.Workspaces.route(hostId))
                        } else {
                            navController.navigate(
                                Destination.WorkspaceStart.route(hostId, workspacePath),
                            )
                        }
                    },
                    // #2635 N2: a neighbouring workspace opens in place,
                    // without a trip back through the workspace list.
                    onOpenWorkspaceSession = { otherWorkspacePath, entrySessionName ->
                        navController.openSession(hostId, entrySessionName, otherWorkspacePath)
                    },
                ),
            )
        }
        composable(
            route = Destination.Files.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_PATH) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            // Task P-3a: the real remote file explorer. Like the tree, the
            // ViewModel reads both arguments from its own SavedStateHandle, so
            // the screen survives process death without navigation re-supplying
            // them; the hostId is read here only to build the viewer route.
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            filesScreen(
                hostId,
                entry.arguments?.getString(Destination.ARG_PATH),
                { filePath -> navController.navigate(Destination.FileViewer.route(hostId, filePath)) },
                { navController.popBackStack() },
            )
        }
        composable(
            route = Destination.FileViewer.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_PATH) { type = NavType.StringType },
            ),
        ) { entry ->
            // Task P-3b: the real file viewer/editor.
            viewerScreen(
                entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L,
                entry.arguments?.getString(Destination.ARG_PATH),
            ) { navController.popBackStack() }
        }
        composable(
            route = Destination.Ports.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            servicesScreen(
                { navController.popBackStack() },
                { remotePort -> navController.navigate(Destination.TunnelDetail.route(hostId, remotePort)) },
                { remotePort -> navController.navigate(Destination.AddTunnel.route(hostId, remotePort)) },
            )
        }
        composable(
            route = Destination.TunnelDetail.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_REMOTE_PORT) { type = NavType.IntType },
            ),
        ) { entry ->
            val remotePort = entry.arguments?.getInt(Destination.ARG_REMOTE_PORT)
                ?: Destination.NO_REMOTE_PORT
            tunnelDetailScreen(remotePort) { navController.popBackStack() }
        }
        composable(
            route = Destination.AddTunnel.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_REMOTE_PORT) {
                    type = NavType.IntType
                    defaultValue = Destination.NO_REMOTE_PORT
                },
            ),
        ) { entry ->
            val rawRemotePort = entry.arguments?.getInt(Destination.ARG_REMOTE_PORT)
                ?: Destination.NO_REMOTE_PORT
            addTunnelScreen(rawRemotePort.takeIf { it > 0 }) { navController.popBackStack() }
        }
        composable(Destination.Settings.pattern) {
            settingsScreen(
                SettingsNavigation(
                    onBack = { navController.popBackStack() },
                    onOpenTerminal = { navController.navigate(Destination.TerminalSettings.route()) },
                    onOpenVoice = { navController.navigate(Destination.VoiceSettings.route()) },
                    onOpenConnections = { navController.navigate(Destination.ConnectionSettings.route()) },
                    onOpenAdvanced = { navController.navigate(Destination.AdvancedSettings.route()) },
                    onOpenAccount = { navController.navigate(Destination.AccountSync.route()) },
                    onOpenDiagnostics = { navController.navigate(Destination.Diagnostics.route()) },
                    onOpenAbout = { navController.navigate(Destination.About.route()) },
                ),
            )
        }
        composable(Destination.TerminalSettings.pattern) {
            terminalSettingsScreen { navController.popBackStack() }
        }
        composable(Destination.VoiceSettings.pattern) {
            voiceSettingsScreen(
                { navController.popBackStack() },
                { navController.navigate(Destination.VoiceLanguage.route()) },
            )
        }
        composable(Destination.VoiceLanguage.pattern) {
            languageSettingsScreen { navController.popBackStack() }
        }
        composable(Destination.ConnectionSettings.pattern) {
            connectionSettingsScreen(
                { navController.popBackStack() },
                { navController.navigate(Destination.GraceSettings.route()) },
                { hostId -> navController.navigate(Destination.WorkspaceRoots.route(hostId)) },
            )
        }
        composable(Destination.GraceSettings.pattern) {
            graceSettingsScreen { navController.popBackStack() }
        }
        composable(Destination.AdvancedSettings.pattern) {
            advancedSettingsScreen { navController.popBackStack() }
        }
        composable(Destination.AccountSync.pattern) {
            accountSyncScreen { navController.popBackStack() }
        }
        composable(Destination.Diagnostics.pattern) {
            diagnosticsScreen(
                { navController.popBackStack() },
                { reportId -> navController.navigate(Destination.DiagnosticReport.route(reportId)) },
            )
        }
        composable(
            route = Destination.DiagnosticReport.pattern,
            arguments = listOf(navArgument(Destination.ARG_REPORT_ID) { type = NavType.StringType }),
        ) { entry ->
            val reportId = entry.arguments?.getString(Destination.ARG_REPORT_ID).orEmpty()
            diagnosticReportScreen(reportId) { navController.popBackStack() }
        }
        composable(Destination.About.pattern) {
            aboutScreen(
                { navController.popBackStack() },
                { navController.navigate(Destination.Update.route()) },
            )
        }
        composable(Destination.Update.pattern) {
            updateScreen { navController.popBackStack() }
        }
        composable(
            route = Destination.WorkspaceRoots.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            workspaceRootsScreen(hostId) { navController.popBackStack() }
        }
        composable(
            route = Destination.AddWorkspaceRoot.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            workspaceRootAddScreen(
                hostId,
                { navController.popBackStack() },
                { navController.popBackStack() },
            )
        }
        composable(Destination.Usage.pattern) {
            // Task P-5: the real usage/quota panel.
            usageScreen { navController.popBackStack() }
        }
        composable(
            route = Destination.HostUsage.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            hostUsageScreen(hostId) { navController.popBackStack() }
        }
    }

    // This is a cold-launch handoff, not a live observer. The Hosts row writes
    // the last opened host immediately when it is tapped; reacting to that
    // write here would start a second startup dial while the trust sheet is
    // still waiting for the user's decision.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, initialStartupHostId) {
        var startupAttempted = false
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // `repeatOnLifecycle` is only the readiness gate here. The resume
            // handoff is a cold-start action and must run at most once: if the
            // user backs out to Hosts during this Activity, repeating it would
            // immediately send them back to the workspace and can leave a
            // NavBackStackEntry below CREATED during teardown.
            if (startupAttempted) return@repeatOnLifecycle
            startupAttempted = true
            val hostId = initialStartupHostId ?: return@repeatOnLifecycle
            // Let NavHost finish attaching the start entry before adding a
            // second entry. Without this frame boundary a very fast activity
            // teardown can destroy the new entry while it is still INITIALIZED.
            withFrameNanos { }
            if (
                hostId <= 0L ||
                navController.currentDestination?.route != Destination.Hosts.pattern
            ) {
                return@repeatOnLifecycle
            }
            if (!startupHostExists(hostId)) return@repeatOnLifecycle
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                return@repeatOnLifecycle
            }
            // ConnectGate owns the dial, trust prompt, retry, and success
            // navigation. This handoff supplies only the validated id.
            // Issue #2632: a cold launch that already resumes the host should
            // land on the terminal the user actually left, not one level above
            // it — so the workspace destination this dial opens is armed.
            hostOpenedForResume.value = hostId
            startupHostToConnect.value = hostId
        }
    }
}
