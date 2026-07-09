package com.pocketshell.app.tmux

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.protocol.ControlEvent
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionViewModelPortDetectionTest : TmuxSessionViewModelTestBase() {

    /**
     * Attach a client + session that reports [listeningPorts] from its
     * `ss` confirm scan, then materialise one pane so the detection
     * collector is wired onto the pane's shared output flow.
     */
    private fun TmuxSessionViewModel.attachForPortDetection(
        client: FakeTmuxClient,
        session: PortDetectionSshSession,
    ) {
        replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            // Empty session name so the default ParsedPane.sessionName ("")
            // passes applyParsedPanes' session filter.
            sessionName = "",
            client = client,
            session = session,
        )
        applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0),
            ),
        )
    }

    @Test
    fun confirmedNewPortSurfacesOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(5173))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        assertNull(vm.detectedPort.value)

        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Local:   http://localhost:5173/\n".toByteArray()),
        )
        advanceUntilIdle()

        assertEquals(5173, vm.detectedPort.value)
    }

    @Test
    fun stalePortDetectorFromParkedRuntimeDoesNotSurfaceOnNewRuntime() = runTest(scheduler) {
        val vm = newVm()
        val oldClient = FakeTmuxClient()
        val oldSession = PortDetectionSshSession(listeningPorts = emptySet())
        vm.attachForPortDetection(oldClient, oldSession)
        advanceUntilIdle()

        val newClient = FakeTmuxClient()
        val newSession = PortDetectionSshSession(listeningPorts = setOf(5173))
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = newSession,
        )
        advanceUntilIdle()

        oldClient.emittedEvents.emit(
            ControlEvent.Output("%0", "Local:   http://localhost:5173/\n".toByteArray()),
        )
        advanceUntilIdle()

        assertNull(
            "a detector bound to the parked old runtime must not confirm against the new runtime",
            vm.detectedPort.value,
        )
    }

    @Test
    fun assistantConversationLocalhostUrlSurfacesPortForwardOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(5173))
        vm.attachForPortDetection(client, session)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        advanceUntilIdle()

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.Message(
                    id = "assistant-localhost",
                    agent = AgentKind.ClaudeCode,
                    role = ConversationRole.Assistant,
                    text = "Preview is ready at http://localhost:5173/",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(5173, vm.detectedPort.value)
    }

    @Test
    fun assistantConversationLoopbackPortPhraseSurfacesPortForwardOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(3000))
        vm.attachForPortDetection(client, session)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        advanceUntilIdle()

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.Message(
                    id = "assistant-localhost-port-phrase",
                    agent = AgentKind.ClaudeCode,
                    role = ConversationRole.Assistant,
                    text = "Preview is running on localhost port 3000.",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(3000, vm.detectedPort.value)
    }

    @Test
    fun agentToolResultLoopbackPortSurfacesPortForwardOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(8000))
        vm.attachForPortDetection(client, session)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        advanceUntilIdle()

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.ToolResult(
                    id = "tool-localhost",
                    agent = AgentKind.ClaudeCode,
                    output = "Server running on 0.0.0.0:8000\n",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(8000, vm.detectedPort.value)
    }

    @Test
    fun userConversationLocalhostUrlDoesNotSurfacePortForwardOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(5173))
        vm.attachForPortDetection(client, session)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        advanceUntilIdle()

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.Message(
                    id = "user-localhost",
                    agent = AgentKind.ClaudeCode,
                    role = ConversationRole.User,
                    text = "Can you check http://localhost:5173?",
                ),
            ),
        )
        advanceUntilIdle()

        assertNull(vm.detectedPort.value)
    }

    @Test
    fun echoedPortNotListeningDoesNotSurfaceOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        // ss reports nothing listening -- the regex hit is an echoed/old URL.
        val session = PortDetectionSshSession(listeningPorts = emptySet())
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()

        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on http://127.0.0.1:8000\n".toByteArray()),
        )
        advanceUntilIdle()

        assertNull("unconfirmed port must not surface an overlay", vm.detectedPort.value)
    }

    @Test
    fun acceptingDetectedPortReturnsItAndClearsOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(8000))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertEquals(8000, vm.detectedPort.value)

        assertEquals(8000, vm.acceptDetectedPort())
        assertNull(vm.detectedPort.value)
    }

    @Test
    fun dismissedPortDoesNotReSurfaceInSameSession() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(8000))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertEquals(8000, vm.detectedPort.value)

        vm.dismissDetectedPort()
        assertNull(vm.detectedPort.value)

        // Same port reprinted later in the session -- must not re-prompt.
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertNull("dismissed port must not re-prompt", vm.detectedPort.value)
    }

    @Test
    fun forwardedPortDoesNotReSurfaceInSameSession() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(8000))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertEquals(8000, vm.acceptDetectedPort())

        // Same port reprinted after the user forwarded it -- no re-prompt.
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertNull("forwarded port must not re-prompt", vm.detectedPort.value)
    }

    /**
     * Issue #877 (idle-session ANR): a [CoroutineDispatcher] that delegates to
     * the shared virtual-clock test dispatcher but flips [usedForScan] true the
     * first time it is asked to [dispatch] a block. Injected as the VM's
     * `portDetectionDispatcher` so the test can assert the per-`%output`
     * decode + `PortDetector.scan` was hopped off the main/immediate dispatcher
     * (it goes THROUGH this tracking dispatcher) rather than running inline on
     * the bridge scope (Main) the way the unfixed code did.
     */
    private inner class ScanDispatchTracker(
        // A real-dispatch delegate (a StandardTestDispatcher on the shared
        // scheduler), NOT the Unconfined Main dispatcher -- wrapping Unconfined
        // and forcing dispatch violates its "yield-only" contract. A
        // StandardTestDispatcher genuinely needs dispatch and is driven by
        // advanceUntilIdle, so a `withContext(this)` from a bridgeScope (Main)
        // coroutine is a real, observable hop OFF Main.
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        val usedForScan = AtomicBoolean(false)
        val dispatchCount = AtomicInteger(0)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            usedForScan.set(true)
            dispatchCount.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    /**
     * Issue #877 regression (red->green, load-bearing): the per-`%output`
     * decode + 7-regex [PortDetector.scan] -- the work that froze an idle agent
     * session because it ran on the UI thread for every output chunk -- must run
     * on the injected off-main `portDetectionDispatcher`, NOT inline on the
     * bridge scope (Main). RED on base: `startPortDetectionForPane` ran the
     * scan inline so the tracking dispatcher is never used. GREEN with the fix:
     * `scanOutputEventForPorts` hops to `portDetectionDispatcher`, so the
     * tracker records the dispatch. The port is still detected (behaviour
     * preserved -- only the thread changed).
     */
    @Test
    fun portDetectionDecodeAndScanRunsOffMainNotOnBridgeScope() = runTest(scheduler) {
        val vm = newVm()
        val tracker = ScanDispatchTracker(StandardTestDispatcher(scheduler))
        vm.setPortDetectionDispatcherForTest(tracker)
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(5173))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        val dispatchesBeforeOutput = tracker.dispatchCount.get()

        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Local:   http://localhost:5173/\n".toByteArray()),
        )
        advanceUntilIdle()

        assertTrue(
            "the %output chunk must produce a NEW off-main scan dispatch, proving " +
                "the decode + scan was hopped off the bridge scope (Main)",
            tracker.dispatchCount.get() > dispatchesBeforeOutput,
        )
        assertTrue(
            "the per-%output decode + PortDetector.scan must run on the off-main " +
                "portDetectionDispatcher, not inline on the bridge scope (Main)",
            tracker.usedForScan.get(),
        )
        assertEquals(
            "the port must still be detected after the scan moved off Main",
            5173,
            vm.detectedPort.value,
        )
    }

    /**
     * Issue #877 class coverage: an idle agent pane keeps emitting low-rate
     * spinner/status `%output` frames; EVERY such frame's decode + scan must
     * hop off Main, never accumulating main-thread work. Feed a burst of idle
     * spinner frames carrying NO port and assert the scan ran off-main for each
     * one (the tracker is dispatched once per frame) while no overlay is
     * surfaced. This is the steady-idle-on-Main pattern the maintainer hit.
     */
    @Test
    fun idleSpinnerOutputScansOffMainEveryFrameWithoutSurfacingOverlay() = runTest(scheduler) {
        val vm = newVm()
        val tracker = ScanDispatchTracker(StandardTestDispatcher(scheduler))
        vm.setPortDetectionDispatcherForTest(tracker)
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = emptySet())
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        val dispatchesBeforeFrames = tracker.dispatchCount.get()

        val frames = 40
        val spinner = "⠋⠙⠹⠸"
        repeat(frames) { i ->
            // A typical idle-agent spinner/status repaint: cursor moves + a
            // braille spinner glyph, no listening-port signal.
            client.emittedEvents.emit(
                ControlEvent.Output(
                    "%0",
                    "[2K\rThinking ${spinner[i % spinner.length]} (esc to interrupt)".toByteArray(),
                ),
            )
        }
        advanceUntilIdle()

        assertTrue(
            "every idle %output frame's scan must run off-main (one new off-main " +
                "dispatch per emitted frame)",
            tracker.dispatchCount.get() - dispatchesBeforeFrames >= frames,
        )
        assertNull(
            "idle spinner output carries no port, so no overlay must surface",
            vm.detectedPort.value,
        )
    }

    private fun newClaudeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/home/u/.claude/sessions/abc.jsonl",
        sessionId = "abc",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private class PortDetectionSshSession(
        private val listeningPorts: Set<Int>,
    ) : SshSession {
        @Volatile
        private var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            val stdout = when {
                command.contains("ss -tlnp") ->
                    listeningPorts.joinToString("\n") {
                        "0.0.0.0:$it users:((\"server\",pid=1,fd=3))"
                    }
                command.contains("netstat -tlnp") || command.contains("ss -tln") -> ""
                else -> ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

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
}
