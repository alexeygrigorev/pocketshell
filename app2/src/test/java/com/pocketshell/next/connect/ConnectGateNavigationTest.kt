package com.pocketshell.next.connect

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.next.AppNavHost
import com.pocketshell.next.hosts.HostListRoute
import com.pocketshell.next.hosts.HostListViewModel
import com.pocketshell.next.hosts.hostRowTag
import com.pocketshell.next.nav.Destination
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole U-2 edge as a real composition on the host JVM: the real host list
 * inside the real `NavHost`, the real [ConnectGate], the real
 * [ConnectionsRegistry] over a real Room database — with only the sshj dial
 * swapped for a scripted factory.
 *
 * This is the companion to the emulator journey `J01ConnectAndTrustJourney`:
 * that one proves the stack works against a real sshd on a real device, this
 * one runs in seconds on every unit-test lane so a regression in the wiring
 * (tap does not dial, trust does not retry, reject navigates anyway) fails
 * without needing an emulator.
 *
 * Every assertion is on the RENDERED tree, never on ViewModel state (D29): a
 * green state flow over a screen stuck on the host list is the exact failure
 * this project keeps re-learning.
 */
@RunWith(AndroidJUnit4::class)
class ConnectGateNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var stack: TestConnectStack

    @After
    fun tearDown() {
        if (::stack.isInitialized) stack.close()
    }

    @Test
    fun `tapping a host with an unknown key raises the trust sheet, and trusting lands on the tree`() {
        stack = TestConnectStack(presentedFingerprint = PRESENTED)
        val hostId = stack.seedHost(name = "fixture")
        val nav = setContent()

        tapHost(hostId)

        // The prompt is on screen with the fingerprint the server presented —
        // and the tap did NOT navigate.
        awaitTag(TRUST_SHEET_FINGERPRINT_TAG)
        composeRule.onNodeWithText(PRESENTED).assertExists()
        assertEquals(Destination.Hosts.pattern, nav.currentBackStackEntry?.destination?.route)

        composeRule.onNodeWithTag(TRUST_SHEET_TRUST_TAG).performClick()

        awaitText("Tree(hostId=$hostId)")
        assertEquals(Destination.Tree.pattern, nav.currentBackStackEntry?.destination?.route)
        assertEquals(
            hostId,
            nav.currentBackStackEntry?.arguments?.getLong(Destination.ARG_HOST_ID),
        )
        assertEquals(PRESENTED, stack.storedFingerprint(hostId))
    }

    @Test
    fun `rejecting the trust prompt stores no key and stays on the host list`() {
        stack = TestConnectStack(presentedFingerprint = PRESENTED)
        val hostId = stack.seedHost(name = "fixture")
        val nav = setContent()

        tapHost(hostId)
        awaitTag(TRUST_SHEET_REJECT_TAG)

        composeRule.onNodeWithTag(TRUST_SHEET_REJECT_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(TRUST_SHEET_FINGERPRINT_TAG)
                .fetchSemanticsNodes().isEmpty()
        }
        assertEquals(Destination.Hosts.pattern, nav.currentBackStackEntry?.destination?.route)
        composeRule.onNodeWithText("Tree(hostId=$hostId)").assertDoesNotExist()
        assertNull(stack.storedFingerprint(hostId))
    }

    @Test
    fun `a failed dial shows a retry banner on the host list, not a dead end`() {
        stack = TestConnectStack()
        val hostId = stack.seedHost(name = "fixture")
        stack.factory.failWith = "Connect to testuser@10.0.2.2:2222 failed: refused"
        val nav = setContent()

        tapHost(hostId)

        awaitTag(CONNECT_ERROR_BANNER_TAG)
        // The list is still there behind the banner.
        composeRule.onNodeWithTag(hostRowTag(hostId)).assertExists()
        assertEquals(Destination.Hosts.pattern, nav.currentBackStackEntry?.destination?.route)

        // Retry, this time against a healthy fixture, actually reconnects.
        stack.factory.failWith = null
        composeRule.onNodeWithTag(CONNECT_ERROR_RETRY_TAG).performClick()

        awaitText("Tree(hostId=$hostId)")
    }

    private fun tapHost(hostId: Long) {
        awaitTag(hostRowTag(hostId))
        composeRule.onNodeWithTag(hostRowTag(hostId)).performClick()
        composeRule.waitForIdle()
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun setContent(): NavHostController {
        val hostListViewModel = HostListViewModel(stack.db.hostDao(), Dispatchers.Unconfined)
        lateinit var controller: NavHostController
        composeRule.setContent {
            controller = rememberNavController()
            AppNavHost(
                navController = controller,
                // Both ViewModels are constructed explicitly: a Robolectric
                // `createComposeRule()` composition has no Hilt-managed
                // Activity to resolve `hiltViewModel()` against. Everything
                // else — the gate, the sheet, the registry, the trust store,
                // the screens, the graph — is production code.
                hostsScreen = { actions ->
                    HostListRoute(
                        onOpenHost = actions.onOpenHost,
                        onAddHost = actions.onAddHost,
                        onEditHost = actions.onEditHost,
                        onScanQr = actions.onScanQr,
                        onOpenSettings = actions.onOpenSettings,
                        viewModel = hostListViewModel,
                    )
                },
                connectViewModel = { stack.viewModel },
                // Same reason: the U-3 session tree resolves its ViewModel
                // through `hiltViewModel()`. This suite is about the connect
                // gate's navigation edge, so the destination is a stand-in that
                // echoes the argument the route delivered.
                treeScreen = { hostId, _, _, _ -> Text("Tree(hostId=$hostId)") },
            )
        }
        composeRule.waitForIdle()
        return controller
    }

    private companion object {
        const val PRESENTED = "SHA256:presented-by-the-fixture"
    }
}
