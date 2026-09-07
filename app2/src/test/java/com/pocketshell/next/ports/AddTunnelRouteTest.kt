package com.pocketshell.next.ports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.PortRemappingEntity
import com.pocketshell.next.nav.Destination
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Add Tunnel route over the real Room-backed forwarding ViewModel.
 *
 * The leaf screen tests pin rendering, but only this route test exercises the
 * form state/effect that asks the controller whether a local bind is free.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AddTunnelRouteTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var stack: TestForwardingStack
    private lateinit var viewModel: PortForwardViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        stack = TestForwardingStack(Dispatchers.Unconfined)
        val hostId = stack.seedHost()
        runBlocking {
            stack.db.portRemappingDao().insert(
                PortRemappingEntity(
                    hostId = hostId,
                    remotePort = 22,
                    localPort = 7_432,
                    name = "Fixture HTTP",
                ),
            )
        }
        viewModel = PortForwardViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Destination.ARG_HOST_ID to hostId)),
            hostDao = stack.db.hostDao(),
            remappingDao = stack.db.portRemappingDao(),
            controller = stack.controller,
            showAllPortsStore = stack.showAllPortsStore,
        )
    }

    @After
    fun tearDown() {
        stack.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `remote-only edit reruns local collision validation`() {
        composeRule.setContent {
            PocketShellTheme {
                AddTunnelRoute(
                    initialRemotePort = 5_173,
                    onDone = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.onNodeWithTag(ADD_TUNNEL_LOCAL_TAG).performTextReplacement("7432")
        awaitCollision()

        // The local bind did not change. The route still has to rerun the
        // validation effect after this remote-only edit, otherwise the reset
        // collisionChecked flag leaves Start tunnel disabled forever.
        composeRule.onNodeWithTag(ADD_TUNNEL_REMOTE_TAG).performTextReplacement("5174")
        awaitCollision()
        composeRule.onNodeWithTag(ADD_TUNNEL_SUBMIT_TAG).assertIsNotEnabled()
    }

    private fun awaitCollision() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(COLLISION_TEXT).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(COLLISION_TEXT).assertIsDisplayed()
    }

    private companion object {
        const val COLLISION_TEXT = "Local port 7432 is already used by Fixture HTTP"
    }
}
