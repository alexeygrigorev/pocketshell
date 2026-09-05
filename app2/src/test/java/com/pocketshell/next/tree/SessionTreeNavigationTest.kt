package com.pocketshell.next.tree

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.next.AppNavHost
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.nav.Destination
import com.pocketshell.next.ports.PORT_FORWARD_BACK_TAG
import com.pocketshell.next.ports.PortForwardScreen
import com.pocketshell.next.ports.PortForwardUiState
import com.pocketshell.next.usage.USAGE_BACK_TAG
import com.pocketshell.next.usage.USAGE_SCREEN_TAG
import com.pocketshell.next.usage.UsageScreen
import com.pocketshell.next.usage.UsageScreenState
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2532: the tree and ports seams actually pop / open Usage. Screen
 * tests prove the buttons fire callbacks; this suite proves [AppNavHost]
 * wired those callbacks to `popBackStack` / `Destination.Usage`.
 */
@RunWith(AndroidJUnit4::class)
class SessionTreeNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val stack = TestConnectStack()

    @After
    fun tearDown() {
        stack.close()
    }

    @Test
    fun `tree Back pops to Hosts`() {
        val nav = setContentWithNav()
        composeRule.runOnUiThread { nav.navigate(Destination.Tree.route(hostId = 7)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SESSION_TREE_BACK_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_TREE_BACK_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(Destination.Hosts.pattern, nav.currentBackStackEntry?.destination?.route)
        composeRule.onNodeWithText("Hosts").assertIsDisplayed()
    }

    @Test
    fun `tree Usage opens the usage panel`() {
        val nav = setContentWithNav()
        composeRule.runOnUiThread { nav.navigate(Destination.Tree.route(hostId = 7)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SESSION_TREE_USAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_TREE_USAGE_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(Destination.Usage.pattern, nav.currentBackStackEntry?.destination?.route)
        composeRule.onNodeWithTag(USAGE_SCREEN_TAG).assertIsDisplayed()
    }

    @Test
    fun `ports Back pops to the tree`() {
        val nav = setContentWithNav()
        composeRule.runOnUiThread { nav.navigate(Destination.Tree.route(hostId = 7)) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { nav.navigate(Destination.Ports.route(hostId = 7)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PORT_FORWARD_BACK_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PORT_FORWARD_BACK_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(Destination.Tree.pattern, nav.currentBackStackEntry?.destination?.route)
        composeRule.onNodeWithTag(SESSION_TREE_TAG).assertIsDisplayed()
    }

    private fun setContentWithNav(): NavHostController {
        lateinit var controller: NavHostController
        composeRule.setContent {
            controller = rememberNavController()
            PocketShellTheme {
                AppNavHost(
                    navController = controller,
                    hostsScreen = { Text("Hosts") },
                    connectViewModel = { stack.viewModel },
                    treeScreen = { _, _, _, onOpenPorts, onBack, onOpenUsage ->
                        SessionTreeScreen(
                            state = SessionTreeUiState(hostId = 7, loaded = true),
                            onRefresh = {},
                            onOpenSession = {},
                            onOpenFiles = {},
                            onOpenPorts = onOpenPorts,
                            onBack = onBack,
                            onOpenUsage = onOpenUsage,
                        )
                    },
                    portsScreen = { onBack ->
                        PortForwardScreen(
                            state = PortForwardUiState(hostId = 7, hostName = "rmthz"),
                            onSetEnabled = {},
                            onTogglePort = {},
                            onSetShowAllPorts = {},
                            onBack = onBack,
                        )
                    },
                    usageScreen = { onBack ->
                        UsageScreen(
                            state = UsageScreenState(),
                            onBack = onBack,
                            onRefresh = {},
                        )
                    },
                )
            }
        }
        composeRule.waitForIdle()
        return controller
    }
}
