package com.pocketshell.next

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.navigation.navArgument
import com.pocketshell.next.nav.Destination
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
                    AppNavHost()
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
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Destination.start.pattern,
        modifier = modifier,
    ) {
        composable(Destination.Hosts.pattern) {
            RoutePlaceholder("Hosts")
        }
        composable(
            route = Destination.Tree.pattern,
            arguments = listOf(navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType }),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID)
            RoutePlaceholder("Tree(hostId=$hostId)")
        }
        composable(
            route = Destination.Session.pattern,
            arguments = listOf(
                navArgument(Destination.ARG_HOST_ID) { type = NavType.LongType },
                navArgument(Destination.ARG_SESSION_NAME) { type = NavType.StringType },
            ),
        ) { entry ->
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID)
            val name = entry.arguments?.getString(Destination.ARG_SESSION_NAME)
            RoutePlaceholder("Session(hostId=$hostId, name=$name)")
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
            val hostId = entry.arguments?.getLong(Destination.ARG_HOST_ID)
            val path = entry.arguments?.getString(Destination.ARG_PATH)
            RoutePlaceholder("Files(hostId=$hostId, path=$path)")
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
