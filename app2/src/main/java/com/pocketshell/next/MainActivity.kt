package com.pocketshell.next

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.navArgument
import com.pocketshell.next.connect.ConnectGate
import com.pocketshell.next.connect.ConnectViewModel
import com.pocketshell.next.crash.CrashReportsScreen
import com.pocketshell.next.files.FileExplorerRoute
import com.pocketshell.next.files.ViewerRoute
import com.pocketshell.next.hosts.AddEditHostRoute
import com.pocketshell.next.hosts.HostListRoute
import com.pocketshell.next.hosts.QrScannerRoute
import com.pocketshell.next.hosts.SshKeysRoute
import com.pocketshell.next.nav.Destination
import com.pocketshell.next.ports.PortForwardRoute
import com.pocketshell.next.settings.LocalAppSettings
import com.pocketshell.next.settings.SettingsRoute
import com.pocketshell.next.settings.SettingsViewModel
import com.pocketshell.next.settings.WorkspaceRootsRoute
import com.pocketshell.next.terminal.GraceCoordinator
import com.pocketshell.next.terminal.SessionRoute
import com.pocketshell.next.tree.SessionTreeRoute
import com.pocketshell.next.usage.UsageRoute
import com.pocketshell.uikit.theme.PocketShellTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

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
class MainActivity : ComponentActivity() {

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
                        )
                    }
                }
            }
        }
    }
}

/**
 * Every non-dial action the host list can start. Grouped into one type because
 * the list is the app's landing screen and now carries five of them — passing
 * them as five positional lambdas through the [AppNavHost] seam made both the
 * production call and every test stand-in unreadable.
 */
