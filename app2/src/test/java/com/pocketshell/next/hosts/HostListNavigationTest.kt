package com.pocketshell.next.hosts

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.AppNavHost
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.nav.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The host list end to end on the host JVM: a row stored in Room is rendered by
 * the real screen inside the real `NavHost`, and tapping it lands on the
 * `Tree(hostId)` route with the right id.
 *
 * This is the assertion that a ViewModel-only test cannot make. The two ways
 * this screen realistically breaks on device are (a) the row renders but the
 * tap goes nowhere, and (b) the tap navigates with the wrong id — both are
 * invisible to a state-flow assertion and both are caught here.
 *
 * The ViewModels are constructed explicitly rather than resolved via
 * `hiltViewModel()`; a Robolectric `createComposeRule()` composition has no
 * Hilt-managed Activity to resolve against. Everything else — the screen, the
 * graph, the route builder, the argument decoding — is production code.
 *
 * Since task U-2 the tap edge runs through the real connect gate, so this suite
 * carries the shared [TestConnectStack] with a factory that dials straight to
 * connected (no host-key question). The trust branches of that gate belong to
 * `com.pocketshell.next.connect.ConnectGateNavigationTest`.
 */
@RunWith(AndroidJUnit4::class)
class HostListNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var stack: TestConnectStack

    @Before
    fun setUp() {
        stack = TestConnectStack()
    }

    @After
    fun tearDown() {
        stack.close()
    }

    @Test
    fun `stored hosts render and a tap navigates to that host's tree`() {
        val keyId = runBlocking {
            stack.db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/id_ed25519"))
        }
        val hetznerId = runBlocking { insertHost(keyId, "hetzner", "135.181.114.209", "alexey") }
        val builderId = runBlocking { insertHost(keyId, "builder", "10.0.0.7", "root") }

        val nav = setContent()

        // Both stored rows are painted, name + `user@host`.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            // Room's first emission arrives off the composition thread; poll
            // rather than assume it beat the initial frame.
            composeRule.onAllNodesWithText("hetzner").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("hetzner").assertExists()
        composeRule.onNodeWithText("alexey@135.181.114.209").assertExists()
        composeRule.onNodeWithText("builder").assertExists()
        composeRule.onNodeWithText("root@10.0.0.7").assertExists()

        composeRule.onNodeWithTag(hostRowTag(hetznerId)).performClick()
        // The tap dials now; wait for the connect to land on the tree route
        // rather than assuming navigation happened within one frame.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Tree(hostId=$hetznerId)")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The route pattern AND the decoded argument: a graph that navigated to
        // the wrong host would still match the pattern.
        assertEquals(Destination.Tree.pattern, nav.currentBackStackEntry?.destination?.route)
        assertEquals(
            hetznerId,
            nav.currentBackStackEntry?.arguments?.getLong(Destination.ARG_HOST_ID),
        )
        composeRule.onNodeWithText("Tree(hostId=$hetznerId)").assertExists()
        // Guards against a captured-index/first-row bug: the OTHER host's id
        // must not be the one we navigated with.
        assertNotEquals(builderId, hetznerId)
    }

    @Test
    fun `an empty host table renders the empty state, not a blank screen`() {
        setContent()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("No hosts yet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("No hosts yet").assertExists()
    }

    private fun setContent(): NavHostController {
        val vm = HostListViewModel(stack.db.hostDao(), Dispatchers.Unconfined)
        lateinit var controller: NavHostController
        composeRule.setContent {
            controller = rememberNavController()
            AppNavHost(
                navController = controller,
                hostsScreen = { onOpenHost ->
                    HostListRoute(onOpenHost = onOpenHost, viewModel = vm)
                },
                connectViewModel = { stack.viewModel },
            )
        }
        composeRule.waitForIdle()
        return controller
    }

    private suspend fun insertHost(
        keyId: Long,
        name: String,
        hostname: String,
        username: String,
    ): Long = stack.db.hostDao().insert(
        HostEntity(name = name, hostname = hostname, username = username, keyId = keyId),
    )
}
