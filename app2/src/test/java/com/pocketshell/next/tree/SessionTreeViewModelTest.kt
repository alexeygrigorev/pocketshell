package com.pocketshell.next.tree

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.AgentState
import com.pocketshell.core.hostapi.AgentStateSource
import com.pocketshell.core.hostapi.Backend
import com.pocketshell.core.hostapi.ExecOutcome
import com.pocketshell.core.hostapi.HostCliClient
import com.pocketshell.core.storage.entity.ProjectRootEntity
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
    fun `a healthy listing groups every session into root then folder`() = runTest(dispatcher) {
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

        // One inferred `~/git` root (pocketshell + aplexer folders) plus `other`.
        // Creation order: aplexer (1788350000) is older than pocketshell (1788370000).
        assertEquals(listOf("~/git", OTHER_ROOT_LABEL), state.roots.map { it.headerLabel })
        assertEquals(listOf("aplexer", "pocketshell"), state.roots[0].folders.map { it.label })
        assertEquals(listOf("aplexer-follow:yolo"), state.roots[0].folders[0].rows.map { it.name })
        assertEquals(listOf("codex", "claude-main"), state.roots[0].folders[1].rows.map { it.name })
        assertEquals(listOf("opencode-lab"), state.roots[1].folders.single().rows.map { it.name })
        assertTrue(state.roots[1].folders.single().untracked)
        assertEquals(4, state.sessionCount)

        // The parsed detail the screen renders actually survived the round trip.
        val claude = state.roots[0].folders[1].rows.single { it.name == "claude-main" }
        assertEquals(Backend.TMUX, claude.backend)
        assertEquals(AgentState.WORKING, claude.agentState)
        assertEquals(AgentStateSource.REPORTED, claude.agentStateSource)
        assertTrue(claude.attached)
        val aplexer = state.roots[0].folders[0].rows.single()
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
    fun `registered workspace roots become the root list and unmatched go to other`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            stack.db.projectRootDao().insert(
                ProjectRootEntity(hostId = hostId, label = "tmp", path = "~/tmp", createdAt = 1),
            )
            stack.db.projectRootDao().insert(
                ProjectRootEntity(hostId = hostId, label = "git", path = "~/git", createdAt = 2),
            )
            answerSessions(HEALTHY_LISTING)
            val viewModel = viewModel(hostId)

            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.state.value
            // Registration order (tmp then git), not recency, and the unmatched
            // workspace-less session lands in `other`.
            assertEquals(listOf("~/tmp", "~/git", OTHER_ROOT_LABEL), state.roots.map { it.headerLabel })
            assertEquals(0, state.roots[0].sessionCount)
            assertTrue(state.roots[0].configured)
            assertEquals(listOf("aplexer", "pocketshell"), state.roots[1].folders.map { it.label })
            assertEquals(listOf("opencode-lab"), state.roots[2].folders.single().rows.map { it.name })
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
            state.roots.flatMap { it.folders }.flatMap { it.rows }.any { it.backend == Backend.UNKNOWN },
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
        assertEquals(emptyList<SessionRoot>(), state.roots)
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

    // --- create (task U-6) -------------------------------------------------

    @Test
    fun `a successful create refreshes the listing and asks the screen to open it`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            answerListAndCreate(HEALTHY_LISTING, createdJson("demo", created = true))
            val viewModel = viewModel(hostId)

            viewModel.refresh()
            advanceUntilIdle()
            viewModel.openCreateSheet()
            assertTrue(viewModel.state.value.create.visible)

            viewModel.createSession(
                CreateSessionRequest(name = "demo", cwd = "/home/testuser/git/pocketshell"),
            )
            advanceUntilIdle()

            val create = viewModel.state.value.create
            assertNull("a successful create is not a failure", create.failure)
            assertNull("a session that did NOT exist needs no notice", create.notice)
            assertFalse("the sheet closes on success", create.visible)
            assertFalse(create.submitting)
            assertEquals("demo", create.openRequest)

            // The command is the host CLI's own, with --cwd quoted and the name
            // after `--`. A Shell create with the host-default backend omits
            // --engine / --profile / --backend.
            val createCommand = connection().executedCommands.single { "create" in it }
            assertEquals(
                "pocketshell sessions create --json --cwd '/home/testuser/git/pocketshell' -- 'demo'",
                createCommand,
            )
            // ...and the listing was re-read afterwards, so the new session can
            // appear on the tree.
            assertEquals(
                2,
                connection().executedCommands.count { it == "pocketshell sessions list --json" },
            )
            assertTrue(viewModel.state.value.loaded)
        }

    /**
     * The idempotency contract (`CreatedSession.created == false`): the session
     * already existed, which is a SUCCESS. It must still open, with a notice
     * rather than an error — treating "already there" as a failure is exactly
     * the bug the host CLI's idempotent create exists to prevent.
     */
    @Test
    fun `creating a name that already exists opens it instead of failing`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerListAndCreate(HEALTHY_LISTING, createdJson("claude-main", created = false))
        val viewModel = viewModel(hostId)

        viewModel.openCreateSheet()
        viewModel.createSession(CreateSessionRequest(name = "claude-main", cwd = null))
        advanceUntilIdle()

        val create = viewModel.state.value.create
        assertNull("an existing session must NOT read as a failure", create.failure)
        assertEquals("claude-main", create.openRequest)
        assertFalse(create.visible)
        val notice = requireNotNull(create.notice) { "the user should be told it already existed" }
        assertTrue(notice, notice.contains("already existed"))
        assertTrue(notice, notice.contains("claude-main"))
        // And the tree itself is not in an error state over it.
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `a failed create keeps the sheet open carrying the hosts own words`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.factory.script = { connection ->
            connection.onExecPrefix(
                "pocketshell sessions list",
                ExecResult(0, HEALTHY_LISTING, "", false),
            )
            connection.onExecPrefix(
                "pocketshell sessions create",
                // The host's create prints its OWN explanation as a JSON error
                // envelope on stdout and exits non-zero.
                ExecResult(
                    exitCode = 1,
                    stdout = """{"schema":2,"error":"pocketshell: `tmuxctl create-detached demo` exited 2."}""",
                    stderr = "",
                    timedOut = false,
                ),
            )
        }
        val viewModel = viewModel(hostId)

        viewModel.refresh()
        advanceUntilIdle()
        viewModel.openCreateSheet()
        viewModel.createSession(CreateSessionRequest(name = "demo", cwd = "/nope"))
        advanceUntilIdle()

        val create = viewModel.state.value.create
        val failure = requireNotNull(create.failure) { "a failed create must be reported" }
        assertTrue(failure, failure.contains("tmuxctl create-detached demo"))
        assertTrue("the sheet must stay open so the user can retry", create.visible)
        assertFalse(create.submitting)
        assertNull("a failed create must never navigate", create.openRequest)
        // The tree's own listing is untouched: the create failed, the list did not.
        assertNull(viewModel.state.value.failure)
        assertEquals(4, viewModel.state.value.sessionCount)
    }

    @Test
    fun `a blank name is refused before anything is sent to the host`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerListAndCreate(HEALTHY_LISTING, createdJson("demo", created = true))
        val viewModel = viewModel(hostId)

        viewModel.refresh()
        advanceUntilIdle()
        viewModel.openCreateSheet()
        viewModel.createSession(CreateSessionRequest(name = "   ", cwd = "/home/testuser"))
        advanceUntilIdle()

        assertEquals(BLANK_NAME_MESSAGE, viewModel.state.value.create.failure)
        assertTrue(viewModel.state.value.create.visible)
        assertNull(viewModel.state.value.create.openRequest)
        assertTrue(
            "the host CLI requires NAME, so nothing may be sent",
            connection().executedCommands.none { "create" in it },
        )
    }

    /** A blank folder means no `--cwd` at all, so the host's default applies. */
    @Test
    fun `a blank folder omits the cwd option entirely`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerListAndCreate(HEALTHY_LISTING, createdJson("demo", created = true))
        val viewModel = viewModel(hostId)

        viewModel.createSession(CreateSessionRequest(name = "demo", cwd = "   "))
        advanceUntilIdle()

        assertEquals(
            "pocketshell sessions create --json -- 'demo'",
            connection().executedCommands.single { "create" in it },
        )
    }

    @Test
    fun `the open request is one-shot`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerListAndCreate(HEALTHY_LISTING, createdJson("demo", created = true))
        val viewModel = viewModel(hostId)

        viewModel.createSession(CreateSessionRequest(name = "demo", cwd = null))
        advanceUntilIdle()
        assertEquals("demo", viewModel.state.value.create.openRequest)

        viewModel.consumeOpenRequest()

        assertNull(
            "a consumed signal must not re-fire when the user comes back",
            viewModel.state.value.create.openRequest,
        )
    }

    @Test
    fun `a create in flight cannot be started twice or dismissed`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerListAndCreate(HEALTHY_LISTING, createdJson("demo", created = true))
        val viewModel = viewModel(hostId)

        viewModel.openCreateSheet()
        viewModel.createSession(CreateSessionRequest(name = "demo", cwd = null))
        // Not advanced: the create is queued but has not answered yet.
        assertTrue(viewModel.state.value.create.submitting)

        viewModel.createSession(CreateSessionRequest(name = "demo-2", cwd = null))
        viewModel.dismissCreateSheet()
        assertTrue("a submitting sheet must stay on screen", viewModel.state.value.create.visible)
        advanceUntilIdle()

        assertEquals(
            "the second tap must not create a second session",
            1,
            connection().executedCommands.count { "create" in it },
        )
        assertEquals("demo", viewModel.state.value.create.openRequest)
    }

    @Test
    fun `dismissing the sheet creates nothing and clears a previous failure`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            answerListAndCreate(HEALTHY_LISTING, createdJson("demo", created = true))
            val viewModel = viewModel(hostId)

            viewModel.openCreateSheet()
            viewModel.createSession(CreateSessionRequest(name = "", cwd = null))
            assertEquals(BLANK_NAME_MESSAGE, viewModel.state.value.create.failure)

            viewModel.dismissCreateSheet()
            advanceUntilIdle()

            val create = viewModel.state.value.create
            assertFalse(create.visible)
            assertNull(create.failure)
            assertNull(create.openRequest)
            // A blank name is answered locally: `sessions create` is never sent.
            assertTrue(
                "a blank name must never reach sessions create",
                stack.factory.connections.flatMap { it.executedCommands }
                    .none { "create" in it },
            )
        }

    @Test
    fun `an agent create forwards engine profile and backend on the argv`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerListAndCreate(HEALTHY_LISTING, createdJson("demo", created = true))
        val viewModel = viewModel(hostId)

        viewModel.createSession(
            CreateSessionRequest(
                name = "demo",
                cwd = "/home/testuser/git/pocketshell",
                engine = "claude",
                profile = "Claude (Z.AI)",
                backend = "aplexer",
            ),
        )
        advanceUntilIdle()

        assertEquals(
            "pocketshell sessions create --json " +
                "--cwd '/home/testuser/git/pocketshell' " +
                "--engine 'claude' " +
                "--profile 'Claude (Z.AI)' " +
                "--backend 'aplexer' " +
                "-- 'demo'",
            connection().executedCommands.single { "create" in it },
        )
        assertEquals("demo", viewModel.state.value.create.openRequest)
    }

    @Test
    fun `opening the sheet loads engines including ones the picker will hide`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            answerListAndCreate(HEALTHY_LISTING, createdJson("demo", created = true))
            val viewModel = viewModel(hostId)

            viewModel.openCreateSheet()
            advanceUntilIdle()

            val create = viewModel.state.value.create
            assertEquals(
                listOf("claude", "codex", "opencode", "disabled"),
                create.engines.map { it.id },
            )
            assertFalse(create.enginesLoading)
            assertNull(create.enginesFailure)
            assertEquals(
                listOf("pocketshell engines list --json", "pocketshell profiles list --json"),
                connection().executedCommands.filter { "engines" in it || "profiles" in it },
            )
            // The hide rule is the sheet's: the VM keeps the host's full list
            // so a dropped-row regression is a picker bug, not a missing fetch.
            assertEquals(
                listOf("claude", "codex"),
                availableEnginesForCreate(create.engines).map { it.id },
            )
        }

    /**
     * The sheet's folder prefill: the most recently active workspace the host
     * reported, never the "other" bucket's label (which is not a path).
     */
    @Test
    fun `the suggested folder is the most recent real workspace`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerSessions(HEALTHY_LISTING)
        val viewModel = viewModel(hostId)

        assertEquals("", viewModel.state.value.suggestedFolder)

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals("/home/testuser/git/pocketshell", viewModel.state.value.suggestedFolder)
    }

    @Test
    fun `a workspace-less host suggests no folder rather than the other bucket`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            answerSessions(
                """
                {"schema":2,"managers":["tmux"],"sessions":[
                  {"name":"homeless","manager":"tmux","id":null,"workspace":null,"tag":null,
                   "engine":null,"profile":null,"agent_state":null,"agent_state_source":null,
                   "attached":false,"created_epoch":null,"activity_epoch":10}
                ],"errors":[]}
                """.trimIndent(),
            )
            val viewModel = viewModel(hostId)

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(1, viewModel.state.value.sessionCount)
            assertEquals("", viewModel.state.value.suggestedFolder)
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

    // --- stop (issue #2535) ----------------------------------------------

    @Test
    fun `requesting Stop does not kill until confirm`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerListAndKill(HEALTHY_LISTING, LISTING_WITHOUT_CLAUDE)
        val viewModel = viewModel(hostId)

        viewModel.refresh()
        advanceUntilIdle()
        viewModel.requestStopSession("claude-main")
        advanceUntilIdle()

        assertEquals("claude-main", viewModel.state.value.pendingStop)
        assertTrue(
            "the kebab must not kill before confirm",
            connection().executedCommands.none { "kill" in it },
        )
        assertTrue(
            viewModel.state.value.roots.flatMap { it.folders }.flatMap { it.rows }.any { it.name == "claude-main" },
        )
    }

    @Test
    fun `cancelling Stop is a no-op on the host`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerListAndKill(HEALTHY_LISTING, LISTING_WITHOUT_CLAUDE)
        val viewModel = viewModel(hostId)

        viewModel.refresh()
        advanceUntilIdle()
        viewModel.requestStopSession("claude-main")
        viewModel.cancelStopSession()
        advanceUntilIdle()

        assertNull(viewModel.state.value.pendingStop)
        assertTrue(
            "cancel must not send sessions kill",
            connection().executedCommands.none { "kill" in it },
        )
        assertTrue(
            "cancel must leave the session on the tree",
            viewModel.state.value.roots.flatMap { it.folders }.flatMap { it.rows }.any { it.name == "claude-main" },
        )
    }

    @Test
    fun `confirming Stop kills the exact name and drops the row on refresh`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        answerListAndKill(HEALTHY_LISTING, LISTING_WITHOUT_CLAUDE)
        val viewModel = viewModel(hostId)

        viewModel.refresh()
        advanceUntilIdle()
        viewModel.requestStopSession("claude-main")
        viewModel.confirmStopSession()
        advanceUntilIdle()

        assertNull(viewModel.state.value.pendingStop)
        assertEquals(
            "pocketshell sessions kill -- 'claude-main'",
            connection().executedCommands.single { "kill" in it },
        )
        assertTrue(
            "killing claude-main must not name a neighbour",
            connection().executedCommands.none { it.contains("api-staging") },
        )
        assertTrue(
            viewModel.state.value.roots.flatMap { it.folders }.flatMap { it.rows }.none { it.name == "claude-main" },
        )
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `a failed Stop keeps the row and shows the hosts own words`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.factory.script = { connection ->
            connection.onExecPrefix(
                "pocketshell sessions list",
                ExecResult(0, HEALTHY_LISTING, "", false),
            )
            connection.onExecPrefix(
                "pocketshell sessions kill",
                ExecResult(3, "", "no session named 'claude-main'\n", false),
            )
        }
        val viewModel = viewModel(hostId)

        viewModel.refresh()
        advanceUntilIdle()
        viewModel.requestStopSession("claude-main")
        viewModel.confirmStopSession()
        advanceUntilIdle()

        val failure = requireNotNull(viewModel.state.value.failure)
        assertTrue(failure, failure.contains("no session named 'claude-main'"))
        assertTrue(
            "a refused kill must not drop the row locally",
            viewModel.state.value.roots.flatMap { it.folders }.flatMap { it.rows }.any { it.name == "claude-main" },
        )
    }

    // --- helpers ----------------------------------------------------------

    private fun viewModel(hostId: Long) = SessionTreeViewModel(
        savedStateHandle = SavedStateHandle(mapOf(Destination.ARG_HOST_ID to hostId)),
        registry = stack.registry,
        // The production binding, verbatim (see AppModule.provideHostCliClientFactory).
        clients = HostCliClientFactory { connection -> HostCliClient(connection.asRemoteExec()) },
        projectRootDao = stack.db.projectRootDao(),
    )

    private fun answerSessions(json: String) {
        stack.factory.script = { connection ->
            connection.onExecPrefix(
                "pocketshell sessions list",
                ExecResult(exitCode = 0, stdout = json, stderr = "", timedOut = false),
            )
        }
    }

    /**
     * Scripts list + kill so the listing after a successful kill no longer
     * contains the stopped session.
     */
    private fun answerListAndKill(beforeJson: String, afterJson: String) {
        var killed = false
        stack.factory.script = { connection ->
            connection.onExecMatching("sessions list or kill", once = false, { true }) { command ->
                when {
                    command.startsWith("pocketshell sessions kill") -> {
                        killed = true
                        ExecResult(exitCode = 0, stdout = "", stderr = "", timedOut = false)
                    }
                    else -> ExecResult(
                        exitCode = 0,
                        stdout = if (killed) afterJson else beforeJson,
                        stderr = "",
                        timedOut = false,
                    )
                }
            }
        }
    }

    /** Scripts both verbs on one connection: the listing and the create. */
    private fun answerListAndCreate(listJson: String, createJson: String) {
        stack.factory.script = { connection ->
            connection.onExecPrefix(
                "pocketshell sessions list",
                ExecResult(exitCode = 0, stdout = listJson, stderr = "", timedOut = false),
            )
            connection.onExecPrefix(
                "pocketshell sessions create",
                ExecResult(exitCode = 0, stdout = createJson, stderr = "", timedOut = false),
            )
            connection.onExecPrefix(
                "pocketshell engines list",
                ExecResult(exitCode = 0, stdout = ENGINES_LISTING, stderr = "", timedOut = false),
            )
            connection.onExecPrefix(
                "pocketshell profiles list",
                ExecResult(exitCode = 0, stdout = PROFILES_LISTING, stderr = "", timedOut = false),
            )
        }
    }

    /** The host CLI's schema-2 create envelope, verbatim in shape. */
    private fun createdJson(name: String, created: Boolean): String = """
        {"schema": 2, "name": "$name", "manager": "tmux", "id": null, "created": $created}
    """.trimIndent()

    private fun connection(): FakeHostConnection = stack.factory.connections.single()

    private companion object {
        /**
         * Two named workspaces plus a workspace-less session, one aplexer row
         * with an engine/tag, and a reported agent state — the shape the Docker
         * `agents` fixture serves journey J02.
         */
        val LISTING_WITHOUT_CLAUDE = """
            {
              "schema": 2,
              "managers": ["tmux", "aplexer"],
              "sessions": [
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

        val ENGINES_LISTING = """
            {"engines":[
              {"id":"claude","label":"Claude","available_for_create":true,
               "enabled":true,"available":true},
              {"id":"codex","label":"Codex","available_for_create":true,
               "enabled":true,"available":true},
              {"id":"opencode","label":"OpenCode","available_for_create":false,
               "enabled":true,"available":false,
               "unavailable_reason":"`opencode` is not installed on this host (not on PATH)."},
              {"id":"disabled","label":"Disabled","available_for_create":false,
               "enabled":false,"available":true,
               "unavailable_reason":"disabled in the host registry"}
            ]}
        """.trimIndent()

        val PROFILES_LISTING = """
            {"profiles":[
              {"name":"Claude","engine":"claude","config_dir":null,"default":true},
              {"name":"Claude (Z.AI)","engine":"claude","config_dir":"/home/a/.zlaude","default":false},
              {"name":"Codex","engine":"codex","default":true}
            ]}
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
