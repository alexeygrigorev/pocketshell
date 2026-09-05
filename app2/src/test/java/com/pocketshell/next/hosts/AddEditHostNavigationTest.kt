package com.pocketshell.next.hosts

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.AppNavHost
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.nav.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Add and edit a host through the REAL screens inside the REAL navigation
 * graph, on the host JVM.
 *
 * This is the acceptance a ViewModel test cannot make: that a fresh install
 * with an empty host table has a reachable way to add a host, that filling the
 * form and saving lands back on the list, and that the row the list paints is
 * the row the form wrote. Before task P-6 the answer to the first of those was
 * "no" — U-1's list was read-only, so the app could not be used on a fresh
 * install at all.
 *
 * The ViewModels are constructed explicitly because a Robolectric
 * `createComposeRule()` composition has no Hilt-managed Activity for
 * `hiltViewModel()` to resolve against; the screens, the graph, the route
 * builders and the argument decoding are all production code.
 */
@RunWith(AndroidJUnit4::class)
// Robolectric's default window is 320x470px, which is shorter than the host
// form. A button laid out below that fold is silently un-clickable — the tap is
// dropped, the test sees no error, and the assertion fails somewhere unrelated.
// Pinning a realistic phone viewport (and scrolling to the CTA below) keeps this
// suite testing the screen rather than the harness.
@Config(qualifiers = "w411dp-h891dp")
class AddEditHostNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var stack: TestConnectStack
    private var keyId: Long = 0

    @Before
    fun setUp() {
        stack = TestConnectStack()
        keyId = runBlocking {
            stack.db.sshKeyDao().insert(SshKeyEntity(name = "hetzner-key", privateKeyPath = "/tmp/k"))
        }
    }

    @After
    fun tearDown() {
        stack.close()
    }

    @Test
    fun `a fresh install can add a host from the empty state and see it listed`() {
        val nav = setContent()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("No hosts yet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Add host").performClick()
        composeRule.waitForIdle()
        assertEquals(Destination.HostForm.pattern, nav.currentBackStackEntry?.destination?.route)

        typeHost(name = "hetzner", hostname = "135.181.114.209", port = "2222", username = "alexey")
        chooseKey()
        save()

        // Saving pops back to the list, and the row Room emitted is painted.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("alexey@135.181.114.209").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(Destination.Hosts.pattern, nav.currentBackStackEntry?.destination?.route)
        composeRule.onNodeWithText("hetzner").assertExists()

        val stored = runBlocking { stack.db.hostDao().getById(1) }
        assertEquals(2222, stored?.port)
        assertEquals(keyId, stored?.keyId)
    }

    /**
     * The user-visible shape of the audit-F1 bug, end to end: edit a host, go
     * back, choose Add, fill in a second host, save. The list must end up with
     * two rows — not one row wearing the second host's details.
     *
     * The form ViewModel is shared across both route entries here on purpose
     * (see [setContent]); that is the arrangement the bug needs, and this test
     * goes red under the old `bind(hostId) { if (hostId == null) return }`.
     */
    @Test
    fun `editing a host then adding another leaves both rows`() {
        val alpha = runBlocking {
            stack.db.hostDao().insert(
                HostEntity(name = "alpha", hostname = "10.0.0.1", username = "alexey", keyId = keyId),
            )
        }
        val nav = setContent()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("alpha").fetchSemanticsNodes().isNotEmpty()
        }

        // Edit alpha through the row's own menu.
        composeRule.onNodeWithTag(hostRowMenuTag(alpha)).performClick()
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOST_FORM_NAME_TAG).performTextClearance()
        composeRule.onNodeWithTag(HOST_FORM_NAME_TAG).performTextInput("alpha-renamed")
        save()
        // Wait for the LIST, not for the text: "alpha-renamed" is also the value
        // sitting in the form's own name field, so matching on it would pass
        // while still on the form and make every later step nonsense.
        awaitHostList()

        // Now add a second host.
        composeRule.onNodeWithTag(HOST_LIST_ADD_TAG).performClick()
        composeRule.waitForIdle()
        typeHost(name = "beta", hostname = "10.0.0.2", port = "22", username = "root")
        chooseKey()
        save()

        awaitHostList()
        assertEquals(Destination.Hosts.pattern, nav.currentBackStackEntry?.destination?.route)

        // Both rows survive, and alpha kept its own endpoint.
        composeRule.onNodeWithText("alpha-renamed").assertExists()
        composeRule.onNodeWithText("alexey@10.0.0.1").assertExists()
        composeRule.onNodeWithText("root@10.0.0.2").assertExists()
        val rows = runBlocking { stack.db.hostDao().getById(alpha) }
        assertEquals("alpha-renamed", rows?.name)
        assertEquals("10.0.0.1", rows?.hostname)
    }

    @Test
    fun `an invalid port keeps the user on the form with a message`() {
        setContent()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("No hosts yet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Add host").performClick()
        composeRule.waitForIdle()

        typeHost(name = "h", hostname = "10.0.0.1", port = "22x", username = "u")
        chooseKey()
        save()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Enter a port between 1 and 65535").assertExists()
        assertEquals(emptyList<HostEntity>(), runBlocking { stack.db.hostDao().getAll().first() })
    }

    private fun typeHost(name: String, hostname: String, port: String, username: String) {
        composeRule.onNodeWithTag(HOST_FORM_NAME_TAG).performTextInput(name)
        composeRule.onNodeWithTag(HOST_FORM_HOSTNAME_TAG).performTextInput(hostname)
        composeRule.onNodeWithTag(HOST_FORM_PORT_TAG).performTextClearance()
        composeRule.onNodeWithTag(HOST_FORM_PORT_TAG).performTextInput(port)
        composeRule.onNodeWithTag(HOST_FORM_USERNAME_TAG).performTextInput(username)
    }

    /** Waits until the host list is the screen on top. */
    private fun awaitHostList() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(HOST_LIST_ADD_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Scrolls the form's CTA into view and taps it. */
    private fun save() {
        composeRule.onNodeWithTag(HOST_FORM_SAVE_TAG).performScrollTo().performClick()
    }

    /** Opens the key menu and picks the one seeded key. */
    private fun chooseKey() {
        composeRule.onNodeWithText("Choose").performClick()
        composeRule.onNodeWithText("hetzner-key").performClick()
    }

    /**
     * ONE form ViewModel, shared by every entry to the form route.
     *
     * Production is safer than this: navigation-compose scopes `hiltViewModel()`
     * to the back-stack entry, so Add and Edit would get separate instances
     * anyway. Sharing one instance here deliberately recreates the
     * Activity-scoped store the old client used, which is the only arrangement
     * in which the audit-F1 retained-identity bug can appear at all. Passing
     * this journey with a shared instance means it passes with the stricter
     * production scoping too.
     */
    private fun setContent(): NavHostController {
        val hostList = HostListViewModel(stack.db.hostDao(), Dispatchers.Unconfined)
        val formViewModel = AddEditHostViewModel(
            stack.db.hostDao(),
            stack.db.sshKeyDao(),
            SavedStateHandle(),
        )
        lateinit var controller: NavHostController
        composeRule.setContent {
            controller = rememberNavController()
            AppNavHost(
                navController = controller,
                hostsScreen = { actions ->
                    HostListRoute(
                        onOpenHost = actions.onOpenHost,
                        onAddHost = actions.onAddHost,
                        onEditHost = actions.onEditHost,
                        onScanQr = actions.onScanQr,
                        onOpenSettings = actions.onOpenSettings,
                        viewModel = hostList,
                    )
                },
                connectViewModel = { stack.viewModel },
                treeScreen = { hostId, _, _, _ -> Text("Tree(hostId=$hostId)") },
                hostFormScreen = { hostId, onDone, onAddKey ->
                    AddEditHostRoute(hostId = hostId, onDone = onDone, onAddKey = onAddKey, viewModel = formViewModel)
                },
            )
        }
        composeRule.waitForIdle()
        return controller
    }
}
