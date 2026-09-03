package com.pocketshell.next.tree

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.AgentState
import com.pocketshell.core.hostapi.AgentStateSource
import com.pocketshell.core.hostapi.Backend
import com.pocketshell.core.hostapi.ExecOutcome
import com.pocketshell.core.hostapi.HostCliClient
import com.pocketshell.core.transport.ExecResult
import com.pocketshell.core.transport.FakeHostConnection
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.hostcli.asRemoteExec
import com.pocketshell.next.nav.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SessionTreeViewModel] over the REAL connect stack — a real Room database, the
 * real [com.pocketshell.next.connect.ConnectionsRegistry], the real
 * [HostCliClient] and the real schema-2 parser — with only the sshj dial swapped
 * for `core-transport`'s scripted [FakeHostConnection].
 *
 * Nothing between the ViewModel and the bytes on the wire is stubbed, which is
 * what makes the failure assertions meaningful: "exit 127" arrives here as the
 * host CLI's own text because [HostCliClient] produced it, not because the test
 * asserted the string it also wrote.
 *
 * Robolectric (`AndroidJUnit4`) is needed only for the in-memory Room database
 * that [TestConnectStack] builds; the ViewModel itself touches no Android API
 * beyond [SavedStateHandle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SessionTreeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var stack: TestConnectStack

    @Before
    fun setUp() {
        // viewModelScope is hard-wired to Dispatchers.Main.
        Dispatchers.setMain(dispatcher)
        stack = TestConnectStack()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        stack.close()
    }

    @Test
    fun `a healthy listing groups every session by workspace`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerSessions(HEALTHY_LISTING)
        val viewModel = viewModel(hostId)

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("a successful listing must mark the screen loaded", state.loaded)
        assertNull(state.failure)
        assertEquals(emptyList<String>(), state.errors.map { it.manager })
        assertFalse(state.loading)
        assertFalse(state.refreshing)

        // Two named workspaces plus the "other" bucket, most-recent first.
        assertEquals(
            listOf("/home/testuser/git/pocketshell", "/home/testuser/git/aplexer", OTHER_WORKSPACE_LABEL),
            state.groups.map { it.label },
        )
        assertEquals(
            listOf("claude-main", "codex"),
            state.groups[0].rows.map { it.name },
        )
        assertEquals(listOf("aplexer-follow:yolo"), state.groups[1].rows.map { it.name })
        assertEquals(listOf("opencode-lab"), state.groups[2].rows.map { it.name })
        assertEquals(4, state.sessionCount)

        // The parsed detail the screen renders actually survived the round trip.
        val claude = state.groups[0].rows.first()
        assertEquals(Backend.TMUX, claude.backend)
        assertEquals(AgentState.WORKING, claude.agentState)
        assertEquals(AgentStateSource.REPORTED, claude.agentStateSource)
        assertTrue(claude.attached)
        val aplexer = state.groups[1].rows.single()
        assertEquals(Backend.APLEXER, aplexer.backend)
        assertEquals("codex", aplexer.engine)
        assertEquals("yolo", aplexer.tag)

        // And the command it ran is the host CLI's, verbatim.
        assertEquals(
            listOf("pocketshell sessions list --json"),
            connection().executedCommands,
        )
    }

    @Test
    fun `an UNKNOWN manager row is still rendered rather than dropped`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerSessions(UNKNOWN_MANAGER_LISTING)
        val viewModel = viewModel(hostId)

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.failure)
        assertEquals(2, state.sessionCount)
        assertTrue(
            "a manager this build does not know must survive as UNKNOWN",
            state.groups.flatMap { it.rows }.any { it.backend == Backend.UNKNOWN },
        )
    }

    @Test
    fun `a backend that failed to enumerate surfaces as a partial listing, not an empty one`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            answerSessions(PARTIAL_LISTING)
            val viewModel = viewModel(hostId)

            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.state.value
            // This is the #2426 contract: the rows that DID arrive are shown,
            // AND the screen knows the list is short.
            assertEquals(listOf("aplexer"), state.errors.map { it.manager })
            assertTrue(state.errors.single().message.contains("exit 127"))
            assertEquals(1, state.sessionCount)
            assertTrue(state.loaded)
            assertNull("a partial listing is not a hard failure", state.failure)
            assertFalse(
                "partial must never read as empty-and-healthy",
                state.isEmptyAndHealthy,
            )
        }

    @Test
    fun `an empty healthy listing is distinguishable from a broken one`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerSessions("""{"schema":2,"managers":["tmux"],"sessions":[],"errors":[]}""")
        val viewModel = viewModel(hostId)

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.isEmptyAndHealthy)
        assertNull(state.failure)
        assertEquals(emptyList<WorkspaceGroup>(), state.groups)
    }

    @Test
    fun `a host CLI that is not installed surfaces the hosts own words as a failure`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            stack.factory.script = { connection ->
                connection.onExecPrefix(
                    "pocketshell sessions list",
                    ExecResult(
                        exitCode = 127,
                        stdout = "",
                        stderr = "sh: pocketshell: not found",
                        timedOut = false,
                    ),
                )
            }
            val viewModel = viewModel(hostId)

            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.state.value
            val failure = requireNotNull(state.failure) { "a non-zero exit must fail the screen" }
            assertTrue(failure, failure.contains("exit 127"))
            assertTrue(failure, failure.contains("pocketshell: not found"))
            assertFalse("a broken host must not read as empty-and-healthy", state.isEmptyAndHealthy)
            assertFalse(state.loaded)
            assertFalse(state.loading)
        }

    @Test
    fun `a host CLI too old to answer schema 2 asks the user to update it`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerSessions("""{"schema":1,"managers":["tmux"],"sessions":[]}""")
        val viewModel = viewModel(hostId)

        viewModel.refresh()
        advanceUntilIdle()

        val failure = requireNotNull(viewModel.state.value.failure)
        assertTrue(failure, failure.contains("too old"))
        assertFalse(viewModel.state.value.loaded)
    }

    @Test
    fun `a failed dial surfaces the transport message and never runs a command`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            stack.factory.failWith = "Connect to testuser@10.0.2.2:2222 failed: refused"
            val viewModel = viewModel(hostId)

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(
                "Connect to testuser@10.0.2.2:2222 failed: refused",
                viewModel.state.value.failure,
            )
            assertEquals(0, stack.factory.connections.size)
        }

    @Test
    fun `an untrusted host key is reported, not answered here`() = runTest(dispatcher) {
        stack.close()
        // A factory that presents a fingerprint the host row does not trust.
        stack = TestConnectStack(presentedFingerprint = "SHA256:presented-by-the-fixture")
        val hostId = stack.seedHost()
        val viewModel = viewModel(hostId)

        viewModel.refresh()
        advanceUntilIdle()

        val failure = requireNotNull(viewModel.state.value.failure)
        assertTrue(failure, failure.contains("host list"))
        // The tree must NOT have written a trust decision of its own.
        assertNull(stack.storedFingerprint(hostId))
    }

    @Test
    fun `a refresh that fails keeps the last good listing on screen under the error`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            // One good listing, then a broken one on the SAME live connection —
            // `once` makes the first rule fall away after it matches.
            stack.factory.script = { connection ->
                connection.onExecPrefix(
                    "pocketshell sessions list",
                    ExecResult(0, HEALTHY_LISTING, "", false),
                    once = true,
                )
                connection.onExecPrefix(
                    "pocketshell sessions list",
                    ExecResult(1, "", "tmux: server exited", false),
                )
            }
            val viewModel = viewModel(hostId)

            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(4, viewModel.state.value.sessionCount)

            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(requireNotNull(state.failure), state.failure!!.contains("exit 1"))
            assertEquals("the last good listing must survive a failed refresh", 4, state.sessionCount)
            assertTrue(state.loaded)
            assertFalse(state.refreshing)
        }

    @Test
    fun `a successful refresh clears a previous failure`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.factory.script = { connection ->
            connection.onExecPrefix(
                "pocketshell sessions list",
                ExecResult(127, "", "not found", false),
                once = true,
            )
            connection.onExecPrefix(
                "pocketshell sessions list",
                ExecResult(0, HEALTHY_LISTING, "", false),
            )
        }
        val viewModel = viewModel(hostId)

        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.failure != null)

        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.state.value.failure)
        assertEquals(4, viewModel.state.value.sessionCount)
    }

    @Test
    fun `overlapping refreshes collapse into one read`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerSessions(HEALTHY_LISTING)
        val viewModel = viewModel(hostId)

        // Three calls with no scheduler turn in between — the ON_START effect
        // firing while the user also pulls to refresh.
        viewModel.refresh()
        viewModel.refresh()
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(1, stack.factory.dialCount)
        assertEquals(listOf("pocketshell sessions list --json"), connection().executedCommands)
    }

    @Test
    fun `a second refresh after the first completed does read again`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerSessions(HEALTHY_LISTING)
        val viewModel = viewModel(hostId)

        viewModel.refresh()
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, connection().executedCommands.size)
        // Still ONE connection: the registry reuses the live one.
        assertEquals(1, stack.factory.dialCount)
    }

    @Test
    fun `the first load shows loading, a later refresh shows refreshing`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerSessions(HEALTHY_LISTING)
        val viewModel = viewModel(hostId)

        viewModel.refresh()
        // Not yet advanced: the read is queued but has not run.
        assertTrue("first load must be `loading`", viewModel.state.value.loading)
        assertFalse("first load must not drive the pull indicator", viewModel.state.value.refreshing)
        advanceUntilIdle()

        viewModel.refresh()
        assertTrue("a refresh over content must be `refreshing`", viewModel.state.value.refreshing)
        assertFalse(viewModel.state.value.loading)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.refreshing)
    }

    /**
     * The `ExecResult` → `ExecOutcome` bridge. Both are four-field records of
     * the same shapes, so a positional/misnamed mapping compiles cleanly and
     * shows up only as "response was not valid JSON" much later.
     */
    @Test
    fun `asRemoteExec maps every field by name`() = runTest(dispatcher) {
        val connection = FakeHostConnection()
        connection.onExec(
            "probe",
            ExecResult(exitCode = 3, stdout = "OUT", stderr = "ERR", timedOut = true),
        )

        val outcome = connection.asRemoteExec().exec("probe", timeoutMs = 4_242)

        assertEquals(ExecOutcome(exitCode = 3, stdout = "OUT", stderr = "ERR", timedOut = true), outcome)
        assertEquals(4_242L, connection.execCalls.single().timeoutMs)
    }

    // --- helpers ----------------------------------------------------------

    private fun viewModel(hostId: Long) = SessionTreeViewModel(
        savedStateHandle = SavedStateHandle(mapOf(Destination.ARG_HOST_ID to hostId)),
        registry = stack.registry,
        // The production binding, verbatim (see AppModule.provideHostCliClientFactory).
        clients = HostCliClientFactory { connection -> HostCliClient(connection.asRemoteExec()) },
    )

    private fun answerSessions(json: String) {
        stack.factory.script = { connection ->
            connection.onExecPrefix(
                "pocketshell sessions list",
                ExecResult(exitCode = 0, stdout = json, stderr = "", timedOut = false),
            )
        }
    }

    private fun connection(): FakeHostConnection = stack.factory.connections.single()

    private companion object {
        /**
         * Two named workspaces plus a workspace-less session, one aplexer row
         * with an engine/tag, and a reported agent state — the shape the Docker
         * `agents` fixture serves journey J02.
         */
        val HEALTHY_LISTING = """
            {
              "schema": 2,
              "managers": ["tmux", "aplexer"],
              "sessions": [
                {"name":"claude-main","manager":"tmux","id":null,
                 "workspace":"/home/testuser/git/pocketshell","tag":null,"engine":"claude",
                 "profile":null,"agent_state":"working","agent_state_source":"reported",
                 "attached":true,"created_epoch":1788380000,"activity_epoch":1788409253},
                {"name":"codex","manager":"tmux","id":null,
                 "workspace":"/home/testuser/git/pocketshell","tag":null,"engine":null,
                 "profile":null,"agent_state":null,"agent_state_source":null,
                 "attached":false,"created_epoch":1788370000,"activity_epoch":1788409100},
                {"name":"opencode-lab","manager":"tmux","id":null,
                 "workspace":null,"tag":null,"engine":null,
                 "profile":null,"agent_state":null,"agent_state_source":null,
                 "attached":false,"created_epoch":1788360000,"activity_epoch":1788400000},
                {"name":"aplexer-follow:yolo","manager":"aplexer","id":"52a2508e",
                 "workspace":"/home/testuser/git/aplexer","tag":"yolo","engine":"codex",
                 "profile":null,"agent_state":"waiting","agent_state_source":"heuristic",
                 "attached":false,"created_epoch":1788350000,"activity_epoch":1788409200}
              ],
              "errors": []
            }
        """.trimIndent()

        val UNKNOWN_MANAGER_LISTING = """
            {
              "schema": 2,
              "managers": ["tmux", "warpdrive"],
              "sessions": [
                {"name":"tmux-one","manager":"tmux","id":null,"workspace":"/w","tag":null,
                 "engine":null,"profile":null,"agent_state":null,"agent_state_source":null,
                 "attached":false,"created_epoch":null,"activity_epoch":10},
                {"name":"from-the-future","manager":"warpdrive","id":null,"workspace":"/w",
                 "tag":null,"engine":null,"profile":null,"agent_state":null,
                 "agent_state_source":null,"attached":false,"created_epoch":null,
                 "activity_epoch":20}
              ],
              "errors": []
            }
        """.trimIndent()

        val PARTIAL_LISTING = """
            {
              "schema": 2,
              "managers": ["tmux"],
              "sessions": [
                {"name":"claude-main","manager":"tmux","id":null,"workspace":"/w","tag":null,
                 "engine":null,"profile":null,"agent_state":null,"agent_state_source":null,
                 "attached":false,"created_epoch":null,"activity_epoch":10}
              ],
              "errors": [
                {"manager":"aplexer",
                 "message":"`a --json snapshot` failed: exit 127 (command not found)"}
              ]
            }
        """.trimIndent()
    }
}
