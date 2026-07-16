package com.pocketshell.app.tmux

import com.pocketshell.app.session.OPTIMISTIC_USER_MESSAGE_ID_PREFIX
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.agents.MessageSendState
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.TmuxOutputBacklogOverflow
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Default in-JVM stress-loop floor for both reconnect-send variants. #1615
// established the agent guard; #1617 isolates the shared #1168 collaborator
// roots and adds the payload guard at the same per-push floor. Override with
// `PS_STRESS_COUNT` for a heavier local soak.
private const val STRESS_ITERATIONS = 50

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionAgentSendTest : TmuxSessionViewModelTestBase() {
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

    private fun FakeTmuxClient.withSinglePane(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    private class QueueLeaseConnector(
        private vararg val sessions: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = sessions.getOrNull(connectCount)
                ?: error("unexpected lease connect $connectCount for ${target.leaseKey}")
            connectCount += 1
            return Result.success(next)
        }
    }

    private class FailingLeaseConnector(
        private val failure: Throwable,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.failure(failure)
        }
    }

    private class UserAuthException(message: String) : Exception(message)

    private class FakeSshSession(
        private val isConnectedValue: Boolean = true,
    ) : SshSession {
        @Volatile
        private var closed: Boolean = false

        override val isConnected: Boolean
            get() = isConnectedValue && !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError("not needed")

        override fun startShell(): SshShell = throw NotImplementedError("not needed")

        override suspend fun uploadFile(file: File, remotePath: String): String = remotePath

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = remotePath

        override fun close() {
            closed = true
        }
    }

    @Test
    fun sendToAgentPaneAppendsOptimisticMessageAndWritesCarriageReturn() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        vm.sendToAgentPane("%0", "  run tests  ")
        advanceUntilIdle()

        val state = vm.agentConversations.value["%0"]!!
        val optimistic = state.events.single() as ConversationEvent.Message
        assertTrue(optimistic.id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX))
        assertEquals(ConversationRole.User, optimistic.role)
        assertEquals("run tests", optimistic.text)
        assertEquals(AgentKind.ClaudeCode, optimistic.agent)
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'run tests'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    @Test
    fun sendToAgentPaneExitsTmuxCopyModeBeforeTypingPrompt() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "claude",
                    paneIndex = 0,
                    inCopyMode = true,
                ),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        // Issue #869: confirm the paste landed so the ack-gate submits promptly.
        repeat(4) {
            client.capturePaneResponses.addLast(
                CommandResponse(number = 0L, output = listOf("> run tests"), isError = false),
            )
        }

        val send = async { vm.sendToAgentPaneResult("%0", "  run tests  ") }
        advanceUntilIdle()
        val result = send.await()

        assertTrue("agent prompt should be delivered after copy-mode recovery", result.isSuccess)
        assertFalse("copy-mode recovery must not mark tmux disconnected", client.disconnected.value)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(
            listOf(
                "send-keys -X -t %0 cancel",
                "send-keys -l -t %0 -- 'run tests'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
        assertFalse(vm.panes.value.single { it.paneId == "%0" }.inCopyMode)
    }

    /**
     * Issue #869: Codex keeps its known-needed floor of
     * [CODEX_AGENT_SUBMIT_DELAY_MS] as the MINIMUM wait before the submit Enter
     * — even when a `capture-pane` confirms the paste instantly. The ack-gate
     * then presses Enter once that floor elapses AND a capture confirms the
     * paste (here the very first capture confirms it, so Enter fires right at
     * the floor).
     */
    @Test
    fun codexAgentSubmitHonoursMinimumFloorThenAckGatesEnter() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newCodexDetection())

        // Capture confirms the paste immediately — but the Codex floor must
        // still gate the Enter so the TUI has its known-needed minimum.
        repeat(4) {
            client.capturePaneResponses.addLast(
                CommandResponse(number = 0L, output = listOf("> run tests"), isError = false),
            )
        }

        val send = async { vm.sendToAgentPaneResult("%0", "  run tests  ") }
        runCurrent()

        assertEquals(
            "Codex submit should type the prompt before waiting to press Enter",
            listOf("send-keys -l -t %0 -- 'run tests'"),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )

        advanceTimeBy(CODEX_AGENT_SUBMIT_DELAY_MS - 1L)
        runCurrent()
        assertEquals(
            "Codex submit must not press Enter before the Codex floor elapses, " +
                "even when the capture confirms the paste instantly",
            listOf("send-keys -l -t %0 -- 'run tests'"),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )

        advanceUntilIdle()
        assertTrue(send.await().isSuccess)
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'run tests'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    /**
     * Issue #869 (G10 reproduce-first): the maintainer's on-device symptom —
     * "most of the time when I click Send it's not really sending; I have to
     * press Enter after". Under realistic RTT the agent TUI has NOT finished
     * ingesting the pasted prompt when the blind ~150ms submit-delay timer
     * fires, so the Enter races the paste and the line sits unsent.
     *
     * This test models that latency: `capture-pane` reports the pane WITHOUT
     * the payload for the first few polls (the agent is still ingesting), then
     * WITH the payload once ingestion completes. The fix must press the submit
     * Enter ONLY after a capture confirms the paste landed.
     *
     * RED on the pre-#869 blind delay: no `capture-pane` is issued between the
     * text and the Enter, so the Enter is sent while the pane still shows no
     * payload — `enterSentWhilePayloadVisible` is false → the test fails.
     *
     * GREEN with the ack-gate: the Enter is deferred until a confirming
     * `capture-pane` returns the payload, including under the simulated latency.
     */
    @Test
    fun sendToAgentPaneAckGatesSubmitEnterUntilPasteIngestedUnderLatency() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val payload = "deploy the staging build"

        // Simulate the agent taking a few capture round-trips to render the
        // pasted text (realistic RTT ingestion). The first two captures show the
        // pane WITHOUT the payload; the third shows it WITH the payload landed.
        val notYetIngested = CommandResponse(
            number = 0L,
            output = listOf("> "),
            isError = false,
        )
        val ingested = CommandResponse(
            number = 0L,
            output = listOf("> $payload"),
            isError = false,
        )
        client.capturePaneResponses.addLast(notYetIngested)
        client.capturePaneResponses.addLast(notYetIngested)
        client.capturePaneResponses.addLast(ingested)
        // Plenty of confirming captures left over so a slightly different poll
        // count still confirms.
        repeat(8) { client.capturePaneResponses.addLast(ingested) }

        // Track, at the moment the submit Enter reaches the wire, whether a
        // capture has already confirmed the payload is visible in the pane.
        var sawConfirmingCapture = false
        var enterSentWhilePayloadVisible = false
        client.onCommandSent = { cmd ->
            // The next capture-pane queued response is the one this command will
            // consume; peek it to know if THIS capture confirms the payload.
            if (cmd.startsWith("capture-pane")) {
                val next = client.capturePaneResponses.firstOrNull()
                if (next != null && next.output.any { it.contains(payload) }) {
                    sawConfirmingCapture = true
                }
            }
            if (cmd == "send-keys -t %0 Enter") {
                enterSentWhilePayloadVisible = sawConfirmingCapture
            }
        }

        val send = async { vm.sendToAgentPaneResult("%0", payload) }
        advanceUntilIdle()

        assertTrue("agent submit should succeed", send.await().isSuccess)
        val sentSendKeys = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            "the prompt text is typed then submitted with a single Enter",
            listOf(
                "send-keys -l -t %0 -- 'deploy the staging build'",
                "send-keys -t %0 Enter",
            ),
            sentSendKeys,
        )
        // The load-bearing assertion: the submit Enter must only fire AFTER a
        // capture confirmed the paste is visible in the agent input. On the
        // pre-#869 blind delay there is no confirming capture, so the Enter
        // races ahead and this is false (RED).
        assertTrue(
            "submit Enter must be ack-gated on a capture confirming the paste landed " +
                "(the maintainer's missed-submit race under RTT)",
            enterSentWhilePayloadVisible,
        )
        // Prove the gate actually POLLED the pane (issued at least one
        // capture-pane) before pressing Enter — not a vacuous pass.
        assertTrue(
            "the ack-gate must poll capture-pane before submitting",
            client.sentCommands.any { it.startsWith("capture-pane -p -t %0") },
        )
    }

    @Test
    fun sendToAgentPaneAckCaptureBypassesWedgedBestEffortControlLane() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.setAgentSubmitMonotonicClockForTest { scheduler.currentTime }
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(1_000L)
        client.suspendForeverOnBestEffortCommandPrefix = "capture-pane"
        client.capturePaneResponses.addLast(
            CommandResponse(number = 0L, output = listOf("> fast ack prompt"), isError = false),
        )

        var enterSentAtMs = -1L
        client.onCommandSent = { cmd ->
            if (cmd == "send-keys -t %0 Enter" && enterSentAtMs < 0L) {
                enterSentAtMs = scheduler.currentTime
            }
        }

        val send = async { vm.sendToAgentPaneResult("%0", "fast ack prompt") }
        advanceUntilIdle()

        assertTrue("agent submit should succeed", send.await().isSuccess)
        assertEquals(listOf("%0"), client.capturePaneTextViaExecCalls)
        assertTrue(
            "submit ack capture must not wait for the wedged best-effort control lane",
            enterSentAtMs in 0 until 1_000L,
        )
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'fast ack prompt'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    /**
     * Issue #869: when the agent's input rendering can never be recognised (the
     * payload never shows up in `capture-pane`), Send must NOT hang — it falls
     * back to pressing Enter after the bounded ack timeout (the pre-#869 blind
     * behaviour as the worst case, never a deadlock).
     */
    @Test
    fun sendToAgentPaneFallsBackToSubmitEnterAfterAckTimeout() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        // Every capture comes back WITHOUT the payload — an unrecognised TUI
        // rendering. The FakeTmuxClient default empty capture already models
        // this, but make it explicit for clarity.
        repeat(200) {
            client.capturePaneResponses.addLast(
                CommandResponse(number = 0L, output = listOf("> "), isError = false),
            )
        }

        val send = async { vm.sendToAgentPaneResult("%0", "never-rendered prompt") }

        // Before the bounded timeout, the Enter must not have been pressed.
        advanceTimeBy(AGENT_SUBMIT_ACK_TIMEOUT_MS - 1L)
        runCurrent()
        assertFalse(
            "Enter must not be pressed before the bounded ack timeout elapses",
            client.sentCommands.contains("send-keys -t %0 Enter"),
        )

        advanceUntilIdle()
        assertTrue("submit must not hang — it falls back after the timeout", send.await().isSuccess)
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'never-rendered prompt'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    /**
     * Codex input-freeze follow-up: a Codex/agent output storm can wedge the
     * `capture-pane` command the submit ack gate uses to confirm the paste
     * landed. The ack timeout must bound the WHOLE capture loop, including a
     * single stuck capture command, so typing never parks forever before the
     * submit Enter.
     */
    @Test
    fun sendToAgentPaneAckTimeoutBoundsStuckCaptureCommand() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.setAgentSubmitMonotonicClockForTest { scheduler.currentTime }
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(80L)
        client.suspendForeverOnCapturePaneTextViaExec = true

        val send = async { vm.sendToAgentPaneResult("%0", "blocked capture prompt") }

        advanceTimeBy(79L)
        runCurrent()
        assertFalse(
            "the submit Enter must not fire before the ack timeout elapses",
            client.sentCommands.contains("send-keys -t %0 Enter"),
        )

        advanceUntilIdle()
        assertTrue("stuck capture must fall back instead of hanging Send", send.await().isSuccess)
        assertEquals(
            "a stuck ack capture should be tried once, then cancelled by the wall-clock timeout",
            1,
            client.capturePaneTextViaExecCalls.size,
        )
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'blocked capture prompt'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    /**
     * Issue #869 (reviewer BLOCKED-G4 follow-up): the needle-miss FALLBACK must
     * NOT degrade to the pre-#869 short floor — that short delay IS the
     * maintainer's missed-submit symptom. When the ack is never observed (an
     * unrecognised / reflowed input box the needle can't match) the submit Enter
     * must be held to an ADEQUATE working floor:
     *   max(configuredFloor, AGENT_SUBMIT_ACK_FALLBACK_FLOOR_MS + measuredRtt).
     *
     * This test injects a known per-`capture-pane` RTT and a ZERO configured
     * floor (so neither the Codex floor nor the #526 setting masks the assertion)
     * and proves the Enter does NOT fire until at least
     * `AGENT_SUBMIT_ACK_FALLBACK_FLOOR_MS + injectedRtt` of virtual time has
     * elapsed since the gate started — i.e. the worst case is a working delay,
     * proportionally larger under latency, never the 150ms that raced.
     *
     * RED (pre-hardening): the fallback returns after only the short floor, so
     * the Enter is present well before FALLBACK_FLOOR + RTT → the
     * "not pressed before the adequate floor" assertion fails.
     * GREEN (hardened): the Enter is held until the adequate floor elapses.
     */
    @Test
    fun sendToAgentPaneFallbackHoldsEnterForAdequateFloorPlusRttOnNeedleMiss() =
        runTest(scheduler) {
            val vm = newVm()
            val client = FakeTmuxClient()
            // Read the runTest virtual clock so the gate's RTT measurement +
            // fallback-floor top-up are deterministic (SystemClock reads 0 here).
            vm.setAgentSubmitMonotonicClockForTest { scheduler.currentTime }
            vm.attachClientForTest(client)
            vm.startAgentConversationForTest("%0", newClaudeDetection())
            // Zero configured floor so the fallback floor is the only thing
            // gating the Enter (not the #526 setting / Codex floor).
            vm.setAgentSubmitEnterDelayForTest(0)
            // SHORT poll window so the incidental poll-loop duration is BELOW the
            // fallback floor — making the floor (not the loop) the binding
            // constraint, so this is a genuine red→green of the floor itself.
            vm.setAgentSubmitAckTimeoutForTest(80L) // 2 polls at 40ms

            val injectedRttMs = 20L
            client.captureCommandDelayMs = injectedRttMs
            // Every capture comes back WITHOUT the payload — the needle never
            // matches (a reflowed/unrecognised input box).
            repeat(200) {
                client.capturePaneResponses.addLast(
                    CommandResponse(number = 0L, output = listOf("> "), isError = false),
                )
            }

            val gateStart = scheduler.currentTime
            // Record the virtual-clock instant the submit Enter reaches the wire.
            var enterSentAtMs: Long = -1L
            client.onCommandSent = { cmd ->
                if (cmd == "send-keys -t %0 Enter" && enterSentAtMs < 0L) {
                    enterSentAtMs = scheduler.currentTime
                }
            }
            val send = async { vm.sendToAgentPaneResult("%0", "never-matched prompt") }

            // The adequate floor: the Enter must NOT have fired before this.
            val adequateFloorMs =
                com.pocketshell.app.settings.AppSettings.AGENT_SUBMIT_ACK_FALLBACK_FLOOR_MS +
                    injectedRttMs
            advanceUntilIdle()
            assertTrue(
                "fallback submit must have fired (no hang)",
                send.await().isSuccess,
            )
            // The Enter was eventually pressed.
            assertTrue(
                "fallback must press the submit Enter",
                client.sentCommands.contains("send-keys -t %0 Enter"),
            )
            // Load-bearing: the submit Enter fired only AFTER the adequate floor
            // (FALLBACK_FLOOR + injectedRtt) elapsed — never the old short delay.
            val enterAtMs = enterSentAtMs - gateStart
            assertTrue(
                "needle-miss fallback must hold the submit Enter for at least the " +
                    "adequate floor (FALLBACK_FLOOR + measuredRtt = ${adequateFloorMs}ms); " +
                    "Enter fired after only ${enterAtMs}ms",
                enterAtMs >= adequateFloorMs,
            )
        }

    /**
     * Issue #869 (reviewer BLOCKED-G4 follow-up): the load-bearing needle-vs-
     * real-echo property — the ack must MATCH a WRAPPED/reflowed agent input box.
     * The on-device fixture proved a long prompt wraps and `capture-pane` joins
     * the rows with a separator that can land MID-WORD (`against the` rendered as
     * `against t` + `he new`), AND the head of a very long prompt scrolls off the
     * top so only the tail near the cursor is captured. A naive whole-line
     * substring needle MISSES both → fallback fires → Send degrades to the
     * maintainer's missed-submit. This JVM test reproduces that wrapped+truncated
     * capture deterministically and asserts the ack-gate STILL submits promptly
     * (the needle matches the whitespace-stripped tail).
     *
     * RED on the pre-fix whole-line/space-collapsed needle: the wrap-boundary
     * mid-word split means the collapsed needle is not a substring of the
     * collapsed visible text, so no capture ever confirms → the gate runs to the
     * fallback timeout instead of submitting on the first confirming capture.
     * GREEN with the tail+strip needle: the first wrapped capture confirms.
     */
    @Test
    fun sendToAgentPaneAckMatchesWrappedReflowedInputBox() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.setAgentSubmitMonotonicClockForTest { scheduler.currentTime }
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.setAgentSubmitEnterDelayForTest(0)

        // A long single-line prompt the composer types via `send-keys -l`.
        val payload = "please refactor the authentication middleware so that every " +
            "inbound request is validated against the new session token format " +
            "before it reaches the handler layer"

        // The agent input box renders the payload WRAPPED across rows AND with the
        // head scrolled off — exactly what `capture-pane -p` returned on-device.
        // The wrap boundary lands mid-word (`...against t` | `he new...`), and the
        // first rows of the prompt are gone (only the tail near the cursor shows).
        val wrappedTruncatedEcho = CommandResponse(
            number = 0L,
            output = listOf(
                "y inbound request is validated",
                " against the new session token",
                " format before it reaches the",
                "handler layer",
            ),
            isError = false,
        )
        // First capture: still ingesting (input box empty).
        client.capturePaneResponses.addLast(
            CommandResponse(number = 0L, output = listOf("> "), isError = false),
        )
        // Then the wrapped+truncated echo confirms the paste landed.
        repeat(10) { client.capturePaneResponses.addLast(wrappedTruncatedEcho) }

        // Track that the submit Enter is gated on an OBSERVED ack (not the
        // fallback). With the tail+strip needle the SECOND capture confirms, so
        // the gate submits well before the fallback timeout.
        val send = async { vm.sendToAgentPaneResult("%0", payload) }
        advanceUntilIdle()

        assertTrue("wrapped-echo agent submit should succeed", send.await().isSuccess)
        // The gate must have polled and matched the wrapped echo (ack observed),
        // not run to the fallback floor. The fallback would issue all ~50 polls;
        // an observed ack matches within the first couple. Assert the capture
        // count is small (ack matched early) — i.e. the needle DID match the
        // wrapped box, the load-bearing property.
        val captureCount = client.sentCommands.count { it.startsWith("capture-pane -p -t %0") }
        assertTrue(
            "the ack-gate must MATCH the wrapped/reflowed input box within a couple " +
                "of polls (needle survives the wrap-boundary split + head scroll-off); " +
                "captureCount=$captureCount implies it ran to the fallback timeout instead",
            captureCount in 1..5,
        )
        assertEquals(
            "the prompt is typed then submitted with a single Enter",
            listOf(
                "send-keys -l -t %0 -- '${payload.replace("'", "'\\''")}'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    @Test
    fun codexSendInFlightSurvivesTerminalOverflowWithoutReconnectOrDuplicateSend() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "codex",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        vm.startAgentConversationForTest("%0", newCodexDetection())

        val send = async { vm.sendToAgentPaneResult("%0", "  previous user prompt  ") }
        runCurrent()

        assertEquals(
            "precondition: Codex prompt text is typed once before delayed Enter",
            listOf("send-keys -l -t %0 -- 'previous user prompt'"),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )

        client.outputBacklogOverflowEvents.emit(
            TmuxOutputBacklogOverflow(paneId = "%0", droppedEvents = 2_048),
        )
        runCurrent()

        assertTrue(
            "terminal overflow must stay a pane-surface error, not a transport disconnect",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse("overflow must not flip the tmux disconnected signal", client.disconnected.value)
        assertEquals(
            "overflow must not start a reconnect or reacquire SSH",
            0,
            connector.connectCount,
        )
        assertEquals(
            "overflow must not increment user-visible connect attempts",
            0,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        assertSame(
            "stable transport should remain registered after pane overflow",
            client,
            registry.clients.value[7L]?.client,
        )

        advanceTimeBy(CODEX_AGENT_SUBMIT_DELAY_MS)
        assertTrue(send.await().isSuccess)

        val sendKeys = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            "overflow during the delayed Codex submit must not duplicate the composer prompt",
            1,
            sendKeys.count { it == "send-keys -l -t %0 -- 'previous user prompt'" },
        )
        assertEquals(1, sendKeys.count { it == "send-keys -t %0 Enter" })
        val messages = vm.agentConversations.value["%0"]!!.events
            .filterIsInstance<ConversationEvent.Message>()
            .filter { it.role == ConversationRole.User && it.text == "previous user prompt" }
        assertEquals("Conversation should keep one optimistic user turn", 1, messages.size)
        assertEquals(MessageSendState.Pending, messages.single().sendState)
        // Issue #1205: the overflow that fired mid-submit reseeds-and-reattaches
        // the pane instead of latching it into a dead surfaceError card — and it
        // must NOT disturb the in-flight Codex send (no reconnect, no duplicate
        // prompt, one optimistic turn), which the assertions above already prove.
        assertFalse(
            "Issue #1205: overflow during a delayed submit must reseed-and-reattach the " +
                "pane, not latch it into a dead surfaceError card",
            vm.panes.value.single().surfaceError,
        )
    }

    @Test
    fun agentSubmitDelaysFinalEnterByConfiguredDelayForClaudeCode() = runTest(scheduler) {
        // Issue #526: the composer/agent send path types the message text,
        // waits the user-configurable delay, then presses the submit Enter as
        // a SEPARATE send-keys so the Enter can't race ahead of the agent
        // TUI's paste ingestion (which left the message sitting unsent). This
        // applies to every agent now, not just Codex.
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.setAgentSubmitEnterDelayForTest(200)

        val send = async { vm.sendToAgentPaneResult("%0", "  run tests  ") }
        runCurrent()

        // Text is typed immediately; the submit Enter must NOT have been sent
        // yet — it waits out the configured delay first.
        assertEquals(
            "Send should type the prompt before waiting to press Enter",
            listOf("send-keys -l -t %0 -- 'run tests'"),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )

        advanceTimeBy(199L)
        runCurrent()
        assertEquals(
            "Submit Enter must not fire before the configured delay elapses",
            listOf("send-keys -l -t %0 -- 'run tests'"),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )

        advanceTimeBy(1L)
        assertTrue(send.await().isSuccess)
        assertEquals(
            "After the configured delay the submit Enter fires as a separate key",
            listOf(
                "send-keys -l -t %0 -- 'run tests'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    @Test
    fun agentSubmitWithZeroConfiguredFloorStillAckGatesEnter() = runTest(scheduler) {
        // Issue #869: a 0ms configured floor means "no minimum wait before
        // Enter" — but the submit is STILL ack-gated on the paste landing
        // (the #526 blind back-to-back behaviour was the missed-submit race the
        // maintainer hit). With a 0 floor and an immediately-confirming capture,
        // the Enter fires as soon as the first capture confirms the paste.
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.setAgentSubmitEnterDelayForTest(0)
        repeat(4) {
            client.capturePaneResponses.addLast(
                CommandResponse(number = 0L, output = listOf("> ship it"), isError = false),
            )
        }

        val send = async { vm.sendToAgentPaneResult("%0", "ship it") }
        advanceUntilIdle()
        val result = send.await()

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'ship it'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
        assertTrue(
            "even a 0 floor must poll capture-pane to confirm the paste before Enter",
            client.sentCommands.any { it.startsWith("capture-pane -p -t %0") },
        )
    }

    @Test
    fun rawPaneInputDoesNotUseCodexAgentSubmitDelay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newCodexDetection())

        vm.writeInputToPane("%0", "manual\r".toByteArray(Charsets.UTF_8))
        runCurrent()

        assertEquals(
            "manual pane input should keep the immediate text + Enter routing",
            listOf(
                "send-keys -l -t %0 -- 'manual'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    @Test
    fun sendToAgentPaneLongSingleLineUsesBoundedBracketedChunksThenEnter() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val draft = "x".repeat(TMUX_PASTE_BODY_CHUNK_BYTES * 3 + 17)
        // Issue #869: confirm the paste landed so the ack-gate submits promptly.
        repeat(4) {
            client.capturePaneResponses.addLast(
                CommandResponse(number = 0L, output = listOf("> $draft"), isError = false),
            )
        }
        val send = async { vm.sendToAgentPaneResult("%0", draft) }
        advanceUntilIdle()
        val result = send.await()

        assertTrue("expected long single-line send to succeed", result.isSuccess)
        // Issue #1636: the old assertions counted `send-keys -H` chunks — a wire-shape
        // proxy. What must hold is that a long draft goes through the bounded,
        // atomically-committed paste route and arrives BYTE-EXACT.
        val commands = client.sentCommands
        assertTrue(
            "long single-line draft must not create one unbounded literal command: $commands",
            commands.none { it.startsWith("send-keys -l") },
        )
        assertTrue(
            "expected a multi-chunk bounded paste-buffer fill, got $commands",
            commands.count { it.startsWith("set-buffer ") } > 3,
        )
        assertEquals("send-keys -t %0 Enter", commands.last())
        val longest = commands.maxOf { it.length }
        val maxExpectedCommandLength = TMUX_PASTE_BODY_CHUNK_BYTES + 64
        assertTrue(
            "tmux commands must stay bounded; longest=$longest max=$maxExpectedCommandLength",
            longest <= maxExpectedCommandLength,
        )
    }

    @Test
    fun sendToAgentPaneLongDictationWithAttachmentBlockSubmitsFinalEnter() = runTest(scheduler) {
        // Issue #569: a long dictated prompt plus staged attachment paths
        // must not stop at "text inserted into the agent TUI". The composed
        // prompt is pasted through bounded chunks and then submitted with the
        // separate Enter key.
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val payload = buildString {
            append("Please inspect the attached screenshot and explain why the agent did not submit. ")
            repeat(80) {
                append("This sentence represents a long dictation segment that should stay one prompt. ")
            }
            append("\n\nAttached files:\n")
            append("- ~/.pocketshell/attachments/host-1/issue-569-135736.png")
        }
        // Issue #869: confirm the paste landed (the ack-gate matches on the last
        // non-blank line of the payload — here the attachment path).
        repeat(4) {
            client.capturePaneResponses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf("> - ~/.pocketshell/attachments/host-1/issue-569-135736.png"),
                    isError = false,
                ),
            )
        }
        val send = async { vm.sendToAgentPaneResult("%0", payload) }
        advanceUntilIdle()
        val result = send.await()

        assertTrue("expected long dictation plus attachment send to succeed", result.isSuccess)
        // Issue #1636: bounded fill + ONE atomic commit + Enter (was: hex chunk counts).
        val commands = client.sentCommands
        assertTrue(
            "combined dictation/attachment prompt must use a bounded paste-buffer fill, got $commands",
            commands.count { it.startsWith("set-buffer ") } > 3,
        )
        assertTrue(
            "combined prompt must not use one unbounded literal send-keys command: $commands",
            commands.none { it.startsWith("send-keys -l") },
        )
        assertEquals(
            "combined prompt must reach the pane through exactly ONE commit",
            1,
            commands.count { it.startsWith("paste-buffer ") },
        )
        assertEquals(
            "combined prompt must be submitted after the paste commit",
            "send-keys -t %0 Enter",
            commands.last(),
        )
        val longest = commands.maxOf { it.length }
        val maxExpectedCommandLength = TMUX_PASTE_BODY_CHUNK_BYTES + 64
        assertTrue(
            "tmux commands must stay bounded; longest=$longest max=$maxExpectedCommandLength",
            longest <= maxExpectedCommandLength,
        )
    }

    @Test
    fun sendToAgentPaneResultFailureDuringLargePasteKeepsDraftOnlyAndReconnectAvailable() = runTest(scheduler) {
        val vm = newVm()
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val client = FakeTmuxClient().apply {
            // Issue #1636: the multi-chunk paste's wire commands are the buffer fill.
            closeAndThrowOnCommandPrefix = "set-buffer"
            closeAndThrowException = TmuxClientException("failed to write tmux command `set-buffer`")
        }
        vm.replaceClientForTest(
            hostId = 42L,
            hostName = "dev",
            host = "dev.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/dev",
            sessionName = "work",
            client = client,
        )
        runCurrent()
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val result = vm.sendToAgentPaneResult(
            "%0",
            "line one\n" + "x".repeat(TMUX_PASTE_BODY_CHUNK_BYTES * 2),
        )
        runCurrent()

        assertTrue("expected forced send failure", result.isFailure)
        assertTrue("forced failure should close fake client", client.closed)
        assertTrue("Reconnect must remain available after paste disconnect", vm.canReconnect.value)
        assertTrue(
            "composer keeps the draft, so tmux delivery failure must not also leave a failed row",
            vm.agentConversations.value["%0"]!!.events.isEmpty(),
        )
    }

    @Test
    fun sendToAgentPaneResultPasteChunkTmuxErrorRemovesOptimisticMessage() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            responses.addLast(CommandResponse(number = 1L, output = emptyList(), isError = false))
            responses.addLast(
                CommandResponse(
                    number = 2L,
                    output = listOf("not enough arguments"),
                    isError = true,
                ),
            )
        }
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val result = vm.sendToAgentPaneResult(
            "%0",
            "x".repeat(TMUX_PASTE_BODY_CHUNK_BYTES * 2 + 1),
        )
        runCurrent()

        assertTrue("expected tmux %error to fail the paste result", result.isFailure)
        assertTrue(
            "composer keeps failed draft; conversation must drop the temporary optimistic row",
            vm.agentConversations.value["%0"]!!.events.isEmpty(),
        )
        assertTrue(
            "failed paste must stop before submitting Enter: ${client.sentCommands}",
            client.sentCommands.none { it == "send-keys -t %0 Enter" },
        )
    }

    @Test
    fun sendToAgentPaneResultFinalEnterTmuxErrorRemovesOptimisticMessage() = runTest(scheduler) {
        val vm = newVm()
        // Issue #1636: target the Enter by COMMAND rather than by position in the
        // canned-response FIFO — the paste's command count is an implementation
        // detail of the paste route, and pinning the error to an index made this
        // test assert that detail instead of the behaviour under test.
        val client = FakeTmuxClient().apply {
            errorOnCommandPrefix = "send-keys -t %0 Enter"
            errorOnCommandRemaining = 1
            errorOnCommandOutput = listOf("can't find pane: %0")
        }
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        val draft = "x".repeat(TMUX_PASTE_BODY_CHUNK_BYTES * 2 + 1)
        // Issue #869: confirm the paste so the ack-gate reaches the (failing)
        // submit Enter promptly rather than burning the bounded ack timeout.
        repeat(4) {
            client.capturePaneResponses.addLast(
                CommandResponse(number = 0L, output = listOf("> $draft"), isError = false),
            )
        }

        val send = async { vm.sendToAgentPaneResult("%0", draft) }
        advanceUntilIdle()
        val result = send.await()

        assertTrue("expected tmux %error from final Enter to fail the send result", result.isFailure)
        assertTrue(
            "composer keeps failed draft; conversation must drop the temporary optimistic row",
            vm.agentConversations.value["%0"]!!.events.isEmpty(),
        )
        assertEquals(
            "send-keys -t %0 Enter",
            client.sentCommands.filter { it.startsWith("send-keys") }.last(),
        )
    }

    @Test
    fun sendToAgentPaneBlankTextIsNoOp() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        vm.sendToAgentPane("%0", "   ")
        advanceUntilIdle()

        assertTrue(vm.agentConversations.value["%0"]!!.events.isEmpty())
        assertTrue(client.sentCommands.none { it.startsWith("send-keys") })
    }

    @Test
    fun sendToAgentPaneResultDoesNotAppendOptimisticMessageWhenReconnectCannotStart() = runTest(scheduler) {
        // Issue #548 follow-up: if send-time reconnect cannot even start
        // (no remembered target), the unified composer keeps the draft.
        // Do not also append a failed optimistic row, or the next
        // successful send can show the same text twice.
        val vm = newVm()
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val result = vm.sendToAgentPaneResult("%0", "preserve this prompt")
        runCurrent()

        assertTrue("disconnected tmux agent send must report failure", result.isFailure)
        assertTrue(vm.agentConversations.value["%0"]!!.events.isEmpty())
    }

    @Test
    fun sendToAgentPaneResultReconnectsAndSendsWhenDisconnectedRecoverable() = runTest(scheduler) {
        runReconnectRecoverableAgentSendScenario()
    }

    @Test
    fun sendAgentPayloadToPaneResultReconnectsAndSendsWhenDisconnectedRecoverable() = runTest(scheduler) {
        runReconnectRecoverablePayloadSendScenario()
    }

    /**
     * Issue #1617: run the exact payload single-shot body at the acceptance
     * floor. Every iteration owns and drains its factory/tail/teardown scope and
     * VM roots, so this guard detects a return to class-wide real-IO coupling.
     */
    @Test
    fun sendAgentPayloadReconnectRecoverableDrainsDeterministicallyUnderStress() = runTest(scheduler) {
        val iterations = System.getenv("PS_STRESS_COUNT")?.toIntOrNull() ?: STRESS_ITERATIONS
        repeat(iterations) {
            runReconnectRecoverablePayloadSendScenario()
        }
    }

    private suspend fun TestScope.runReconnectRecoverablePayloadSendScenario() {
        val scenarioScope = newReconnectScenarioScope()
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%0")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(
                connector = connector,
                scope = scenarioScope,
                idleTtlMillis = 0L,
            ),
            agentRepository = AgentConversationRepository(tailScope = scenarioScope),
            tmuxClientFactory = TmuxClientFactory(scenarioScope),
        )
        // Issue #1110: the send-triggered reconnect REPLACES the disconnected
        // client, and the displaced client's runtime is torn down on the off-Main
        // teardown scope ([deferConnectionTeardownOffMain]). The default scope is a
        // real `Dispatchers.IO` ([defaultTeardownScope]); that real worker races the
        // virtual-clock reconnect/send the test drives, so the load-bearing
        // so the load-bearing reconnect+send assertions flaked under CI contention
        // (the `:11824` lambda-line failure). Pin the teardown to the SHARED
        // virtual-clock scheduler (the established #1085 pattern below) so the close
        // reconnect/send assertion drains deterministically with no real-IO race.
        //
        // Issue #1617: unlike the former class-wide #1168 roots, scenarioScope
        // is virtual, scenario-owned, and drained below before another reconnect
        // scenario can start.
        vm.setTeardownScopeForTest(scenarioScope)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "codex",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )

        deadClient.disconnectedSignal.value = true
        runCurrent()

        val send = async {
            vm.sendAgentPayloadToPaneResult("%0", "codex terminal send", AgentKind.Codex)
        }
        awaitReconnectSendCompletion(send)
        val result = send.await()

        assertTrue(
            "Codex Terminal-tab Send+Enter must reconnect before send: " +
                "failure=${result.exceptionOrNull()} status=${vm.connectionStatus.value} " +
                "connectCount=${connector.connectCount} " +
                "registryClient=${registry.clients.value[7L]?.client}",
            result.isSuccess,
        )
        assertEquals(1, connector.connectCount)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'codex terminal send'",
                "send-keys -t %0 Enter",
            ),
            reconnectClient.sentCommands.filter { it.startsWith("send-keys") },
        )
        drainReconnectScenario(vm, scenarioScope)
    }

    /**
     * Issue #1615 (test-infra determinism — AGENT variant, #1614 flake): the
     * `sendToAgentPaneResult` reconnect-recoverable send coupled the REAL off-Main
     * teardown dispatcher (the #1110
     * [TmuxSessionViewModel.deferConnectionTeardownOffMain] path) into a
     * virtual-time `runTest`. The send-triggered reconnect REPLACES the
     * disconnected client and the displaced client's runtime tears down on the
     * teardown scope; when that scope is a real `Dispatchers.IO` worker (the base
     * `defaultTeardownScope`) it races the virtual clock the assertions drive with
     * `advanceUntilIdle()`, so the load-bearing reconnect+send ordering
     * intermittently hadn't drained when the clock advanced — a spurious `Unit
     * tests` red on unrelated PR #1614 (this variant at :1081).
     *
     * The fix is test determinism only (no production behaviour change): the
     * agent scenario pins factory, agent-tail, and teardown work to one
     * scenario-owned virtual scope, then clears the VM and drains both its own
     * coroutine roots and that collaborator scope before returning.
     *
     * This stress guard runs the EXACT same scenario body as the single-shot
     * `sendToAgentPaneResultReconnectsAndSendsWhenDisconnectedRecoverable` test
     * above (shared helper) [STRESS_ITERATIONS]× so a returning real-IO teardown
     * race surfaces as a hard failure in the per-push Unit gate. It is proven
     * stable at this floor (co-run + solo 50×, 0 flakes; reviewer saw the agent
     * scenario pass at 300× under CPU saturation, and FAIL at 300× only when the
     * seam is removed).
     *
     * Issue #1617 applies the same isolation boundary to this sibling and the
     * payload variant, so neither guard can leave work behind for the other.
     */
    @Test
    fun sendToAgentPaneReconnectRecoverableDrainsDeterministicallyUnderStress() = runTest(scheduler) {
        val iterations = System.getenv("PS_STRESS_COUNT")?.toIntOrNull() ?: STRESS_ITERATIONS
        repeat(iterations) {
            runReconnectRecoverableAgentSendScenario()
        }
    }

    private suspend fun TestScope.runReconnectRecoverableAgentSendScenario() {
        val scenarioScope = newReconnectScenarioScope()
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%0")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(
                connector = connector,
                scope = scenarioScope,
                idleTtlMillis = 0L,
            ),
            agentRepository = AgentConversationRepository(tailScope = scenarioScope),
            tmuxClientFactory = TmuxClientFactory(scenarioScope),
        )
        // Issue #1615: pin the off-Main connection-teardown scope to the SHARED
        // virtual-clock scheduler (a StandardTestDispatcher on `scheduler`) so the
        // send-triggered reconnect's displaced-client teardown
        // ([TmuxSessionViewModel.deferConnectionTeardownOffMain]) drains on the
        // scenario scheduler instead of racing a real `Dispatchers.IO` worker
        // (the base default is `defaultTeardownScope`, real IO). Without this the
        // real teardown thread races the virtual-clock reconnect/send and the
        // load-bearing assertions flaked on CI (PR #1614, this variant at :1081).
        vm.setTeardownScopeForTest(scenarioScope)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "claude",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)

        deadClient.disconnectedSignal.value = true
        runCurrent()
        assertTrue(
            "precondition: passive EOF should surface a recoverable disconnected state",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )

        val send = async { vm.sendToAgentPaneResult("%0", "send after return") }
        awaitReconnectSendCompletion(send)
        val result = send.await()

        assertTrue("send should reconnect and deliver instead of dead-ending", result.isSuccess)
        assertEquals(1, connector.connectCount)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'send after return'",
                "send-keys -t %0 Enter",
            ),
            reconnectClient.sentCommands.filter { it.startsWith("send-keys") },
        )
        val pending = vm.agentConversations.value["%0"]!!.events.single() as ConversationEvent.Message
        assertEquals("send after return", pending.text)
        assertEquals(MessageSendState.Pending, pending.sendState)
        drainReconnectScenario(vm, scenarioScope)
    }

    /**
     * Issue #1617: one collaborator scope per reconnect scenario/iteration.
     * Both the TmuxClientFactory reader root and AgentConversationRepository
     * tail root use the same virtual scheduler as the scenario, so no real-IO
     * child can accumulate behind the next payload/agent reconnect assertion.
     */
    private fun newReconnectScenarioScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

    /**
     * Quiesce the scenario at its boundary rather than deferring all VMs and
     * collaborator roots to the class-level @After. This is load-bearing for
     * the 50x guards: iteration N+1 starts with zero live work from iteration N.
     */
    private fun TestScope.drainReconnectScenario(
        vm: TmuxSessionViewModel,
        scenarioScope: CoroutineScope,
    ) {
        vm.clearForTest()
        runCurrent()
        scenarioScope.cancel()
        val drainDeadline = System.currentTimeMillis() + 5_000L
        while (
            vm.activeOwnScopeChildCountForTest() > 0 &&
            System.currentTimeMillis() < drainDeadline
        ) {
            // Completion handlers may enqueue one final bridgeScope child. Keep
            // cancelling and pumping the shared scheduler until that hand-back
            // has also quiesced (the same #1355 shape as the base @After).
            vm.cancelOwnScopesForTest()
            runCurrent()
            // A VM child may be unwinding from a genuine background dispatcher;
            // yield wall time before pumping its Main continuation again.
            Thread.sleep(1)
        }
        assertEquals(
            "reconnect scenario must not retain VM work into its sibling/next iteration",
            0,
            vm.activeOwnScopeChildCountForTest(),
        )
        assertTrue(
            "reconnect scenario factory/tail/teardown scope must be fully cancelled",
            scenarioScope.coroutineContext[Job]?.children?.none { it.isActive } != false,
        )
    }

    /**
     * Drive reconnect polling in small virtual increments per wall-clock yield.
     * `advanceUntilIdle()` leaps directly to the send timeout before a real
     * dispatcher gets CPU under contention; `runCurrent()` alone never advances
     * the reconnect poll delay. This bounded pump does both without either race.
     */
    private fun TestScope.awaitReconnectSendCompletion(send: Job) {
        repeat(2_000) {
            if (send.isCompleted) return
            runCurrent()
            if (!send.isCompleted) {
                advanceTimeBy(10L)
                Thread.sleep(1)
            }
        }
        assertTrue(
            "reconnect send did not complete within 2,000 settle ticks under the " +
                "virtual-clock/wall-clock settle pump",
            send.isCompleted,
        )
    }

    @Test
    fun sendToAgentPaneResultDoesNotRetryNonRetryableFailedConnection() = runTest(scheduler) {
        val authFailure = UserAuthException("bad key")
        val connector = FailingLeaseConnector(authFailure)
        val vm = newVm(
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        deadClient.disconnectedSignal.value = true
        runCurrent()

        val first = async { vm.sendToAgentPaneResult("%0", "auth blocked") }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)
        assertEquals(1, connector.connectCount)
        assertTrue(vm.agentConversations.value["%0"]?.events.orEmpty().isEmpty())

        val second = vm.sendToAgentPaneResult("%0", "auth blocked")
        runCurrent()

        assertTrue("non-retryable failed state must fail immediately", second.isFailure)
        assertEquals("send must not redial after non-retryable auth failure", 1, connector.connectCount)
        assertTrue(vm.agentConversations.value["%0"]?.events.orEmpty().isEmpty())
    }

    @Test
    fun retryFailedAgentSendDropsFailedTurnAndReSendsWithoutDoubleSend() = runTest(scheduler) {
        // Issue #494: retrying a failed tmux send drops the failed
        // placeholder and re-sends. With a live client the re-send inserts
        // a fresh pending turn and submits the keys — exactly one user turn
        // remains (no double-send, no orphaned failed row).
        val vm = newVm()
        val failed = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}seed",
            agent = AgentKind.ClaudeCode,
            atMillis = 1L,
            role = ConversationRole.User,
            text = "retry me",
            sendState = MessageSendState.Failed,
        )
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.appendAgentEventsForTest("%0", listOf(failed))

        // Bring up a live client and retry.
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.retryFailedAgentSend("%0", failed.id)
        advanceUntilIdle()

        val events = vm.agentConversations.value["%0"]!!.events
        assertEquals("retry must leave exactly one user turn (no double-send)", 1, events.size)
        val pending = events.single() as ConversationEvent.Message
        assertEquals("retry me", pending.text)
        assertEquals(MessageSendState.Pending, pending.sendState)
        assertTrue("retried turn must be a fresh optimistic id", pending.id != failed.id)
        assertTrue(
            "retried send must submit Enter to the pane: ${client.sentCommands}",
            client.sentCommands.any { it == "send-keys -t %0 Enter" },
        )
    }

    @Test
    fun retryFailedAgentSendKeepsFailedTurnWhenRetryDeliveryFails() = runTest(scheduler) {
        val vm = newVm()
        val failed = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}seed",
            agent = AgentKind.ClaudeCode,
            atMillis = 1L,
            role = ConversationRole.User,
            text = "retry me",
            sendState = MessageSendState.Failed,
        )
        val client = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "send-keys"
        }
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.appendAgentEventsForTest("%0", listOf(failed))

        vm.retryFailedAgentSend("%0", failed.id)
        advanceUntilIdle()

        val events = vm.agentConversations.value["%0"]!!.events
        assertEquals("failed retry must still leave one retryable row", 1, events.size)
        val stillFailed = events.single() as ConversationEvent.Message
        assertEquals("retry me", stillFailed.text)
        assertEquals(MessageSendState.Failed, stillFailed.sendState)
        assertTrue("retry should create a fresh optimistic id", stillFailed.id != failed.id)
        assertTrue(
            "retry delivery should attempt tmux send before failing: ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("send-keys") },
        )
    }
}
