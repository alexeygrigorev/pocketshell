package com.pocketshell.next

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.navArgument
import com.pocketshell.next.connect.ConnectGate
import com.pocketshell.next.connect.ConnectViewModel
import com.pocketshell.next.files.FileExplorerRoute
import com.pocketshell.next.files.ViewerRoute
import com.pocketshell.next.hosts.HostListRoute
import com.pocketshell.next.nav.Destination
import com.pocketshell.next.ports.PortForwardRoute
import com.pocketshell.next.terminal.SessionRoute
import com.pocketshell.next.tree.SessionTreeRoute
import com.pocketshell.uikit.theme.PocketShellTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single Activity of app2 (plan §A.1). Everything is Compose; there are no
 * fragments and no second Activity.
 *
 * Today it hosts the empty scaffold — each route renders a placeholder. The
 * U-tasks replace those placeholders with real screens one at a time, which is
 * why the graph is wired now: every later slice is a one-line swap inside
 * [AppNavHost] rather than a navigation change.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // The window draws edge to edge (targetSdk 35 makes that
                    // non-optional), so content must be inset out from under
                    // the status/navigation bars or the first row of any
                    // screen renders under the clock — which is exactly what
                    // the placeholder scaffold hid by centring its one label.
                    // IME insets stay a screen concern (task U-5 owns the
                    // terminal's keyboard behaviour); this is bars only.
                    AppNavHost(
                        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
                    )
                }
            }
        }
    }
}

/** Test tag on the placeholder body, so a journey can assert which route rendered. */
const val ROUTE_PLACEHOLDER_TAG: String = "route_placeholder"

/**
 * The app2 navigation graph. Routes come from [Destination] — no literal route
 * strings live here.
 *
 * [hostsScreen], [connectViewModel], [treeScreen], [sessionScreen],
 * [portsScreen], [filesScreen] and [viewerScreen] are seams, not feature
 * flags: the real host list, connect gate, session tree, terminal,
 * port-forward panel, file explorer and file viewer resolve their
 * ViewModels through `hiltViewModel()`, which needs a Hilt-managed Activity,
 * so a plain Robolectric `createComposeRule()` composition could not host
 * them. The parameters let a test supply the same screen / the same
 * ViewModel built by hand (over an in-memory database and a scripted
 * connection factory) and still exercise the real navigation edge — the
 * production defaults are the real ones.
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
    hostsScreen: @Composable (onOpenHost: (Long) -> Unit) -> Unit = { onOpenHost ->
        HostListRoute(onOpenHost = onOpenHost)
    },
    connectViewModel: @Composable () -> ConnectViewModel = { hiltViewModel() },
    treeScreen: @Composable (
        hostId: Long,
        onOpenSession: (String) -> Unit,
        onOpenFiles: () -> Unit,
    ) -> Unit = { _, onOpenSession, onOpenFiles ->
        SessionTreeRoute(onOpenSession = onOpenSession, onOpenFiles = onOpenFiles)
    },
    sessionScreen: @Composable (hostId: Long, sessionName: String, onBack: () -> Unit) -> Unit =
        { hostId, sessionName, onBack ->
            SessionRoute(hostId = hostId, sessionName = sessionName, onBack = onBack)
        },
    portsScreen: @Composable () -> Unit = { PortForwardRoute() },
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
                content = hostsScreen,
            )
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
            sessionScreen(hostId, name) { navController.popBackStack() }
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
            portsScreen()
        }
        composable(Destination.Settings.pattern) {
            RoutePlaceholder("Settings")
        }
        composable(Destination.Usage.pattern) {
            RoutePlaceholder("Usage")
        }
    }
}

/**
 * Scaffold stand-in for a not-yet-ported screen. Intentionally text-only —
 * building any real chrome here would be a design decision made twice, since
 * every U-task replaces this with the ui-kit primitives (docs/design-system.md).
 */
@Composable
private fun RoutePlaceholder(label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.testTag(ROUTE_PLACEHOLDER_TAG),
        )
    }
}
