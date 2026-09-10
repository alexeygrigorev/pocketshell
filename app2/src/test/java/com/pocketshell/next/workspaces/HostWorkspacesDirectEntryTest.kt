package com.pocketshell.next.workspaces

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.HostCliClient
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.transport.ExecResult
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.hostcli.asRemoteExec
import com.pocketshell.next.nav.Destination
import com.pocketshell.next.terminal.LastSessionStore
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2632, maintainer follow-up (2026-09-10): "I don't want to have
 * another screen — I want to jump to the last session I opened and I want to
 * be able to switch between sessions".
 *
 * The gesture under test is the one the maintainer actually performs: a tap on
 * a WORKSPACE row. Before this it pushed the workspace/session-picker screen
 * and cost a second tap to reach a terminal. These drive the real
 * [HostWorkspacesRoute] over a real ViewModel and a scripted host, and assert
 * on which navigation callback the tap fired — because "we render a row that
 * navigates somewhere" is not the property; "it navigates to the terminal
 * rather than to another list" is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HostWorkspacesDirectEntryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var stack: TestConnectStack
    private lateinit var lastSessions: LastSessionStore
    private val openedSessions = mutableListOf<SessionRow>()
    private val openedWorkspaces = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        stack = TestConnectStack()
        lastSessions = LastSessionStore(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        stack.close()
    }

    @Test
    fun `tapping a workspace opens its remembered session, not a session list`() {
        val hostId = seedHost()
        lastSessions.record(hostId, "pocketshell:review", WORKSPACE)

        tapWorkspace(hostId)

        assertEquals(listOf("pocketshell:review"), openedSessions.map { it.name })
        assertEquals(emptyList<String>(), openedWorkspaces)
    }

    /**
     * A workspace this device has no memory of still must not stop at a
     * picker: the most recently active session is the best answer to "show me
     * this project", and it is a far better answer than another list.
     */
    @Test
    fun `an unremembered workspace opens its most recently active session`() {
        val hostId = seedHost()

        tapWorkspace(hostId)

        assertEquals(listOf("pocketshell:review"), openedSessions.map { it.name })
        assertEquals(emptyList<String>(), openedWorkspaces)
    }

    @Test
    fun `a remembered session that has since ended falls back to a live one`() {
        val hostId = seedHost()
        lastSessions.record(hostId, "pocketshell:gone", WORKSPACE)

        tapWorkspace(hostId)

        assertEquals(listOf("pocketshell:review"), openedSessions.map { it.name })
        assertEquals(emptyList<String>(), openedWorkspaces)
    }

    /**
     * The one case that still needs the workspace screen: nothing is running
     * there, so there is no terminal to jump to and the user needs the
     * empty-state / start-a-session surface.
     */
    @Test
    fun `a workspace with no sessions still opens the workspace screen`() {
        val hostId = seedHost(sessions = EMPTY_SESSIONS)

        tapWorkspace(hostId)

        assertEquals(emptyList<String>(), openedSessions.map { it.name })
        assertEquals(listOf(WORKSPACE), openedWorkspaces)
    }

    @Test
    fun `the opened session carries its workspace, so the tab strip is scoped to it`() {
        val hostId = seedHost()

        tapWorkspace(hostId)

        // The session route's workspace argument is what makes
        // `SessionSwitcherViewModel` list this workspace's siblings — i.e.
        // what puts the right tabs on the terminal the tap landed on.
        assertEquals(WORKSPACE, openedSessions.single().workspace)
    }

    private fun tapWorkspace(hostId: Long) {
        val viewModel = viewModel(hostId)
        composeRule.setContent {
            PocketShellTheme {
                HostWorkspacesRoute(
                    onOpenWorkspace = { openedWorkspaces += it },
                    onOpenSession = { openedSessions += it },
                    onOpenFiles = {},
                    onOpenPorts = {},
                    onBack = {},
                    onOpenUsage = {},
                    viewModel = viewModel,
                )
            }
        }
        viewModel.refresh()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(workspaceRowTag(WORKSPACE))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(workspaceRowTag(WORKSPACE)).performClick()
        composeRule.waitForIdle()
    }

    private fun seedHost(sessions: String = TWO_SESSIONS): Long {
        val hostId = stack.seedHost()
        stack.factory.script = { connection ->
            connection.onExecMatching("listing", once = false, { true }) { command ->
                when {
                    command.startsWith("pocketshell workspaces list") ->
                        ExecResult(0, WORKSPACES, "", false)
                    command.startsWith("pocketshell sessions list") ->
                        ExecResult(0, sessions, "", false)
                    else -> ExecResult(0, "", "", false)
                }
            }
        }
        return hostId
    }

    private fun viewModel(hostId: Long) = HostWorkspacesViewModel(
        savedStateHandle = SavedStateHandle(mapOf(Destination.ARG_HOST_ID to hostId)),
        registry = stack.registry,
        clients = HostCliClientFactory { connection -> HostCliClient(connection.asRemoteExec()) },
        hostDao = stack.db.hostDao(),
        projectRootDao = stack.db.projectRootDao(),
        workspaceOrderStore = WorkspaceOrderStore(ApplicationProvider.getApplicationContext()),
        lastSessionStore = lastSessions,
    )

    private companion object {
        const val WORKSPACE = "/home/testuser/git/pocketshell"
        const val WORKSPACES =
            """{"schema":1,"workspaces":[{"path":"$WORKSPACE","display_path":"~/git/pocketshell"}]}"""

        /** `review` is the more recently active of the two. */
        const val TWO_SESSIONS = """
            {"schema":3,"sessions":[
              {"name":"pocketshell:main","workspace":"$WORKSPACE","engine":"shell","attached":false,"activity_epoch":100},
              {"name":"pocketshell:review","workspace":"$WORKSPACE","engine":"shell","attached":false,"activity_epoch":900}
            ],"errors":[]}
        """
        const val EMPTY_SESSIONS = """{"schema":3,"sessions":[],"errors":[]}"""
    }
}
