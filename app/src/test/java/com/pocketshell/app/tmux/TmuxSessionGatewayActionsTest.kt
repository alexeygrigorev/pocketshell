package com.pocketshell.app.tmux

import com.pocketshell.app.projects.FolderImportPayload
import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.app.projects.FolderListResult
import com.pocketshell.app.projects.SessionNamePolicy
import com.pocketshell.app.projects.WindowKillOutcome
import com.pocketshell.app.projects.SessionCreateOutcome
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionGatewayActionsTest : TmuxSessionViewModelTestBase() {
    @Test
    fun createSessionWithoutActiveTargetFailsVisiblyWithoutUsingGateway() = runTest(scheduler) {
        val gateway = RecordingStopGateway(killSucceeds = true, createResolvedName = "unused")
        val vm = newVm(
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val failure = async { vm.sessionCreateError.first() }
        runCurrent()

        vm.createSession(name = "new-work", cwd = "/srv/work")
        runCurrent()

        assertTrue(
            "create after attach teardown must emit a user-visible failure instead of going silent",
            failure.isCompleted,
        )
        assertEquals(
            "Session isn't attached yet. Reconnect, then try again.",
            failure.await(),
        )
        assertTrue("no target means no gateway create", gateway.createCalls.isEmpty())
    }

    @Test
    fun createSessionWithoutProductionGatewayFailsInsteadOfUsingControlChannel() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        val failure = async { vm.sessionCreateError.first() }
        runCurrent()

        vm.createSession(name = "new-work", cwd = "/srv/work")
        runCurrent()

        assertTrue("missing production dependencies must be reported", failure.isCompleted)
        assertEquals("Session creation isn't available.", failure.await())
        assertFalse(
            "the obsolete control-channel create fallback must be deleted",
            client.sentCommands.any { it.startsWith("new-session") },
        )
    }

    @Test
    fun createSessionMissingHostFailsVisiblyWithoutUsingGateway() = runTest(scheduler) {
        val gateway = RecordingStopGateway(killSucceeds = true, createResolvedName = "unused")
        val vm = newVm(
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 8L),
        )
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "missing",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        val failure = async { vm.sessionCreateError.first() }
        runCurrent()

        vm.createSession(name = "new-work", cwd = "/srv/work")
        awaitCondition { failure.isCompleted }

        assertEquals("Host not found. Return home and reconnect.", failure.await())
        assertTrue("missing host means no gateway create", gateway.createCalls.isEmpty())
    }

    @Test
    fun killCurrentSessionBroadcastsSignalOnConfirmedKill() = runTest(scheduler) {
        val signals = SessionLifecycleSignals()
        val gateway = RecordingStopGateway(killSucceeds = true)
        val vm = newVm(
            sessionLifecycleSignals = signals,
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "doomed",
            tmuxSessionId = "\$4",
            sessionCreated = 1_710_000_000L,
            client = client,
        )
        runCurrent()

        val killed = async { signals.killedSessions.first() }
        runCurrent()

        vm.killCurrentSession()
        advanceUntilIdle()

        val event = killed.await()
        assertEquals(7L, event.hostId)
        assertEquals("doomed", event.sessionName)
        assertTrue(
            "expected the gateway kill to target 'doomed', got ${gateway.killedSessionNames}",
            gateway.killedSessionNames.contains("doomed"),
        )
        assertFalse(
            "in-session Stop must not kill over the control channel; sent=${client.sentCommands}",
            client.sentCommands.any { it.startsWith("kill-session") },
        )
    }

    @Test
    fun confirmedKillEvictsKilledGenerationFromCaches() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val agentSessionMemory = AgentSessionMemory()
        val signals = SessionLifecycleSignals(
            runtimeCache = runtimeCache,
            agentSessionMemory = agentSessionMemory,
        )
        val doomedRuntime = cachedRuntimeForGatewayActionTest(
            sessionName = "doomed",
            hostId = 7L,
            durableSessionKey = "tmux:7:\$4:1710000000",
        )
        val successorRuntime = cachedRuntimeForGatewayActionTest(
            sessionName = "doomed",
            hostId = 7L,
            durableSessionKey = "tmux:7:\$9:1720000000",
        )
        val otherRuntime = cachedRuntimeForGatewayActionTest(sessionName = "other", hostId = 7L)
        runtimeCache.put(doomedRuntime)
        runtimeCache.put(successorRuntime)
        runtimeCache.put(otherRuntime)
        agentSessionMemory.remember(
            hostId = 7L,
            sessionName = "doomed",
            windowId = "@2",
            detection = AgentDetection(
                agent = AgentKind.ClaudeCode,
                sourcePath = "/home/alex/.claude/projects/doomed.jsonl",
                sessionId = "doomed-agent",
                confidence = AgentDetection.Confidence.ProcessConfirmed,
            ),
            wasOnConversation = true,
            durableSessionKey = "tmux:7:\$4:1710000000",
        )
        agentSessionMemory.remember(
            hostId = 7L,
            sessionName = "doomed",
            windowId = "@2",
            detection = AgentDetection(
                agent = AgentKind.Codex,
                sourcePath = "/home/alex/.codex/projects/doomed.jsonl",
                sessionId = "successor-agent",
                confidence = AgentDetection.Confidence.ProcessConfirmed,
            ),
            wasOnConversation = false,
            durableSessionKey = "tmux:7:\$9:1720000000",
        )
        val gateway = RecordingStopGateway(killSucceeds = true)
        val vm = newVm(
            runtimeCache = runtimeCache,
            agentSessionMemory = agentSessionMemory,
            sessionLifecycleSignals = signals,
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "doomed",
            tmuxSessionId = "\$4",
            sessionCreated = 1_710_000_000L,
            client = FakeTmuxClient(),
        )
        runCurrent()

        val killed = async { signals.killedSessions.first() }
        runCurrent()

        vm.killCurrentSession()
        advanceUntilIdle()

        val event = killed.await()
        assertEquals(7L, event.hostId)
        assertEquals("doomed", event.sessionName)
        assertFalse(
            "confirmed Stop must evict the killed session's parked runtime",
            runtimeCache.contains(doomedRuntime.key),
        )
        assertTrue(runtimeCache.contains(otherRuntime.key))
        assertTrue(runtimeCache.contains(successorRuntime.key))
        assertNull(
            "the killed generation's agent memory must be forgotten",
            agentSessionMemory.recall(
                7L,
                "doomed",
                "@2",
                durableSessionKey = "tmux:7:\$4:1710000000",
            ),
        )
        assertEquals(
            AgentKind.Codex,
            agentSessionMemory.recall(
                7L,
                "doomed",
                "@2",
                durableSessionKey = "tmux:7:\$9:1720000000",
            )?.detection?.agent,
        )
    }

    @Test
    fun createSessionRoutesChosenAgentOptionsThroughGateway() = runTest(scheduler) {
        val gateway = RecordingStopGateway(
            killSucceeds = true,
            createResolvedName = "git-claude",
        )
        val vm = newVm(
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        runCurrent()

        val startCommand = "pocketshell agent codex --dir '/home/alex/git' " +
            "--no-skip-permissions --profile 'work'"
        val resolvedDeferred = CompletableDeferred<String>()
        vm.createSession(
            name = "git-codex",
            cwd = "/home/alex/git",
            startCommand = startCommand,
            chosenKind = SessionAgentKind.Codex,
            onResolved = { resolvedDeferred.complete(it) },
        )
        val resolved = resolvedDeferred.await()
        advanceUntilIdle()

        assertEquals(
            "expected exactly one gateway createSession call, got ${gateway.createCalls}",
            1,
            gateway.createCalls.size,
        )
        val call = gateway.createCalls.single()
        assertEquals("git-codex", call.sessionName)
        assertEquals("/home/alex/git", call.cwd)
        assertEquals(startCommand, call.startCommand)
        // Issue #1820: the in-session "+ New session" create must ask the HOST
        // for a free name. `ExactName` here is the original defect — a second
        // same-folder create would request the taken base and fail the #976
        // guard (agent launch) or no-op-attach (plain shell).
        assertEquals(SessionNamePolicy.UniqueOnHost, call.namePolicy)
        assertEquals("git-claude", resolved)
        assertFalse(
            "rich-sheet create must use the gateway, not control-channel new-session; " +
                "sent=${client.sentCommands}",
            client.sentCommands.any { it.startsWith("new-session") },
        )
    }

    /**
     * Issue #1928 — the IN-SESSION caller of the partial-success outcome.
     *
     * The in-session "+ New session" sheet's success branch attaches the user to
     * the new session. On a partial success that would drop them into a plain
     * shell they asked to be a Codex session, silently — the create was reported
     * as fully successful, so nothing on screen said otherwise. The sheet must
     * instead surface the reason through the same `sessionCreateError` channel a
     * real create failure uses, and must NOT resolve.
     */
    @Test
    fun createSessionLaunchFailureReportsInsteadOfAttaching() = runTest(scheduler) {
        val gateway = RecordingStopGateway(
            killSucceeds = true,
            createResolvedName = "git-codex",
            createLaunchFailureDetail = "can't find pane: =git-codex:",
        )
        val vm = newVm(
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        runCurrent()
        val failure = async { vm.sessionCreateError.first() }
        runCurrent()

        val attached = CompletableDeferred<String>()
        vm.createSession(
            name = "git-codex",
            cwd = "/home/alex/git",
            startCommand = "pocketshell agent codex --dir '/home/alex/git'",
            chosenKind = SessionAgentKind.Codex,
            onResolved = { attached.complete(it) },
        )
        // The create hops through a REAL Dispatchers.IO for the host lookup, so
        // `advanceUntilIdle()` alone can return before the gateway call lands
        // (the #708 virtual-clock-vs-real-dispatcher class). Racing the two
        // outcomes is the deterministic sync point in BOTH directions: with the
        // fix the error arrives; a caller that collapses the partial success
        // attaches instead, and this fails immediately with that name rather
        // than parking until the runTest timeout.
        val message = select<String> {
            failure.onAwait { it }
            attached.onAwait { attachedName ->
                fail(
                    "a launch failure must not attach the user to the session; " +
                        "attached to $attachedName",
                )
                ""
            }
        }
        failure.cancel()
        advanceUntilIdle()

        assertFalse("a launch failure must not attach", attached.isCompleted)
        assertTrue("must name the created session: $message", message.contains("git-codex"))
        assertTrue("must say it WAS created: $message", message.contains("was created"))
        assertTrue(
            "must carry the host's reason: $message",
            message.contains("can't find pane: =git-codex:"),
        )
        assertEquals("exactly one create must have run", 1, gateway.createCalls.size)
    }

    @Test
    fun createSessionShellChoiceHasNoStartCommand() = runTest(scheduler) {
        val gateway = RecordingStopGateway(
            killSucceeds = true,
            createResolvedName = "plain",
        )
        val vm = newVm(
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        runCurrent()

        val resolvedDeferred = CompletableDeferred<String>()
        vm.createSession(
            name = "plain",
            cwd = "/srv/app",
            startCommand = null,
            chosenKind = null,
            onResolved = { resolvedDeferred.complete(it) },
        )
        resolvedDeferred.await()
        advanceUntilIdle()

        val call = gateway.createCalls.single()
        assertEquals("/srv/app", call.cwd)
        assertNull("shell session must carry no startCommand", call.startCommand)
    }

    @Test
    fun killCurrentSessionDoesNotBroadcastWhenGatewayKillFails() = runTest(scheduler) {
        val signals = SessionLifecycleSignals()
        val gateway = RecordingStopGateway(killSucceeds = false)
        val vm = newVm(
            sessionLifecycleSignals = signals,
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "stubborn",
            client = client,
        )
        runCurrent()

        var broadcast: KilledSession? = null
        val collector = async { broadcast = signals.killedSessions.first() }
        runCurrent()

        vm.killCurrentSession()
        advanceUntilIdle()

        assertNull("a failed (unverified) kill must not broadcast a lifecycle signal", broadcast)
        collector.cancel()
    }

    @Test
    fun stopOnMultiWindowRowKillsOnlyThatWindow() = runTest(scheduler) {
        val signals = SessionLifecycleSignals()
        val gateway = RecordingStopGateway(
            killSucceeds = true,
            windowKillSessionSurvived = true,
        )
        val vm = newVm(
            sessionLifecycleSignals = signals,
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "multi",
            client = client,
        )
        runCurrent()
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%0", "@10", "$0", "win0", paneIndex = 0,
                    windowIndex = 0, sessionName = "multi",
                ),
                TmuxSessionViewModel.ParsedPane(
                    "%1", "@11", "$0", "win1", paneIndex = 0,
                    windowIndex = 1, sessionName = "multi",
                ),
            ),
        )
        advanceUntilIdle()

        val closed = async { signals.closedWindows.first() }
        runCurrent()

        vm.killCurrentSession(windowIndex = 0)
        advanceUntilIdle()

        val event = closed.await()
        assertEquals(7L, event.hostId)
        assertEquals(
            "the closed-window signal must carry window 0's stable tmux id (@10)",
            "@10",
            event.windowId,
        )
        assertEquals(
            "Stop on a window row must run kill-window for ONLY that window; " +
                "got ${gateway.killedWindowTargets}",
            listOf("multi:0"),
            gateway.killedWindowTargets,
        )
        assertTrue(
            "Stop on a window row must NOT run kill-session (that would take " +
                "the whole session + sibling window down); got ${gateway.killedSessionNames}",
            gateway.killedSessionNames.isEmpty(),
        )
    }

    @Test
    fun stopOnLastWindowDestroysTheSession() = runTest(scheduler) {
        val signals = SessionLifecycleSignals()
        val gateway = RecordingStopGateway(
            killSucceeds = true,
            windowKillSessionSurvived = false,
        )
        val vm = newVm(
            sessionLifecycleSignals = signals,
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "solo",
            tmuxSessionId = "\$20",
            sessionCreated = 1_720_000_020L,
            client = client,
        )
        runCurrent()
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%0", "@20", "$0", "win0", paneIndex = 0,
                    windowIndex = 0, sessionName = "solo",
                ),
            ),
        )
        advanceUntilIdle()

        val killed = async { signals.killedSessions.first() }
        runCurrent()

        vm.killCurrentSession(windowIndex = 0)
        advanceUntilIdle()

        val event = killed.await()
        assertEquals(7L, event.hostId)
        assertEquals(
            "closing the last window destroys the session - the whole-session " +
                "signal must carry the session name",
            "solo",
            event.sessionName,
        )
        assertEquals(
            "the last-window Stop still goes through kill-window on the remote",
            listOf("solo:0"),
            gateway.killedWindowTargets,
        )
    }

    @Test
    fun stopWithoutWindowIndexKillsWholeSession() = runTest(scheduler) {
        val signals = SessionLifecycleSignals()
        val gateway = RecordingStopGateway(killSucceeds = true)
        val vm = newVm(
            sessionLifecycleSignals = signals,
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "whole",
            tmuxSessionId = "\$21",
            sessionCreated = 1_720_000_021L,
            client = client,
        )
        runCurrent()

        val killed = async { signals.killedSessions.first() }
        runCurrent()

        vm.killCurrentSession()
        advanceUntilIdle()

        val event = killed.await()
        assertEquals("whole", event.sessionName)
        assertEquals(
            "the no-window Stop must run kill-session, not kill-window",
            listOf("whole"),
            gateway.killedSessionNames,
        )
        assertTrue(
            "the no-window Stop must NOT run kill-window; got ${gateway.killedWindowTargets}",
            gateway.killedWindowTargets.isEmpty(),
        )
    }

    private fun cachedRuntimeForGatewayActionTest(
        sessionName: String,
        hostId: Long = 1L,
        durableSessionKey: String? = null,
        client: FakeTmuxClient = FakeTmuxClient(),
        session: SshSession = GatewayActionSshSession(),
    ): CachedTmuxRuntime {
        val key = TmuxRuntimeKey(
            hostId = hostId,
            hostname = "alpha.example",
            port = 22,
            username = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
            durableSessionKey = durableSessionKey,
        )
        return CachedTmuxRuntime(
            key = key,
            hostName = "alpha",
            startDirectory = null,
            session = session,
            client = client,
            panes = emptyList(),
            paneRows = emptyMap(),
            paneProducerJobs = emptyMap(),
            paneInputQueues = emptyMap(),
            paneInputJobs = emptyMap(),
            paneAgentInputs = emptyMap(),
            agentConversations = emptyMap(),
            remoteColumns = 0,
            remoteRows = 0,
            lease = null,
        )
    }

    private class GatewayActionSshSession : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun tail(
            path: String,
            fromLineExclusive: Long,
            onLine: (String) -> Unit,
        ): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError()

        override fun startShell(): SshShell = throw NotImplementedError()

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }

    private class RecordingStopGateway(
        private val killSucceeds: Boolean,
        private val windowKillSessionSurvived: Boolean? = null,
        private val windowKillSucceeds: Boolean = true,
        private val createResolvedName: String? = null,
        /**
         * Issue #1928: when set, the gateway reports PARTIAL success — the tmux
         * session [createResolvedName] exists but its agent launch failed with
         * this reason. `null` keeps the full-success behaviour.
         */
        private val createLaunchFailureDetail: String? = null,
    ) : FolderListGateway {
        val killedSessionNames = mutableListOf<String>()
        val killedWindowTargets = mutableListOf<String>()

        data class CreateCall(
            val sessionName: String,
            val cwd: String,
            val startCommand: String?,
            /**
             * Issue #1820: the policy the PRODUCTION caller chose. Recording it
             * is the point — the whole fix is "each call site declares its
             * intent", so the declaration is load-bearing state and a fake that
             * discards it cannot fail when a caller flips to the wrong one.
             */
            val namePolicy: SessionNamePolicy,
        )
        val createCalls = mutableListOf<CreateCall>()

        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult = FolderListResult.Sessions(rows = emptyList())

        override suspend fun createSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
            cwd: String,
            startCommand: String?,
            namePolicy: SessionNamePolicy,
        ): Result<SessionCreateOutcome> {
            createCalls += CreateCall(sessionName, cwd, startCommand, namePolicy)
            val name = createResolvedName ?: error("not used")
            return Result.success(
                createLaunchFailureDetail
                    ?.let { SessionCreateOutcome.LaunchFailed(name, it) }
                    ?: SessionCreateOutcome.Created(name),
            )
        }

        override suspend fun createEmptyProject(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            parentPath: String,
            folderName: String,
        ): Result<String> = error("not used")

        override suspend fun importFile(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            folderPath: String,
            payload: FolderImportPayload,
        ): Result<String> = error("not used")

        override suspend fun killSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
        ): Result<Unit> {
            if (!killSucceeds) {
                return Result.failure(
                    RuntimeException("tmux session '$sessionName' is still running."),
                )
            }
            killedSessionNames += sessionName
            return Result.success(Unit)
        }

        override suspend fun killWindow(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
            windowIndex: Int,
        ): Result<WindowKillOutcome> {
            killedWindowTargets += "$sessionName:$windowIndex"
            if (!windowKillSucceeds) {
                return Result.failure(
                    RuntimeException("tmux window '$sessionName:$windowIndex' is still running."),
                )
            }
            return Result.success(
                WindowKillOutcome(sessionSurvived = windowKillSessionSurvived ?: false),
            )
        }
    }

    private class StopHostDao(private val hostId: Long) : HostDao {
        private val host = HostEntity(
            id = hostId,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "alex",
            keyId = 1L,
        )

        override fun getAll() = flowOf(listOf(host))
        override suspend fun getById(id: Long): HostEntity? = host.takeIf { it.id == id }
        override fun getEnabled() = flowOf(listOf(host))
        override suspend fun insert(host: HostEntity): Long = error("not used")
        override suspend fun update(host: HostEntity) = error("not used")
        override suspend fun delete(host: HostEntity) = error("not used")
        override suspend fun deleteById(id: Long) = error("not used")
    }
}
