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
import com.pocketshell.next.AppNavHost
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.nav.Destination
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
    fun `workspace route carries its canonical path and Back returns to the host root`() {
        val canonicalPath = "/home/alexey/git/pocketshell"
        val nav = setContent(canonicalPath)

        composeRule.runOnUiThread { nav.navigate(Destination.Workspaces.route(7)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quiet-open-workspace").performClick()
        composeRule.waitForIdle()

        assertEquals(Destination.Workspace.pattern, nav.currentBackStackEntry?.destination?.route)
        assertEquals(
            canonicalPath,
            nav.currentBackStackEntry?.arguments?.getString(Destination.ARG_WORKSPACE_PATH),
        )
        composeRule.onNodeWithText("Workspace $canonicalPath").assertIsDisplayed()

        composeRule.onNodeWithTag("quiet-back-to-workspaces").performClick()
        composeRule.waitForIdle()
        assertEquals(Destination.Workspaces.pattern, nav.currentBackStackEntry?.destination?.route)
        composeRule.onNodeWithText("Workspaces 7").assertIsDisplayed()
    }

    @Test
    fun `session opened from a workspace returns to that workspace on Back`() {
        val nav = setContent("/home/alexey/git/app")
        composeRule.runOnUiThread {
            nav.navigate(Destination.Workspace.route(7, "/home/alexey/git/app"))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("quiet-open-session").performClick()
        composeRule.waitForIdle()
        assertEquals(Destination.Session.pattern, nav.currentBackStackEntry?.destination?.route)

        composeRule.onNodeWithTag("quiet-back-to-workspace").performClick()
        composeRule.waitForIdle()
        assertEquals(Destination.Workspace.pattern, nav.currentBackStackEntry?.destination?.route)
    }

    private fun setContent(path: String): NavHostController {
        lateinit var controller: NavHostController
        composeRule.setContent {
            controller = rememberNavController()
            PocketShellTheme {
                AppNavHost(
                    navController = controller,
                    hostsScreen = { Text("Hosts") },
                    connectViewModel = { stack.viewModel },
                    workspacesScreen = { hostId, onOpenWorkspace, _, _, _, _, onBack, _ ->
                        Column {
                            Text("Workspaces $hostId")
                            Button(
                                onClick = { onOpenWorkspace(path) },
                                modifier = Modifier.testTag("quiet-open-workspace"),
                            ) { Text("Open workspace") }
                            Button(
                                onClick = onBack,
                                modifier = Modifier.testTag("quiet-back-from-workspaces"),
                            ) { Text("Back") }
                        }
                    },
                    workspaceScreen = { _, workspacePath, onOpenSession, _, _, onBack, _ ->
                        Column {
                            Text("Workspace $workspacePath")
                            Button(
                                onClick = { onOpenSession("session-one") },
                                modifier = Modifier.testTag("quiet-open-session"),
                            ) { Text("Open session") }
                            Button(
                                onClick = onBack,
                                modifier = Modifier.testTag("quiet-back-to-workspaces"),
                            ) { Text("Back to workspaces") }
                        }
                    },
                    sessionScreen = { _, _, onBack, _, _, _, _ ->
                        Column {
                            Button(
                                onClick = onBack,
                                modifier = Modifier.testTag("quiet-back-to-workspace"),
                            ) { Text("Back to workspace") }
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()
        return controller
    }
}
