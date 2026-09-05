package com.pocketshell.next.tree

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.HostCliClient
import com.pocketshell.core.transport.ExecResult
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.hostcli.asRemoteExec
import com.pocketshell.next.nav.Destination
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The route's navigation edge: a created session OPENS (task U-6).
 *
 * `SessionTreeViewModelTest` proves the ViewModel raises the one-shot open
 * request, and journey J04 proves the whole thing lands on the session screen
 * on a device. What only this test covers is the wiring between them — that
 * [SessionTreeRoute]'s effect actually consumes the request and calls
 * `onOpenSession` with the HOST's session name. A route that raised the signal
 * and never navigated would pass both of the other suites.
 *
 * Real ViewModel over the real connect stack (only the sshj dial is scripted),
 * driven through the real composition.
 */
@RunWith(AndroidJUnit4::class)
class SessionTreeRouteTest {

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
    fun `a created session is opened once, by the name the host answered with`() {
        val hostId = stack.seedHost()
        stack.factory.script = { connection ->
            connection.onExecPrefix(
                "pocketshell sessions list",
                ExecResult(0, EMPTY_LISTING, "", false),
            )
            connection.onExecPrefix(
                "pocketshell sessions create",
                // The host answers with ITS OWN name for what it made.
                ExecResult(
                    0,
                    """{"schema":2,"name":"reviews","manager":"tmux","id":null,"created":true}""",
                    "",
                    false,
                ),
            )
        }
        val viewModel = SessionTreeViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Destination.ARG_HOST_ID to hostId)),
            registry = stack.registry,
            clients = HostCliClientFactory { connection -> HostCliClient(connection.asRemoteExec()) },
            projectRootDao = stack.db.projectRootDao(),
        )
        val opened = mutableListOf<String>()

        composeRule.setContent {
            PocketShellTheme {
                SessionTreeRoute(
                    onOpenSession = { opened += it },
                    onOpenFiles = {},
                    onOpenPorts = {},
                    onBack = {},
                    onOpenUsage = {},
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals("nothing opens before a create", emptyList<String>(), opened)

        composeRule.runOnIdle {
            viewModel.createSession(CreateSessionRequest(name = "reviews", cwd = "/srv/reviews"))
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { opened.isNotEmpty() }
        composeRule.waitForIdle()

        assertEquals(listOf("reviews"), opened)
        // Consumed before navigating, so returning to the tree cannot re-open it.
        assertNull(viewModel.state.value.create.openRequest)
    }

    private companion object {
        const val EMPTY_LISTING =
            """{"schema":2,"managers":["tmux"],"sessions":[],"errors":[]}"""
    }
}
