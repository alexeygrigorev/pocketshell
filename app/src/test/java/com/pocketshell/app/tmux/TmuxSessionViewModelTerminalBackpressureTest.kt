package com.pocketshell.app.tmux

import android.os.Looper
import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkSnapshot
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.sessionSurfaceState
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.terminal.bridge.SshTerminalBridge
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxOutputBacklogOverflow
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.testsupport.drainMainLooperUntil as drainMainLooperUntilShared
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionViewModelTerminalBackpressureTest : TmuxSessionViewModelTestBase() {
    @Test
    fun codexScaleTmuxOutputFloodKeepsTerminalAndConnectionStateConsistent() =
        runTest(scheduler, timeout = SLOW_FEED_RUN_TEST_TIMEOUT) {
        val diagnostics = installRecordingDiagnosticSink()
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "codex", paneIndex = 0)),
        )
        advanceUntilIdle()

        val state = vm.panes.value.single().terminalState
        val payloads = List(CODEX_SCALE_OUTPUT_CHUNKS, ::codexScaleOutputChunk)
        val emitted = payloads.sumOf { it.size }
        assertTrue(
            "test fixture drift: emitted=$emitted expectedFloor=$CODEX_SCALE_OUTPUT_BYTES",
            emitted >= CODEX_SCALE_OUTPUT_BYTES,
        )
        val observedSideChannelChunks = AtomicInteger(0)
        val outputCollector = launch {
            state.output.collect {
                observedSideChannelChunks.incrementAndGet()
            }
        }
        runCurrent()

        try {
            val sender = async {
                payloads.forEach { bytes ->
                    client.emittedEvents.emit(ControlEvent.Output("%0", bytes))
                }
            }

            // Issue #1042 follow-up (CI-only flake, PR #1045) / #1050: the live
            // `%output` drain feeds the REAL SshTerminalBridge, whose tail render
            // runs on a wall-clock background thread and whose budgeted main-thread
            // drain reposts a `postDelayed` continuation that a bare `idle()` never
            // fires. The old pump here was bounded ONLY by the virtual `runTest`
            // clock, so the entire loop elapsed in ~0 wall-clock time and starved
            // that background feed under a contended JVM (CI), flaking `completed`
            // false. `drainMainLooperUntil` pumps on the WALL clock instead — it
            // STRENGTHENS the assertion: a genuine stall still exhausts the deadline
            // and `completed` stays false.
            val completed = drainMainLooperUntil { !sender.isActive }
            if (completed) {
                sender.await()
            } else {
                sender.cancel()
            }

            assertTrue(
                "tmux %output flood must not stall terminal pane output",
                completed,
            )
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper())
                .idleFor(16L, java.util.concurrent.TimeUnit.MILLISECONDS)

            assertTrue(
                "tmux %output flood should still publish best-effort side-channel chunks",
                observedSideChannelChunks.get() > 0,
            )
            assertTrue(
                "Codex-scale output flood must not be diagnosed as a transport connection error",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertFalse(
                "Codex-scale output flood must not flip the tmux disconnected signal",
                client.disconnectedSignal.value,
            )
            assertFalse(
                "Codex-scale output flood must not mark the local terminal surface as failed",
                vm.panes.value.single().surfaceError,
            )
            assertTrue(
                "Codex-scale output flood must not be logged as terminal overflow",
                diagnostics.eventsNamed("terminal_output_overflow").isEmpty(),
            )
            assertTrue(
                "Codex-scale output flood must not be logged as passive SSH/tmux EOF",
                diagnostics.eventsNamed("passive_disconnect").isEmpty(),
            )
            assertTrue(
                "Codex-scale output flood must not start reconnect diagnostics",
                diagnostics.eventsNamed("reconnect_start").isEmpty(),
            )
        } finally {
            outputCollector.cancel()
            diagnostics.close()
        }
    }

    @Test
    fun codexLikeTmuxOutputWithSlowTerminalSideChannelRendersFinalMarkerWithoutReconnect() =
        runTest(scheduler, timeout = SLOW_FEED_RUN_TEST_TIMEOUT) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val diagnostics = installRecordingDiagnosticSink()
        val vm = newVm()
        val client = FakeTmuxClient()
        try {
            vm.attachClientForTest(client)
            vm.applyParsedPanesForTest(
                listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "codex", paneIndex = 0)),
            )
            advanceUntilIdle()

            val emittedConnectionStatuses =
                mutableListOf<TmuxSessionViewModel.ConnectionStatus>()
            val statusCollector = launch {
                vm.connectionStatus.collect { status ->
                    emittedConnectionStatuses += status
                }
            }
            val state = vm.panes.value.single().terminalState
            state.appendRemoteOutput("ISSUE576-SEED\r\n".toByteArray(Charsets.UTF_8))
            shadowOf(Looper.getMainLooper()).idle()
            val observedSideChannelChunks = AtomicInteger(0)
            val slowSideChannelCollector = launch {
                state.output.collect {
                    observedSideChannelChunks.incrementAndGet()
                    delay(60_000)
                }
            }
            runCurrent()

            try {
                val payloads = codexLikeIssue576BurstChunks()
                val emittedBytes = payloads.sumOf { it.size }
                assertTrue(
                    "test fixture drift: Codex-like burst must stay high-volume, bytes=$emittedBytes",
                    emittedBytes >= 250_000,
                )
                val sender = async {
                    payloads.forEach { bytes ->
                        client.emittedEvents.emit(ControlEvent.Output("%0", bytes))
                    }
                }

                // Issue #1042 follow-up (CI-only flake, PR #1045) / #1050: the
                // off-main live `%output` drain is frame-paced — the
                // MainThreadDrainScheduler `postDelayed`s its continuation one frame
                // (16ms) out between bounded parse turns so the looper is guaranteed a
                // servicing gap (the #803/#804 ANR fix). Under the Robolectric PAUSED
                // looper a plain `idle()` only runs tasks already DUE, so the looper
                // MUST be advanced a frame (`idleFor(16ms)`) for those delayed
                // continuations to fire and the burst to drain. The old budget here was
                // the virtual `runTest` clock, which elapsed in ~0 wall-clock time and
                // starved the real background SshTerminalBridge feed under a contended
                // JVM (CI), flaking `completed` false. `drainMainLooperUntil` pumps on
                // the WALL clock instead so the background feed gets real time. This
                // does NOT weaken the assertion: a genuine stall still exhausts the
                // deadline and `completed` stays false.
                val completed = drainMainLooperUntil { !sender.isActive }
                if (completed) {
                    sender.await()
                } else {
                    sender.cancel()
                }

                assertTrue(
                    "Codex-like tmux %output burst must not stall behind a slow terminal side-channel",
                    completed,
                )
                // Issue #708: the final marker is applied by the REAL
                // SshTerminalBridge feed on a wall-clock background thread, which a
                // single virtual `advanceUntilIdle()` + one Looper idle does not
                // reliably drain under a contended JVM (this is the only
                // load-sensitive flake in the lease-fix gate). Pump the Looper +
                // the scheduler in a bounded wall-clock loop until the marker
                // renders. This strengthens, never weakens, the assertion: if the
                // marker genuinely never lands the loop still times out and the
                // assertTrue below fails.
                var transcript = ""
                val markerDeadline = System.currentTimeMillis() + SLOW_FEED_DRAIN_TIMEOUT_MS
                do {
                    advanceUntilIdle()
                    // Issue #803/#804: advance the virtual looper a frame so the
                    // budgeted `postDelayed` drain-continuations fire (a bare `idle()`
                    // never runs them), draining the queue tail and rendering the marker.
                    shadowOf(Looper.getMainLooper())
                        .idleFor(16L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    transcript = renderedTranscriptFrom(state)
                    if (transcript.contains("ISSUE576-CODEX-LIKE-DONE")) break
                    Thread.sleep(20)
                } while (System.currentTimeMillis() < markerDeadline)
                assertTrue(
                    "integrated fake tmux -> TerminalSurfaceState -> SshTerminalBridge path must render " +
                        "the final marker; tail=${transcript.takeLast(500)}",
                    transcript.contains("ISSUE576-CODEX-LIKE-DONE"),
                )
                assertTrue(
                    "slow side-channel collector should prove the secondary output flow was subscribed",
                    observedSideChannelChunks.get() > 0,
                )
                val connectionFailureStatuses = emittedConnectionStatuses.filter { status ->
                    status is TmuxSessionViewModel.ConnectionStatus.Connecting ||
                        status is TmuxSessionViewModel.ConnectionStatus.Reconnecting ||
                        status is TmuxSessionViewModel.ConnectionStatus.Failed
                }
                assertTrue(
                    "terminal-side pressure must not emit reconnect/disconnect VM states; " +
                        "observed=$emittedConnectionStatuses",
                    connectionFailureStatuses.isEmpty(),
                )
                assertTrue(
                    "terminal-side pressure must not be diagnosed as a transport connection error",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
                assertFalse(
                    "terminal-side pressure must not flip the tmux disconnected signal",
                    client.disconnectedSignal.value,
                )
                assertFalse(
                    "terminal-side pressure must not mark the local terminal surface as failed",
                    vm.panes.value.single().surfaceError,
                )
                assertEquals(
                    "integrated terminal pressure must not enqueue tmux reconnect attempts",
                    0,
                    TMUX_CONNECT_ATTEMPTS.get(),
                )
                assertTrue(
                    "under-threshold Codex-like output must not be logged as terminal overflow",
                    diagnostics.eventsNamed("terminal_output_overflow").isEmpty(),
                )
                assertTrue(
                    "under-threshold Codex-like output must not be logged as passive SSH/tmux EOF",
                    diagnostics.eventsNamed("passive_disconnect").isEmpty(),
                )
                assertTrue(
                    "under-threshold Codex-like output must not start reconnect diagnostics",
                    diagnostics.eventsNamed("reconnect_start").isEmpty(),
                )
            } finally {
                statusCollector.cancel()
                slowSideChannelCollector.cancel()
            }
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun codexLikePaneOutputOverflowStaysLocalAndNeverReconnectsStableTransport() =
        runTest(scheduler, timeout = SLOW_FEED_RUN_TEST_TIMEOUT) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L))
        val client = FakeTmuxClient()
        client.decoupleOutputForFromEvents = true
        client.reportOutputBacklogOverflowOnTryEmitFailure = true
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "codex",
            client = client,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%0",
                    "@0",
                    "\$0",
                    "codex",
                    paneIndex = 0,
                    sessionName = "codex",
                ),
            ),
        )
        runCurrent()

        val emittedConnectionStatuses =
            mutableListOf<TmuxSessionViewModel.ConnectionStatus>()
        val statusCollector = launch {
            vm.connectionStatus.collect { status ->
                emittedConnectionStatuses += status
            }
        }
        runCurrent()

        val state = vm.panes.value.single().terminalState
        val observedTerminalSideChannelChunks = AtomicInteger(0)
        val terminalSideChannelCollector = launch {
            state.output.collect {
                observedTerminalSideChannelChunks.incrementAndGet()
                delay(60_000)
            }
        }
        val slowPaneOutputCollector = launch {
            client.outputFor("%0").collect {
                delay(60_000)
            }
        }
        runCurrent()

        try {
            val chunks = codexLikeIssue576BurstChunks()
            val emittedBytes = chunks.sumOf { it.size }
            assertTrue(
                "test fixture drift: emittedBytes=$emittedBytes",
                emittedBytes >= 250_000,
            )

            var rejectedChunks = 0
            val sender = async {
                chunks.forEach { chunk ->
                    if (!client.tryEmitPaneOutput("%0", chunk)) rejectedChunks += 1
                }
            }
            val completed = withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
                sender.await()
                true
            } ?: false
            assertTrue(
                "Codex-like pane output burst must not block the reader/UI producer",
                completed,
            )
            assertTrue(
                "test must actually reproduce bounded pane-output backpressure",
                rejectedChunks > 0,
            )

            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()

            registry.lifecycleHooksSnapshot().single().onNetworkChanged(
                networkChange(
                    previous = TerminalNetworkSnapshot.NoValidatedNetwork,
                    current = TerminalNetworkSnapshot.Validated("wifi"),
                    previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                    reason = "same-network-after-codex-output-overflow",
                ),
            )
            runCurrent()

            assertTrue(
                "some output should reach the terminal side-channel before local overflow recovery",
                observedTerminalSideChannelChunks.get() > 0,
            )
            val connectionFailureStatuses = emittedConnectionStatuses.filter { status ->
                status is TmuxSessionViewModel.ConnectionStatus.Connecting ||
                    status is TmuxSessionViewModel.ConnectionStatus.Reconnecting ||
                    status is TmuxSessionViewModel.ConnectionStatus.Failed
            }
            assertTrue(
                "Codex-like output overflow must not emit reconnect/disconnect VM states; " +
                    "observed=$emittedConnectionStatuses",
                connectionFailureStatuses.isEmpty(),
            )
            // Issue #1326 (S3): the pill now derives from the fused
            // [SessionSurfaceState]. Map each emitted status through the SAME fusion
            // the screen uses (a held reveal takes the status's pill flavor) to prove
            // the breadcrumb never flips to Reconnecting/Disconnected on overflow.
            val pillSid = SessionId("codex/overflow")
            fun TmuxSessionViewModel.ConnectionStatus.toPillUi() =
                sessionSurfaceState(RevealState.Seeding(pillSid, "s"), connectionPhaseOf(this), pillSid)
                    .toUiStatus()
            val disconnectUiStatuses = emittedConnectionStatuses
                .map { it.toPillUi() }
                .filter { uiStatus ->
                    uiStatus == com.pocketshell.uikit.model.ConnectionStatus.Connecting ||
                        uiStatus == com.pocketshell.uikit.model.ConnectionStatus.Error
                }
            assertTrue(
                "Codex-like output overflow must not map to the breadcrumb Reconnecting/Disconnected UI; " +
                    "observed=${emittedConnectionStatuses.map { it.toPillUi() }}",
                disconnectUiStatuses.isEmpty(),
            )
            assertTrue(
                "output overflow is local terminal backpressure, not an SSH/tmux disconnect",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertEquals(
                "stable mocked transport must not be reopened for local output overflow",
                0,
                connector.connectCount,
            )
            assertEquals(
                "local output overflow must not enqueue tmux reconnect attempts",
                0,
                TMUX_CONNECT_ATTEMPTS.get(),
            )
            assertFalse(
                "local output overflow must not flip the tmux disconnected signal",
                client.disconnectedSignal.value,
            )
            // Issue #1205: a backlog overflow no longer LATCHES the pane into a
            // dead surfaceError card — it reseeds-and-reattaches the pane through
            // the existing chokepoint (a transient burst costs one reseed, not the
            // pane). The load-bearing invariant this test guards is unchanged: the
            // overflow stays LOCAL (no reconnect, stable transport). The pane now
            // self-heals rather than requiring "Recreate terminal".
            assertFalse(
                "Issue #1205: local output overflow must reseed-and-reattach the pane, " +
                    "not latch it into a dead surfaceError card",
                vm.panes.value.single().surfaceError,
            )
        } finally {
            statusCollector.cancel()
            terminalSideChannelCollector.cancel()
            slowPaneOutputCollector.cancel()
        }
    }

    @Test
    fun terminalOutputBacklogOverflowIsLocalPaneErrorNotReconnect() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        val vm = newVm()
        val client = FakeTmuxClient()
        try {
            vm.attachClientForTest(client)
            vm.applyParsedPanesForTest(
                listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "codex", paneIndex = 0)),
            )
            advanceUntilIdle()

            client.outputBacklogOverflowEvents.emit(
                TmuxOutputBacklogOverflow(paneId = "%0", droppedEvents = 1),
            )
            advanceUntilIdle()

            assertTrue(
                "terminal backlog overflow is a local pane error, not reconnect",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertFalse(
                "terminal backlog overflow must not flip tmux disconnected",
                client.disconnectedSignal.value,
            )
            assertTrue(
                "overflowed pane should enter explicit terminal recovery state",
                vm.panes.value.single().surfaceError,
            )
            val overflow = diagnostics.eventsNamed("terminal_output_overflow").single()
            assertEquals("pane_output_backlog", overflow.fields["source"])
            assertEquals("local_terminal_renderer_backpressure", overflow.fields["classification"])
            assertEquals(false, overflow.fields["reconnect"])
            assertEquals(false, overflow.fields["tmuxDisconnected"])
            assertEquals("%0", overflow.fields["pane"])
            assertEquals(1, overflow.fields["droppedEvents"])
            assertTrue(
                "local overflow must not be logged as passive SSH/tmux EOF",
                diagnostics.eventsNamed("passive_disconnect").isEmpty(),
            )
            assertTrue(
                "local overflow must not start reconnect diagnostics",
                diagnostics.eventsNamed("reconnect_start").isEmpty(),
            )
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun seedGateLiveBufferOverflowIsLocalPaneErrorNotReconnect() =
        runTest(scheduler, timeout = SLOW_FEED_RUN_TEST_TIMEOUT) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val diagnostics = installRecordingDiagnosticSink()
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTerminalSurfaceStateFactoryForTest {
            TerminalSurfaceState(StandardTestDispatcher(testScheduler))
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L))
        val client = FakeTmuxClient(paneOutputExtraBufferCapacity = 0).apply {
            decoupleOutputForFromEvents = true
        }
        try {
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "codex",
                client = client,
            )
            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        "%0",
                        "@0",
                        "\$0",
                        "codex",
                        paneIndex = 0,
                        sessionName = "codex",
                    ),
                ),
            )
            runCurrent()
            val paneOutputSubscribersReady = withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
                client.emittedPaneOutputs.subscriptionCount.first { subscriberCount ->
                    // Terminal producer, output-activity observer, and port detector.
                    subscriberCount >= 3
                }
                true
            } ?: false
            assertTrue(
                "test must wait until pane-output collectors are subscribed",
                paneOutputSubscribersReady,
            )

            val emittedConnectionStatuses =
                mutableListOf<TmuxSessionViewModel.ConnectionStatus>()
            val statusCollector = launch {
                vm.connectionStatus.collect { status ->
                    emittedConnectionStatuses += status
                }
            }
            runCurrent()

            try {
                client.emittedPaneOutputs.emit(
                    ControlEvent.Output(
                        "%0",
                        ByteArray(SshTerminalBridge.MAX_SEED_GATE_LIVE_BUFFER_BYTES + 1),
                    ),
                )
                advanceUntilIdle()
                shadowOf(Looper.getMainLooper()).idle()
                runCurrent()

                val overflow = diagnostics.eventsNamed("terminal_output_overflow").singleOrNull()

                assertNotNull(
                    "seed-gate live buffer overflow should record the local backpressure event",
                    overflow,
                )
                // Issue #1205: the seed-gate overflow reseeds-and-reattaches the
                // pane (same recovery as the backlog overflow) instead of latching
                // it into a dead surfaceError card.
                assertFalse(
                    "Issue #1205: seed-gate overflow must reseed-and-reattach the pane, " +
                        "not latch it into a dead surfaceError card",
                    vm.panes.value.single().surfaceError,
                )
                val overflowEvent = overflow!!
                val connectionFailureStatuses = emittedConnectionStatuses.filter { status ->
                    status is TmuxSessionViewModel.ConnectionStatus.Connecting ||
                        status is TmuxSessionViewModel.ConnectionStatus.Reconnecting ||
                        status is TmuxSessionViewModel.ConnectionStatus.Failed
                }
                assertTrue(
                    "seed-gate overflow must not emit reconnect/disconnect VM states; " +
                        "observed=$emittedConnectionStatuses",
                    connectionFailureStatuses.isEmpty(),
                )
                assertTrue(
                    "seed-gate overflow is terminal-local backpressure, not an SSH/tmux disconnect",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
                assertEquals(
                    "stable mocked transport must not be reopened for local seed-gate overflow",
                    0,
                    connector.connectCount,
                )
                assertEquals(
                    "local seed-gate overflow must not enqueue tmux reconnect attempts",
                    0,
                    TMUX_CONNECT_ATTEMPTS.get(),
                )
                assertFalse(
                    "local seed-gate overflow must not flip the tmux disconnected signal",
                    client.disconnectedSignal.value,
                )

                assertEquals("seed_gate_live_buffer", overflowEvent.fields["source"])
                assertEquals("local_terminal_renderer_backpressure", overflowEvent.fields["classification"])
                assertEquals(false, overflowEvent.fields["reconnect"])
                assertEquals(false, overflowEvent.fields["tmuxDisconnected"])
                assertEquals("%0", overflowEvent.fields["pane"])
                assertTrue(
                    "local overflow must not be logged as passive SSH/tmux EOF",
                    diagnostics.eventsNamed("passive_disconnect").isEmpty(),
                )
                assertTrue(
                    "local overflow must not start reconnect diagnostics",
                    diagnostics.eventsNamed("reconnect_start").isEmpty(),
                )
            } finally {
                statusCollector.cancel()
            }
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun terminalSurfaceFailureDoesNotMarkTmuxTransportDisconnectedOrReconnect() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()
        val originalTerminalState = vm.panes.value.single().terminalState
        val attemptsBeforeFailure = TMUX_CONNECT_ATTEMPTS.get()

        vm.reportTerminalSurfaceFailureForTest(
            paneId = "%0",
            cause = RuntimeException("ime resize"),
        )
        advanceUntilIdle()

        assertFalse("local terminal failure must not flip tmux disconnected", client.disconnectedSignal.value)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(
            "local terminal failure must not enqueue reconnect attempts",
            attemptsBeforeFailure,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        assertNotSame(
            "terminal surface should be recreated locally so IME/input can recover",
            originalTerminalState,
            vm.panes.value.single().terminalState,
        )
    }

    @Test
    fun terminalSurfaceFailureClearsProducerClientWhenAttachProducerFailsSoReconcileRetries() =
        runTest(scheduler) {
            val vm = newVm()
            val backingClient = FakeTmuxClient()
            val client = OutputFailingTmuxClient(backingClient)
            vm.attachClientForTest(client)
            vm.applyParsedPanesForTest(
                listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
            )
            advanceUntilIdle()

            val clientIdentity = System.identityHashCode(client)
            assertEquals(
                "precondition: pane producer starts bound to the live client",
                clientIdentity,
                vm.paneProducerClientIdentityForTest("%0"),
            )
            val originalState = vm.panes.value.single().terminalState

            client.failOutputFor = true
            vm.reportTerminalSurfaceFailureForTest(
                paneId = "%0",
                cause = RuntimeException("surface reset"),
            )
            advanceUntilIdle()

            assertNull(
                "failed terminal producer reattach must clear the client binding so " +
                    "the next reconcile retries instead of assuming the producer is live",
                vm.paneProducerClientIdentityForTest("%0"),
            )
            assertNotSame(
                "surface failure still replaces the local TerminalSurfaceState",
                originalState,
                vm.panes.value.single().terminalState,
            )

            client.failOutputFor = false
            vm.applyParsedPanesForTest(
                listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
            )
            advanceUntilIdle()

            assertEquals(
                "a later reconcile must retry and restore the producer-client binding",
                clientIdentity,
                vm.paneProducerClientIdentityForTest("%0"),
            )
            assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
            assertFalse(backingClient.disconnectedSignal.value)
        }

    @Test
    fun repeatedTerminalSurfaceFailuresStopAtErrorStateInsteadOfReconnectStorm() = runTest(scheduler) {
        // Issue #423: opening the keyboard after a long dictated Codex
        // prompt could send the terminal into a recovery storm — the
        // surface redraws, fails, gets recreated, fails again, and never
        // settles, then the app shows "reconnecting" and becomes
        // unrecoverable. This asserts the storm stops at an actionable
        // error state with the SSH/tmux transport untouched (no reconnect
        // attempts, no disconnected signal), and that the user-driven
        // recreate path clears the error and rebuilds the surface.
        TMUX_CONNECT_ATTEMPTS.set(0)
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()
        val attemptsBeforeFailure = TMUX_CONNECT_ATTEMPTS.get()

        // Drive a burst of failures past the storm threshold. The first
        // few recover transparently (surface recreated, surfaceError
        // stays false); once the threshold trips, the pane flips to the
        // actionable error state and stops re-attaching.
        repeat(SURFACE_RECOVERY_STORM_THRESHOLD + 2) {
            vm.reportTerminalSurfaceFailureForTest(
                paneId = "%0",
                cause = RuntimeException("ime redraw storm"),
            )
        }
        advanceUntilIdle()

        val pane = vm.panes.value.single()
        assertTrue(
            "a recovery storm must flip the pane into the actionable surface-error state",
            pane.surfaceError,
        )
        assertFalse(
            "surface recovery storm must not flip tmux disconnected",
            client.disconnectedSignal.value,
        )
        assertTrue(
            "surface recovery storm must leave the transport Connected",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(
            "surface recovery storm must not enqueue any reconnect attempts",
            attemptsBeforeFailure,
            TMUX_CONNECT_ATTEMPTS.get(),
        )

        // User taps "Recreate terminal": the error clears, a fresh surface
        // is attached, and the transport is still untouched.
        val erroredTerminalState = pane.terminalState
        vm.recreateTerminalSurface("%0")
        advanceUntilIdle()

        val recovered = vm.panes.value.single()
        assertFalse(
            "recreate must clear the surface-error state",
            recovered.surfaceError,
        )
        assertNotSame(
            "recreate must build a fresh TerminalSurfaceState so IME/input can recover",
            erroredTerminalState,
            recovered.terminalState,
        )
        assertEquals(
            "recreate must not reconnect SSH",
            attemptsBeforeFailure,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        assertFalse(
            "recreate must not flip tmux disconnected",
            client.disconnectedSignal.value,
        )
    }


    /**
     * Wall-clock-bounded looper pump for the flood-drain tests whose REAL
     * [SshTerminalBridge] feed runs on an Android Handler / background thread
     * that a virtual-clock-only `runTest` loop starves under a contended (CI)
     * JVM (#1042/#1050). Each turn advances the main looper a frame
     * (`idleFor(16ms)`) so the budgeted `postDelayed` drain continuations fire
     * (a bare `idle()` never runs them — the #803/#804 ANR drain scheduler
     * shape), drives the virtual scheduler (`runCurrent()`) so suspended
     * coroutines progress, and yields real wall-clock time (`Thread.sleep`) to
     * the background feed. Returns true if [condition] becomes true before
     * [deadlineMs] wall-clock milliseconds elapse, false on timeout.
     */
    private fun TestScope.drainMainLooperUntil(
        deadlineMs: Long = SLOW_FEED_DRAIN_TIMEOUT_MS,
        condition: () -> Boolean,
    ): Boolean =
        drainMainLooperUntilShared(
            deadlineMs = deadlineMs,
            sleepMs = 20L,
            onTick = {
                shadowOf(Looper.getMainLooper())
                    .idleFor(16L, java.util.concurrent.TimeUnit.MILLISECONDS)
                runCurrent()
            },
            condition = condition,
        )

    private fun renderedTranscriptFrom(state: TerminalSurfaceState): String {
        val bridgeField = TerminalSurfaceState::class.java.getDeclaredField("bridge").apply {
            isAccessible = true
        }
        val bridge = bridgeField.get(state) as? SshTerminalBridge ?: return ""
        return bridge.emulator.screen.transcriptText
    }

    private fun codexScaleOutputChunk(index: Int): ByteArray {
        val linePrefix = "codex-overload-${index.toString().padStart(4, '0')}"
        val line = "$linePrefix " + "x".repeat(240) + "\r\n"
        return buildString {
            repeat(CODEX_SCALE_OUTPUT_LINES_PER_CHUNK) { append(line) }
        }.toByteArray(Charsets.UTF_8)
    }

    private fun codexLikeIssue576BurstChunks(): List<ByteArray> {
        val transcript = buildString {
            append("# Codex synthetic /new overflow harness\r\n\r\n")
            append("Preparing workspace context with many changed files and prior transcript lines.\r\n")
            append("```text\r\n")
            append("git status --short --branch\r\n")
            repeat(48) { index ->
                append(" M app/src/main/java/com/pocketshell/issue576/File")
                append(index.toString().padStart(3, '0'))
                append(".kt\r\n")
            }
            append("```\r\n\r\n")
            append("```kotlin\r\n")
            append("fun generatedStatus(index: Int) = \"line-${'$'}index\"\r\n")
            append("```\r\n\r\n")
            append("LONG-WRAPPED-CONTEXT ")
            append("L".repeat(32_000))
            append("\r\n")
            repeat(720) { index ->
                append("\u001b[2K\r")
                append("status: indexing ")
                append(index)
                append("/720 ")
                append(".".repeat(index % 40))
                append("\r\n")
                append("- changed file ")
                append(index.toString().padStart(4, '0'))
                append(": ")
                append("markdown `inline code` and shell output ".repeat(5))
                append("\r\n")
                if (index % 9 == 0) {
                    append("```sh\r\n")
                    append("printf 'burst chunk ")
                    append(index)
                    append("'; sleep 0.01\r\n")
                    append("```\r\n")
                }
                if (index % 17 == 0) {
                    append("> progress note ")
                    append(index)
                    append(": ")
                    append("wrapped ".repeat(90))
                    append("\r\n")
                }
            }
            append("\u001b[32mISSUE576-CODEX-LIKE-DONE\u001b[0m\r\n")
        }.toByteArray(Charsets.UTF_8)

        val chunkSizes = intArrayOf(1, 2, 5, 13, 3, 34, 8, 89, 21, 144)
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        var chunkIndex = 0
        while (offset < transcript.size) {
            val size = chunkSizes[chunkIndex % chunkSizes.size]
            val end = minOf(transcript.size, offset + size)
            chunks += transcript.copyOfRange(offset, end)
            offset = end
            chunkIndex += 1
        }
        return chunks
    }

    private fun networkChange(
        previous: TerminalNetworkSnapshot = TerminalNetworkSnapshot.Validated("wifi"),
        current: TerminalNetworkSnapshot.Validated = TerminalNetworkSnapshot.Validated("cell"),
        previousValidated: TerminalNetworkSnapshot.Validated? =
            previous as? TerminalNetworkSnapshot.Validated,
        reason: String = "validated-default-network-changed",
        sequence: Long = 1L,
        deferredFromBackground: Boolean = false,
    ): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = previous,
            current = current,
            previousValidated = previousValidated,
            reason = reason,
            sequence = sequence,
            deferredFromBackground = deferredFromBackground,
        )

    private class QueueLeaseConnector(
        private vararg val sessions: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<com.pocketshell.core.ssh.SshSession> {
            val next = sessions.getOrNull(connectCount)
                ?: error("unexpected lease connect $connectCount for ${target.leaseKey}")
            connectCount += 1
            return Result.success(next)
        }
    }

    private class FakeSshSession : SshSession {
        @Volatile
        private var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

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

    private class OutputFailingTmuxClient(
        private val delegate: FakeTmuxClient,
    ) : TmuxClient by delegate {
        var failOutputFor: Boolean = false

        override fun outputFor(paneId: String): Flow<ControlEvent.Output> {
            if (failOutputFor) throw RuntimeException("outputFor failed")
            return delegate.outputFor(paneId)
        }
    }

    private companion object {
        const val SLOW_FEED_DRAIN_TIMEOUT_MS = 30_000L
        val SLOW_FEED_RUN_TEST_TIMEOUT = 120.seconds

        const val CODEX_SCALE_OUTPUT_CHUNKS = 320
        const val CODEX_SCALE_OUTPUT_LINES_PER_CHUNK = 20
        const val CODEX_SCALE_OUTPUT_BYTES = 1_500_000
    }
}
