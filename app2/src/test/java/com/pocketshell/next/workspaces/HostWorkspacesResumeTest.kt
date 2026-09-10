package com.pocketshell.next.workspaces

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.HostCliClient
import com.pocketshell.core.transport.ExecResult
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.hostcli.asRemoteExec
import com.pocketshell.next.nav.Destination
import com.pocketshell.next.terminal.LastSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2632, acceptance criterion 2: "opening a previously-used session
 * restores its last-viewed tab/window rather than a hardcoded default".
 *
 * The resume decision belongs to this ViewModel because it is the one place
 * that holds BOTH the device's memory and the host's live session listing —
 * and the rule is that the listing has the last word. These tests drive the
 * real ViewModel over the real store against a scripted host, because the
 * failure modes worth catching are all interaction ones: resuming into a dead
 * session, resuming when the user did not ask, and (worst) resuming AGAIN the
 * moment the user backs out, which would trap them in one terminal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HostWorkspacesResumeTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var stack: TestConnectStack
    private lateinit var lastSessions: LastSessionStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        stack = TestConnectStack()
        lastSessions = LastSessionStore(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        stack.close()
    }

    @Test
    fun `an armed visit resumes the remembered session once the host confirms it`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            lastSessions.record(hostId, "pocketshell:review", "/home/testuser/git/pocketshell")
            script(sessions = twoSessions())

            val viewModel = viewModel(hostId)
            viewModel.armResume()
            viewModel.refresh()
            advanceUntilIdle()

            val resumed = viewModel.state.value.resumeSession
            assertEquals("pocketshell:review", resumed?.name)
            // The workspace path comes off the HOST's row, so navigation lands
            // in the workspace the session is in TODAY.
            assertEquals("/home/testuser/git/pocketshell", resumed?.workspace)
        }

    @Test
    fun `arming after the listing landed still resumes`() = runTest(dispatcher) {
        // The screen's arm and its ON_START refresh come from two independent
        // effects, so neither order may be load-bearing.
        val hostId = stack.seedHost()
        lastSessions.record(hostId, "pocketshell:review", null)
        script(sessions = twoSessions())

        val viewModel = viewModel(hostId)
        viewModel.refresh()
        advanceUntilIdle()
        assertNull(viewModel.state.value.resumeSession)

        viewModel.armResume()
        advanceUntilIdle()

        assertEquals("pocketshell:review", viewModel.state.value.resumeSession?.name)
    }

    @Test
    fun `a visit that was not armed never resumes`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        lastSessions.record(hostId, "pocketshell:review", null)
        script(sessions = twoSessions())

        val viewModel = viewModel(hostId)
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.state.value.resumeSession)
    }

    /**
     * The overnight case. A session that is gone from the host must leave the
     * user on the workspace list, not on a terminal that cannot attach.
     */
    @Test
    fun `a remembered session the host no longer runs does not resume`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        lastSessions.record(hostId, "pocketshell:gone", null)
        script(sessions = twoSessions())

        val viewModel = viewModel(hostId)
        viewModel.armResume()
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.state.value.resumeSession)
    }

    @Test
    fun `a host this device has never opened does not resume`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        script(sessions = twoSessions())

        val viewModel = viewModel(hostId)
        viewModel.armResume()
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.state.value.resumeSession)
    }

    /**
     * The trap this whole design exists to avoid: pressing Back out of the
     * resumed terminal returns to THIS ViewModel (it is scoped to the
     * navigation entry) and fires another `ON_START` refresh. If that refresh
     * re-armed the resume, the user could never reach the workspace list.
     */
    @Test
    fun `backing out of the resumed session does not bounce back into it`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        lastSessions.record(hostId, "pocketshell:review", null)
        script(sessions = twoSessions())

        val viewModel = viewModel(hostId)
        viewModel.armResume()
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals("pocketshell:review", viewModel.consumeResumeSession()?.name)
        assertNull(viewModel.state.value.resumeSession)

        // Back from the session screen: same entry, same ViewModel, another
        // ON_START refresh — and, for good measure, another arm attempt.
        viewModel.armResume()
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.state.value.resumeSession)
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

    private fun script(sessions: String) {
        stack.factory.script = { connection ->
            connection.onExecMatching("workspace and sessions listing", once = false, { true }) { command ->
                when {
                    command.startsWith("pocketshell workspaces list") ->
                        ExecResult(0, WORKSPACES, "", false)
                    command.startsWith("pocketshell sessions list") ->
                        ExecResult(0, sessions, "", false)
                    else -> ExecResult(0, "", "", false)
                }
            }
        }
    }

    private fun twoSessions(): String = """
        {"schema":3,"sessions":[
          {"name":"pocketshell:main","workspace":"/home/testuser/git/pocketshell","engine":"shell","attached":false},
          {"name":"pocketshell:review","workspace":"/home/testuser/git/pocketshell","engine":"shell","attached":false}
        ],"errors":[]}
    """.trimIndent()

    private companion object {
        const val WORKSPACES =
            """{"schema":1,"workspaces":[{"path":"/home/testuser/git/pocketshell","display_path":"~/git/pocketshell"}]}"""
    }
}
