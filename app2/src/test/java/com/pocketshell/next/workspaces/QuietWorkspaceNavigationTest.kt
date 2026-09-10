package com.pocketshell.next.workspaces

import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.AppNavHost
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.nav.Destination
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2635 N1 (maintainer-approved route change), reproduce-first.
 *
 * This suite used to pin the OPPOSITE route: `Workspaces → Workspace → Session`,
 * with a workspace page in the middle whose job was to list that workspace's
 * sessions as rows to tap a second time. The maintainer's report was tap count
 * — "I don't want to have another screen" — so the level is gone and these
 * tests now pin what replaced it.
 *
 * The first test fails on the old graph in the strongest possible way: the route
 * it asserts does not exist there. The second is the reason the page could be
 * deleted at all — a workspace with nothing running still must not land on a
 * blank page, so it opens the create sheet instead.
 */
@RunWith(AndroidJUnit4::class)
class QuietWorkspaceNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val stack = TestConnectStack()

    @After
    fun tearDown() {
        stack.close()
    }

    @Test
    fun `a workspace with a session opens its terminal, with no page in between`() {
        val nav = setContent()

        composeRule.runOnUiThread { nav.navigate(Destination.Workspaces.route(7)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quiet-open-session").performClick()
        composeRule.waitForIdle()

        assertEquals(
            "a workspace tap must land on the terminal itself",
            Destination.Session.pattern,
            nav.currentBackStackEntry?.destination?.route,
        )
        assertEquals(
            CANONICAL_PATH,
            nav.currentBackStackEntry?.arguments?.getString(Destination.ARG_WORKSPACE_PATH),
        )
        composeRule.onNodeWithText("Session claude-main").assertIsDisplayed()

        // …and Back returns to the WORKSPACE LIST, because there is nothing
        // between them any more.
        composeRule.onNodeWithTag("quiet-back-from-session").performClick()
        composeRule.waitForIdle()
        assertEquals(Destination.Workspaces.pattern, nav.currentBackStackEntry?.destination?.route)
        composeRule.onNodeWithText("Workspaces 7").assertIsDisplayed()
    }

    @Test
    fun `a workspace with nothing running opens its create sheet, not a blank page`() {
        val nav = setContent()

        composeRule.runOnUiThread { nav.navigate(Destination.Workspaces.route(7)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quiet-start-session").performClick()
        composeRule.waitForIdle()

        assertEquals(
            "an empty workspace goes straight to WorkspaceStart — the create " +
                "sheet — not to a page whose only content is a button",
            Destination.WorkspaceStart.pattern,
            nav.currentBackStackEntry?.destination?.route,
        )
        assertEquals(
            CANONICAL_PATH,
            nav.currentBackStackEntry?.arguments?.getString(Destination.ARG_WORKSPACE_PATH),
        )
        composeRule.onNodeWithText("Start in $CANONICAL_PATH").assertIsDisplayed()
    }

    /** The deleted route must not come back through a compatibility shim (D22). */
    @Test
    fun `the intermediate workspace route no longer exists in the graph`() {
        val routes = Destination.all.map { it.pattern }

        assertEquals(
            "#2635 N1 hard-cut `workspace/{hostId}`; only the create-sheet " +
                "route survives",
            emptyList<String>(),
            routes.filter { it.startsWith("workspace/") },
        )
        assertEquals(
            1,
            routes.count { it.startsWith("workspace-start/") },
        )
    }

    private fun setContent(): NavHostController {
        lateinit var controller: NavHostController
        composeRule.setContent {
            controller = rememberNavController()
            PocketShellTheme {
                AppNavHost(
                    navController = controller,
                    hostsScreen = { Text("Hosts") },
                    connectViewModel = { stack.viewModel },
                    workspacesScreen = { hostId, onOpenSession, onStartSessionAtPath, _, _, _, onBack, _, _ ->
                        Column {
                            Text("Workspaces $hostId")
                            // What a workspace row does when it HAS a session.
                            Button(
                                onClick = {
                                    onOpenSession(sessionRow())
                                },
                                modifier = Modifier.testTag("quiet-open-session"),
                            ) { Text("Open session") }
                            // …and what it does when the workspace is EMPTY.
                            Button(
                                onClick = { onStartSessionAtPath(CANONICAL_PATH) },
                                modifier = Modifier.testTag("quiet-start-session"),
                            ) { Text("Start session") }
                            Button(
                                onClick = onBack,
                                modifier = Modifier.testTag("quiet-back-from-workspaces"),
                            ) { Text("Back") }
                        }
                    },
                    workspaceStartScreen = { _, workspacePath, _, _ ->
                        Text("Start in $workspacePath")
                    },
                    sessionScreen = { _, sessionName, _, actions ->
                        Column {
                            Text("Session $sessionName")
                            Button(
                                onClick = actions.onBack,
                                modifier = Modifier.testTag("quiet-back-from-session"),
                            ) { Text("Back from session") }
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()
        return controller
    }

    private fun sessionRow(): SessionRow = SessionRow(
        name = "claude-main",
        id = null,
        workspace = CANONICAL_PATH,
        tag = null,
        engine = null,
        profile = null,
        agent = null,
        agentState = null,
        agentStateSource = null,
        attached = true,
        createdEpoch = null,
        activityEpoch = null,
    )

    private companion object {
        const val CANONICAL_PATH = "/home/alexey/git/pocketshell"
    }
}