data class HostListActions(
    val onOpenHost: (Long) -> Unit,
    val onAddHost: () -> Unit,
    val onEditHost: (Long) -> Unit,
    val onScanQr: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/**
 * The app2 navigation graph. Routes come from [Destination] — no literal route
 * strings live here.
 *
 * The `*Screen` / `connectViewModel` parameters are seams, not feature flags:
 * the real screens (host list, connect gate, session tree, terminal,
 * port-forward panel, file explorer, file viewer, host add/edit form, SSH
 * keys, QR scan, crash reports) resolve their ViewModels through
 * `hiltViewModel()`, which needs a Hilt-managed Activity, so a plain
 * Robolectric `createComposeRule()` composition could not host them. The
 * parameters let a test supply the same screen / the same ViewModel built by
 * hand (over an in-memory database and a scripted connection factory) and
 * still exercise the real navigation edge — the production defaults are the
 * real ones.
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
    hostsScreen: @Composable (HostListActions) -> Unit = { actions ->
        HostListRoute(
            onOpenHost = actions.onOpenHost,
            onAddHost = actions.onAddHost,
            onEditHost = actions.onEditHost,
            onScanQr = actions.onScanQr,
            onOpenSettings = actions.onOpenSettings,
            updateCheckViewModel = hiltViewModel(),
        )
    },
    connectViewModel: @Composable () -> ConnectViewModel = { hiltViewModel() },
    treeScreen: @Composable (
        hostId: Long,
        onOpenSession: (String) -> Unit,
        onOpenFiles: () -> Unit,
        onOpenPorts: () -> Unit,
        onBack: () -> Unit,
        onOpenUsage: () -> Unit,
    ) -> Unit = { _, onOpenSession, onOpenFiles, onOpenPorts, onBack, onOpenUsage ->
        SessionTreeRoute(
            onOpenSession = onOpenSession,
            onOpenFiles = onOpenFiles,
            onOpenPorts = onOpenPorts,
            onBack = onBack,
            onOpenUsage = onOpenUsage,
            usageGlanceViewModel = hiltViewModel(),
        )
    },
    sessionScreen: @Composable (
        hostId: Long,
        sessionName: String,
        onBack: () -> Unit,
        onOpenUsage: () -> Unit,
    ) -> Unit = { hostId, sessionName, onBack, onOpenUsage ->
        SessionRoute(
            hostId = hostId,
            sessionName = sessionName,
            onBack = onBack,
            onOpenUsage = onOpenUsage,
        )
    },
    portsScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        PortForwardRoute(onBack = onBack)
    },
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
    hostFormScreen: @Composable (hostId: Long?, onDone: () -> Unit, onAddKey: () -> Unit) -> Unit =
        { hostId, onDone, onAddKey ->
            AddEditHostRoute(hostId = hostId, onDone = onDone, onAddKey = onAddKey)
        },
    sshKeysScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        SshKeysRoute(onBack = onBack)
    },
    qrScanScreen: @Composable (onFinished: (String) -> Unit, onClose: () -> Unit) -> Unit =
        { onFinished, onClose -> QrScannerRoute(onFinished = onFinished, onClose = onClose) },
    settingsScreen: @Composable (
        onBack: () -> Unit,
        onOpenWorkspaceRoots: (Long) -> Unit,
        onOpenCrashReports: () -> Unit,
    ) -> Unit = { onBack, onOpenWorkspaceRoots, onOpenCrashReports ->
        SettingsRoute(
            onBack = onBack,
            onOpenWorkspaceRoots = onOpenWorkspaceRoots,
            onOpenCrashReports = onOpenCrashReports,
            updateCheckViewModel = hiltViewModel(),
        )
    },
    workspaceRootsScreen: @Composable (hostId: Long, onBack: () -> Unit) -> Unit =
        { _, onBack -> WorkspaceRootsRoute(onBack = onBack) },
    usageScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        UsageRoute(onBack = onBack)
    },
    crashReportsScreen: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        CrashReportsScreen(onBack = onBack)
    },
) {
    NavHost(
        navController = navController,
        startDestination = Destination.start.pattern,
        modifier = modifier,
    ) {
        composable(Destination.Hosts.pattern) {
            // Task U-2: a host tap DIALS. Only a connected host reaches the
            // tree; an unknown/changed host key raises the trust sheet first
            // and a failed dial keeps the user on the list with a retry.
            ConnectGate(
                onConnected = { hostId -> navController.navigate(Destination.Tree.route(hostId)) },
                viewModel = connectViewModel(),
            ) { onOpenHost ->
                hostsScreen(
                    HostListActions(
                        onOpenHost = onOpenHost,
                        // Task P-6: the management routes are plain
                        // navigations, deliberately NOT gated by the connect
                        // gate — editing a host must work while the host is
                        // unreachable, which is exactly when a user goes
                        // looking for the form.
                        onAddHost = { navController.navigate(Destination.HostForm.route()) },
                        onEditHost = { hostId ->
                            navController.navigate(Destination.HostForm.route(hostId))
                        },
                        onScanQr = { navController.navigate(Destination.QrScan.route()) },
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
            hostFormScreen(
                raw.takeIf { it > 0L },
                { navController.popBackStack() },
                { navController.navigate(Destination.SshKeys.route()) },
            )
        }
        composable(Destination.SshKeys.pattern) {
            sshKeysScreen { navController.popBackStack() }
        }
        composable(Destination.QrScan.pattern) {
            qrScanScreen({ navController.popBackStack() }, { navController.popBackStack() })
        }
        composable(
            route = Destination.Tree.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) { entry ->
            // Task U-3: the real session tree. The hostId is read from the
            // route here only to hand it to the seam; the ViewModel resolves it
            // from its own SavedStateHandle, so the screen keeps working under
            // process death without the navigation layer re-supplying it.
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            treeScreen(
                hostId,
                { sessionName ->
                    navController.navigate(Destination.Session.route(hostId, sessionName))
                },
                // Task P-3a: the host's file browser. Opened with no path, so
                // the explorer resolves the account's home directory itself.
                // The plan's terminal kebab will later navigate to this same
                // route WITH the session's workspace path.
                { navController.navigate(Destination.Files.route(hostId)) },
                // Task P-4: the host's port-forward panel. Same host-scoped
                // rationale as Files — forwarding is not a per-session action.
                { navController.navigate(Destination.Ports.route(hostId)) },
                { navController.popBackStack() },
                // Issue #2532: Usage is a host-scoped panel, same as Files/Ports,
                // so the tree header is an entry point — not only the session
                // glance pill.
                { navController.navigate(Destination.Usage.route()) },
            )
        }
        composable(
            route = Destination.Session.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_SESSION_NAME) { type = NavType.StringType },
            ),
        ) { entry ->
            // Task U-4: the real terminal. The session name arrives already
            // percent-decoded by the navigation library, so a session called
            // `my project:review` reaches `sessions attach` byte-identical —
            // which matters, because the name IS the identity the host CLI
            // resolves against (plan §B.0).
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            val name = entry.arguments?.getString(Destination.ARG_SESSION_NAME).orEmpty()
            sessionScreen(
                hostId,
                name,
                { navController.popBackStack() },
                // Task P-5: the top bar's usage glance pill navigates here.
                { navController.navigate(Destination.Usage.route()) },
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
        ) {
            // Task P-4: the real port-forward panel. Like the tree, the ViewModel
            // reads the hostId from its own SavedStateHandle, so the screen keeps
            // working under process death without navigation re-supplying it.
            portsScreen { navController.popBackStack() }
        }
        composable(Destination.Settings.pattern) {
            // Task P-6: the real settings screen. Workspace roots is a
            // per-host sub-screen rather than an inline expando, because its
            // own add/delete actions and list need the vertical room a
            // Settings row cannot spare.
            settingsScreen(
                { navController.popBackStack() },
                { hostId -> navController.navigate(Destination.WorkspaceRoots.route(hostId)) },
                // Issue #2476: the only entry point into the crash-report
                // browser. Capture (`CrashReporter.install()` from
                // `App.onCreate`) never depended on this route; what was
                // missing was any way for a human to read what it recorded.
                { navController.navigate(Destination.CrashReports.route()) },
            )
        }
        composable(
            route = Destination.WorkspaceRoots.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID) ?: 0L
            workspaceRootsScreen(hostId) { navController.popBackStack() }
        }
        composable(Destination.Usage.pattern) {
            // Task P-5: the real usage/quota panel.
            usageScreen { navController.popBackStack() }
        }
        composable(Destination.CrashReports.pattern) {
            // Task P-10 / issue #2476: the local crash-report browser. Reached
            // from Settings → Diagnostics; argument-free, because the reports
            // are the installation's, not a host's.
            crashReportsScreen { navController.popBackStack() }
        }
    }
}
