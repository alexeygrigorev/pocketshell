package com.pocketshell.app.tmux

import android.os.Looper
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.app.session.SessionTab
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.testsupport.drainMainLooperUntil as drainMainLooperUntilShared
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionViewModelAgentTailTest : TmuxSessionViewModelTestBase() {
    @Test
    fun stoppedAgentLogTailMarksConversationStaleWhileTmuxStaysConnected() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        val tailJob = Job()
        vm.startAgentConversationForTest("%0", detection)

        val started = vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = tailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )

        assertNotNull(started)
        assertEquals(AgentConversationSyncStatus.Live, vm.agentConversations.value["%0"]!!.syncStatus)

        tailJob.complete()
        advanceUntilIdle()

        assertEquals(
            "normal tail exit means the conversation feed is stale, not that tmux disconnected",
            AgentConversationSyncStatus.Stale,
            vm.agentConversations.value["%0"]!!.syncStatus,
        )
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun codexNewReplayBurstCoalescesIntoFewStateFlowEmissionsNotPerLine() = runTest(scheduler) {
        val lineCount = 2_000
        val replay = (0 until lineCount).map { index ->
            """{"type":"user","uuid":"u$index","message":{"role":"user","content":"replayed $index"}}"""
        }
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            tailBatchWindowMillis = 50L,
        )
        val vm = newVm(agentRepository = repository)
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        vm.startAgentConversationForTest("%0", detection)

        var emissionCount = 0
        var lastEventCount = 0
        val collector = backgroundScope.launch {
            vm.agentConversations.collect { map ->
                map["%0"]?.let {
                    emissionCount += 1
                    lastEventCount = it.events.size
                }
            }
        }
        runCurrent()
        val emissionsAfterRegister = emissionCount

        vm.startAgentTailForTest(
            paneId = "%0",
            session = ReplayTailSshSession(replayLines = replay),
            detection = detection,
            fromLineExclusive = 0L,
        )
        advanceTimeBy(500L)
        runCurrent()
        collector.cancel()

        val burstEmissions = emissionCount - emissionsAfterRegister
        assertTrue(
            "expected a handful of burst emissions, got $burstEmissions for $lineCount replayed lines",
            burstEmissions in 1..5,
        )
        assertEquals(
            "batched ingest must produce the bounded feed, not drop to empty",
            500,
            lastEventCount,
        )
        val finalEvents = vm.agentConversations.value["%0"]!!.events
        assertEquals(500, finalEvents.size)
        assertEquals(
            "the most-recent replayed event must be present after coalesced ingest",
            "u${lineCount - 1}",
            finalEvents.last().id,
        )
        assertEquals(
            "the oldest surviving event is the start of the bounded window",
            "u${lineCount - 500}",
            finalEvents.first().id,
        )
    }

    @Test
    fun stoppedAgentLogTailPreservesConcurrentConversationState() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        val tailJob = Job()
        vm.startAgentConversationForTest("%0", detection)
        vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = tailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )

        vm.selectSessionTab("%0", SessionTab.Conversation)
        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.Message(
                    id = "assistant-late",
                    agent = AgentKind.ClaudeCode,
                    atMillis = 10L,
                    role = ConversationRole.Assistant,
                    text = "late event",
                ),
            ),
        )
        tailJob.complete()
        advanceUntilIdle()

        val after = vm.agentConversations.value["%0"]!!
        assertEquals(AgentConversationSyncStatus.Stale, after.syncStatus)
        assertEquals(SessionTab.Conversation, after.selectedTab)
        assertEquals("assistant-late", after.events.single().id)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun issue1168AgentTailScopeLeaksALiveDrainThatCancelJoinDrains() {
        val probeTailScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = AgentConversationRepository(tailScope = probeTailScope)
            val vm = newVm(agentRepository = repository)
            vm.attachClientForTest(FakeTmuxClient())
            val detection = newClaudeDetection()
            vm.startAgentConversationForTest("%0", detection)
            val umbrella = vm.startAgentTailForTest(
                paneId = "%0",
                session = FakeSshSession(tailJob = Job()),
                detection = detection,
                fromLineExclusive = 0L,
            )
            assertNotNull("the follow tail must start (umbrella job non-null)", umbrella)

            val liveChildren = probeTailScope.coroutineContext.job.children
                .filter { it.isActive }
                .toList()
            assertTrue(
                "expected a live tail-drain coroutine on the real-IO tailScope " +
                    "(the un-joined #1168 leak), found none",
                liveChildren.isNotEmpty(),
            )

            probeTailScope.cancel()
            runBlocking {
                withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
                    probeTailScope.coroutineContext.job.children.forEach { it.join() }
                }
            }
            val survivors = probeTailScope.coroutineContext.job.children
                .filter { it.isActive }
                .toList()
            assertTrue(
                "cancel-then-join must drain every tail coroutine before " +
                    "resetMain; survivors=$survivors",
                survivors.isEmpty(),
            )
        } finally {
            probeTailScope.cancel()
        }
    }

    @Test
    fun staleOldAgentLogTailCompletionDoesNotMarkRestartedPaneStale() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        val oldTailJob = Job()
        val restartedTailJob = Job()
        vm.startAgentConversationForTest("%0", detection)

        vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = oldTailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )
        vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = restartedTailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )

        oldTailJob.complete()
        advanceUntilIdle()

        assertEquals(
            "old tail completion must not stale a pane that already has a newer tail",
            AgentConversationSyncStatus.Live,
            vm.agentConversations.value["%0"]!!.syncStatus,
        )

        restartedTailJob.complete()
        advanceUntilIdle()

        assertEquals(AgentConversationSyncStatus.Stale, vm.agentConversations.value["%0"]!!.syncStatus)
    }

    @Test
    fun failedAgentLogTailMarksConversationLogUnavailableWhileTmuxStaysConnected() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        val tailJob = Job()
        vm.startAgentConversationForTest("%0", detection)
        vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = tailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )

        tailJob.completeExceptionally(IOException("tail failed"))
        advanceUntilIdle()

        assertEquals(
            AgentConversationSyncStatus.LogUnavailable,
            vm.agentConversations.value["%0"]!!.syncStatus,
        )
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun disconnectedSshWhenAgentTailStartsMarksConversationUnavailableWithoutCrashing() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()

        try {
            vm.startAgentConversationForPaneForTest(
                paneId = "%0",
                session = FakeSshSession(
                    tailFailure = SshException("SSH session is not connected"),
                ),
                detection = detection,
            )
            advanceUntilIdle()

            val state = vm.agentConversations.value["%0"]
            assertEquals(AgentConversationSyncStatus.LogUnavailable, state?.syncStatus)
            assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
            val event = diagnostics.eventsNamed("tmux_agent_conversation_tail_status").single()
            assertEquals("recoverable", event.category)
            assertEquals("%0", event.fields["pane"])
            assertEquals("tail_start_unavailable", event.fields["reason"])
            assertEquals(AgentConversationSyncStatus.LogUnavailable.name, event.fields["status"])
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun cancelledAgentConversationStartupReadIsNotSwallowed() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()

        try {
            vm.startAgentConversationForPaneForTest(
                paneId = "%0",
                session = FakeSshSession(
                    execFailure = CancellationException("cancelled during line count"),
                ),
                detection = detection,
            )
            assertTrue("startup cancellation must propagate", false)
        } catch (e: CancellationException) {
            assertEquals("cancelled during line count", e.message)
        }
        assertTrue(
            "cancelled startup must not leave a phantom conversation row",
            vm.agentConversations.value.isEmpty(),
        )
    }

    @Test
    fun retryAgentLogTailForPaneRestartsOneTailAndKeepsTmuxConnected() = runTest(scheduler) {
        val vm = newVm()
        val detection = newClaudeDetection()
        val oldTailJob = Job()
        val retryTailJob = Job()
        val recentMtimeSeconds = System.currentTimeMillis() / 1000
        val retryGate = CompletableDeferred<Unit>()
        val retrySession = FakeSshSession(
            tailJob = retryTailJob,
            execGate = retryGate,
            detectionOutput = "claude|$recentMtimeSeconds|/work|/home/u/.claude/projects/-work/abc.jsonl\n",
            processOutput = "123 1 pts/1 node claude\n",
        )
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "agent",
                    paneIndex = 0,
                    cwd = "/work",
                    currentCommand = "claude",
                    paneTty = "/dev/pts/1",
                ),
            ),
        )
        vm.attachSessionForAgentRetryForTest(retrySession)
        vm.startAgentConversationForTest("%0", detection)
        vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = oldTailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )
        oldTailJob.complete()
        advanceUntilIdle()

        assertTrue(vm.retryAgentConversationStreamForPane("%0"))
        assertEquals(
            AgentConversationSyncStatus.Retrying,
            vm.agentConversations.value["%0"]!!.syncStatus,
        )
        assertFalse(
            "duplicate retry must not start a second pane tail",
            vm.retryAgentConversationStreamForPane("%0"),
        )

        retryGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, retrySession.tailCalls)
        assertEquals(AgentConversationSyncStatus.Live, vm.agentConversations.value["%0"]!!.syncStatus)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun codexAgentLogRetryWithTerminalFloodKeepsConversationAndTerminalConsistent() =
        runTest(scheduler, timeout = SLOW_FEED_RUN_TEST_TIMEOUT) {
            val vm = newVm()
            val client = FakeTmuxClient()
            val detection = newCodexDetection()
            val oldTailJob = Job()
            val retryTailJob = Job()
            val retryGate = CompletableDeferred<Unit>()
            val recentMtimeSeconds = System.currentTimeMillis() / 1000
            val codexLines = codexTranscriptWithToolFlood(toolResults = 700)
            val retrySession = FakeSshSession(
                tailJob = retryTailJob,
                execGate = retryGate,
                wcOutput = "${codexLines.size}\n",
                agentLogLines = codexLines,
                detectionOutput = "codex|$recentMtimeSeconds|/work|${detection.sourcePath}\n",
                processOutput = "123 1 pts/1 codex codex --synthetic-overflow\n",
            )
            vm.attachClientForTest(client)
            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        paneId = "%0",
                        windowId = "@0",
                        sessionId = "$0",
                        title = "codex",
                        paneIndex = 0,
                        cwd = "/work",
                        currentCommand = "codex",
                        paneTty = "/dev/pts/1",
                    ),
                ),
            )
            vm.attachSessionForAgentRetryForTest(retrySession)
            vm.startAgentConversationForTest("%0", detection)
            vm.startAgentTailForTest(
                paneId = "%0",
                session = FakeSshSession(tailJob = oldTailJob),
                detection = detection,
                fromLineExclusive = 0L,
            )
            oldTailJob.complete()
            advanceUntilIdle()

            val terminalState = vm.panes.value.single().terminalState
            val payloads = List(CODEX_SCALE_OUTPUT_CHUNKS, ::codexScaleOutputChunk)
            val emitted = payloads.sumOf { it.size }
            assertTrue(
                "test fixture drift: emitted=$emitted expectedFloor=$CODEX_SCALE_OUTPUT_BYTES",
                emitted >= CODEX_SCALE_OUTPUT_BYTES,
            )
            val observedSideChannelChunks = AtomicInteger(0)
            val outputCollector = launch {
                terminalState.output.collect {
                    observedSideChannelChunks.incrementAndGet()
                }
            }
            runCurrent()

            try {
                assertTrue(vm.retryAgentConversationStreamForPane("%0"))
                val sender = async {
                    payloads.forEach { bytes ->
                        client.emittedEvents.emit(ControlEvent.Output("%0", bytes))
                    }
                }
                val completed = drainMainLooperUntil { !sender.isActive }
                if (completed) sender.await() else sender.cancel()

                assertTrue(
                    "Codex-scale terminal output must not stall while Conversation retries",
                    completed,
                )
                assertTrue(
                    "Codex-scale terminal output should still publish best-effort side-channel chunks",
                    observedSideChannelChunks.get() > 0,
                )
            } finally {
                outputCollector.cancel()
            }

            retryGate.complete(Unit)
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()

            val state = vm.agentConversations.value["%0"]!!
            val messages = state.events.filterIsInstance<ConversationEvent.Message>()
            assertEquals(AgentConversationSyncStatus.Live, state.syncStatus)
            assertTrue(
                "widened Codex agent-log read must keep the user prompt that is outside the old 200-line tail",
                messages.any { it.role == ConversationRole.User && it.text == ISSUE_576_CODEX_USER_PROMPT },
            )
            assertTrue(
                "assistant response from the synthetic Codex transcript should also be present",
                messages.any { it.role == ConversationRole.Assistant && it.text == ISSUE_576_CODEX_ASSISTANT_REPLY },
            )
            assertTrue(
                "Codex conversation overflow must not be classified as a tmux disconnect",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertFalse(client.disconnectedSignal.value)
            assertTrue(
                "Codex retry must request the widened raw-line window; commands=${retrySession.execCommands}",
                retrySession.execCommands.any { it.contains("pocketshell agent-log") && it.contains("--tail 1600") },
            )
        }

    @Test
    fun retryAgentLogTailForPaneDetectionFailureReturnsLogUnavailable() = runTest(scheduler) {
        val vm = newVm()
        val detection = newClaudeDetection()
        val retryGate = CompletableDeferred<Unit>()
        val retrySession = FakeSshSession(execGate = retryGate)
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "agent",
                    paneIndex = 0,
                    cwd = "/work",
                    currentCommand = "claude",
                    paneTty = "/dev/pts/1",
                ),
            ),
        )
        vm.attachSessionForAgentRetryForTest(retrySession)
        vm.startAgentConversationForTest("%0", detection)
        val oldTailJob = Job()
        vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = oldTailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )
        oldTailJob.complete()
        advanceUntilIdle()

        assertTrue(vm.retryAgentConversationStreamForPane("%0"))
        assertEquals(
            AgentConversationSyncStatus.Retrying,
            vm.agentConversations.value["%0"]!!.syncStatus,
        )
        retryGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            AgentConversationSyncStatus.LogUnavailable,
            vm.agentConversations.value["%0"]!!.syncStatus,
        )
        assertEquals(0, retrySession.tailCalls)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    private fun TestScope.drainMainLooperUntil(
        deadlineMs: Long = SLOW_FEED_DRAIN_TIMEOUT_MS,
        condition: () -> Boolean,
    ): Boolean =
        drainMainLooperUntilShared(
            deadlineMs = deadlineMs,
            sleepMs = 20L,
            onTick = {
                shadowOf(Looper.getMainLooper()).idleFor(16L, TimeUnit.MILLISECONDS)
                runCurrent()
            },
            condition = condition,
        )

    private fun newClaudeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/home/u/.claude/sessions/abc.jsonl",
        sessionId = "abc",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun newCodexDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.Codex,
        sourcePath = "/home/u/.codex/sessions/xyz.jsonl",
        sessionId = "xyz",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun codexScaleOutputChunk(index: Int): ByteArray {
        val linePrefix = "codex-overload-${index.toString().padStart(4, '0')}"
        val line = "$linePrefix " + "x".repeat(240) + "\r\n"
        return buildString {
            repeat(CODEX_SCALE_OUTPUT_LINES_PER_CHUNK) { append(line) }
        }.toByteArray(Charsets.UTF_8)
    }

    private fun codexTranscriptWithToolFlood(toolResults: Int): List<String> = buildList {
        add("""{"type":"session_meta","payload":{"id":"xyz","cwd":"/work"}}""")
        add(
            """{"id":"issue-576-user","type":"event_msg","timestamp":"2026-06-06T15:15:00Z","payload":{"type":"user_message","message":${JSONObject.quote(ISSUE_576_CODEX_USER_PROMPT)}}}""",
        )
        repeat(toolResults) { index ->
            add(
                """{"type":"response_item","payload":{"type":"function_call_output","call_id":"issue-576-call-$index","output":${JSONObject.quote("terminal chunk $index " + "x".repeat(900))}}}""",
            )
        }
        add(
            """{"type":"response_item","payload":{"type":"message","id":"issue-576-assistant","role":"assistant","content":[{"type":"output_text","text":${JSONObject.quote(ISSUE_576_CODEX_ASSISTANT_REPLY)}}]}}""",
        )
    }

    private class FakeSshSession(
        private val tailJob: CompletableJob = Job(),
        private val execGate: CompletableDeferred<Unit>? = null,
        private val wcOutput: String = "0\n",
        private val agentLogLines: List<String>? = null,
        private val execFailure: Throwable? = null,
        private val tailFailure: Throwable? = null,
        private val detectionOutput: String = "",
        private val processOutput: String = "",
    ) : SshSession {
        val execCommands: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

        var tailCalls: Int = 0
            private set

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            execGate?.await()
            execFailure?.let { throw it }
            val stdout = when {
                command.contains("@@PS_WINDOW@@") ->
                    "${wcOutput.trim()}\n@@PS_WINDOW@@\n"
                command.contains("@@PS_CODEX_WINDOW@@") ->
                    "${wcOutput.trim()}\n@@PS_CODEX_WINDOW@@\n${agentLogEnvelope(command).orEmpty()}"
                command.contains("wc -l < ") -> wcOutput
                command.contains("pocketshell agent-log") -> agentLogEnvelope(command).orEmpty()
                command.startsWith("tail -n ") -> ""
                command.contains("ps -eo pid,ppid,tty,comm,args") -> processOutput
                command.contains(".claude") ||
                    command.contains(".codex") ||
                    command.contains("opencode") -> detectionOutput
                else -> ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        private fun agentLogEnvelope(command: String): String? {
            val lines = agentLogLines ?: return null
            val tail = Regex("""--tail\s+(\d+)""")
                .find(command)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            val selected = if (tail == null || tail <= 0 || tail >= lines.size) {
                lines
            } else {
                lines.takeLast(tail)
            }
            return JSONObject(
                mapOf(
                    "count" to selected.size,
                    "engine" to "codex",
                    "lines" to JSONArray(selected),
                    "path" to "/home/u/.codex/sessions/xyz.jsonl",
                    "session" to "xyz",
                ),
            ).toString()
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job {
            tailCalls += 1
            tailFailure?.let { throw it }
            return tailJob
        }

        override fun tail(
            path: String,
            fromLineExclusive: Long,
            onLine: (String) -> Unit,
        ): Job {
            tailCalls += 1
            tailFailure?.let { throw it }
            return tailJob
        }

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

    private class ReplayTailSshSession(
        private val replayLines: List<String>,
    ) : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            val stdout = if (command.contains("wc -l < ")) "0\n" else ""
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job {
            replayLines.forEach(onLine)
            return Job()
        }

        override fun tail(
            path: String,
            fromLineExclusive: Long,
            onLine: (String) -> Unit,
        ): Job {
            replayLines.forEach(onLine)
            return Job()
        }

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

    private companion object {
        const val SLOW_FEED_DRAIN_TIMEOUT_MS = 30_000L
        val SLOW_FEED_RUN_TEST_TIMEOUT = 120.seconds

        const val CODEX_SCALE_OUTPUT_CHUNKS = 320
        const val CODEX_SCALE_OUTPUT_LINES_PER_CHUNK = 20
        const val CODEX_SCALE_OUTPUT_BYTES = 1_500_000
        const val ISSUE_576_CODEX_USER_PROMPT = "issue 576 synthetic Codex prompt before tool flood"
        const val ISSUE_576_CODEX_ASSISTANT_REPLY = "issue 576 synthetic Codex final reply"
    }
}
