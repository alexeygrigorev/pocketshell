package com.pocketshell.app.tmux

import android.os.Looper
import android.os.SystemClock
import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkSnapshot
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.app.session.OPTIMISTIC_USER_MESSAGE_ID_PREFIX
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.agents.MessageSendState
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.uikit.model.KeyModifierState
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.TmuxOutputBacklogOverflow
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.core.terminal.ui.TerminalRawInputPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [TmuxSessionViewModel] that exercise the per-pane
 * reconciliation and command dispatch via a [FakeTmuxClient].
 *
 * Robolectric is required because [TerminalSurfaceState.attachExternalProducer]
 * spins up a [com.pocketshell.core.terminal.bridge.SshTerminalBridge]
 * whose constructor builds a [com.termux.terminal.TerminalSession] that
 * needs a working `Looper` / `Handler` to construct (mirrors the rationale
 * already documented in [com.pocketshell.app.session.SessionViewModelTest]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun newVm(
        registry: ActiveTmuxClients = ActiveTmuxClients(),
        runtimeCache: TmuxSessionRuntimeCache = TmuxSessionRuntimeCache(),
        sshLeaseManager: SshLeaseManager = SshLeaseManager(
            connector = SshLeaseConnector { target ->
                error("unexpected SSH lease connect for ${target.leaseKey}")
            },
            idleTtlMillis = 0L,
        ),
        sessionLifecycleSignals: SessionLifecycleSignals? = null,
    ): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = registry,
        runtimeCache = runtimeCache,
        sshLeaseManager = sshLeaseManager,
        sessionLifecycleSignals = sessionLifecycleSignals,
    )

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    @Test
    fun panesStateFlowStartsEmpty() {
        val vm = newVm()
        assertTrue(vm.panes.value.isEmpty())
    }

    @Test
    fun connectionStatusStartsIdle() {
        val vm = newVm()
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Idle)
    }

    @Test
    fun killCurrentSessionBroadcastsSignalOnConfirmedKill() = runTest {
        val signals = SessionLifecycleSignals()
        val vm = newVm(sessionLifecycleSignals = signals)
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "doomed",
            client = client,
        )
        runCurrent()

        val killed = async { signals.killedSessions.first() }
        runCurrent()

        vm.killCurrentSession()
        runCurrent()
        // tmux acknowledges the teardown.
        client.emittedEvents.emit(ControlEvent.SessionsChanged)
        advanceUntilIdle()

        val event = killed.await()
        assertEquals(7L, event.hostId)
        assertEquals("doomed", event.sessionName)
        assertTrue(
            "expected kill-session command, got ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("kill-session -t 'doomed'") },
        )
    }

    @Test
    fun killCurrentSessionDoesNotBroadcastWhenTmuxReportsError() = runTest {
        val signals = SessionLifecycleSignals()
        val vm = newVm(sessionLifecycleSignals = signals)
        val client = FakeTmuxClient().apply {
            // tmux rejects the kill (e.g. session already gone / bad target).
            responses += CommandResponse(number = 1L, output = listOf("can't find session"), isError = true)
        }
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

        assertNull("a tmux %error kill must not broadcast a lifecycle signal", broadcast)
        collector.cancel()
    }

    @Test
    fun commandTimeoutDuringReconcileSurfacesFailedStatus() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "list-panes"
            closeAndThrowException = TmuxClientException(
                "tmux command `list-panes` timed out after 100ms",
            )
        }
        vm.attachClientForTest(client)
        runCurrent()

        client.emittedEvents.emit(ControlEvent.WindowAdd("", "@1", ""))
        advanceUntilIdle()

        assertTrue(
            "expected list-panes reconcile, got ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("list-panes") },
        )
        val status = vm.connectionStatus.value
        assertTrue(
            "expected Failed after tmux command timeout, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Disconnected from test@test:0. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
    }

    @Test
    fun attachReadinessTimeoutFailsWithRetryableMessageAndClosesClient() = runTest {
        val vm = newVm()
        vm.setAttachPanesReadyTimeoutForTest(500L)
        val client = FakeTmuxClient().apply {
            suspendForeverOnCommandPrefix = "list-panes"
        }

        val attach = async {
            vm.attachClientWithReadinessForTest(
                hostId = 1L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = client,
            )
        }
        runCurrent()

        assertTrue(
            "precondition: attach must be visibly connecting",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connecting,
        )

        advanceTimeBy(501L)
        advanceUntilIdle()
        attach.await()

        val status = vm.connectionStatus.value
        assertTrue(
            "stalled list-panes must fail the connect, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Timed out waiting for tmux panes from work. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
        assertTrue("Reconnect must remain available after attach timeout", vm.canReconnect.value)
        assertTrue("timed-out attach must close the tmux client", client.closed)
    }

    @Test
    fun attachReadinessRetriesEmptyPaneListUntilPanesArrive() = runTest {
        val vm = newVm()
        vm.setAttachPanesReadyTimeoutForTest(1_000L)
        val client = FakeTmuxClient().apply {
            responses += CommandResponse(number = 1L, output = emptyList(), isError = false)
            responses += CommandResponse(
                number = 2L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            )
        }

        val attach = async {
            vm.attachClientWithReadinessForTest(
                hostId = 1L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = client,
            )
        }
        runCurrent()
        advanceTimeBy(ATTACH_PANES_READY_RETRY_MS + 1L)
        advanceUntilIdle()
        attach.await()

        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(listOf("%0"), vm.panes.value.map { it.paneId })
        assertEquals(
            "attach readiness should retry list-panes after an empty response",
            2,
            client.sentCommands.count { it.startsWith("list-panes") },
        )
    }

    @Test
    fun applyParsedPanesPopulatesStateFlowWithOnePerPane() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "left", paneIndex = 0),
                TmuxSessionViewModel.ParsedPane("%1", "@0", "$0", "right", paneIndex = 1),
            ),
        )
        advanceUntilIdle()

        val panes = vm.panes.value
        assertEquals(2, panes.size)
        assertEquals("%0", panes[0].paneId)
        assertEquals("left", panes[0].title)
        assertEquals("@0", panes[0].windowId)
        assertEquals("$0", panes[0].sessionId)
        assertEquals("%1", panes[1].paneId)
        assertEquals("right", panes[1].title)
    }

    @Test
    fun applyParsedPanesSortsByWindowThenPaneIndex() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        // Input arrives in a deliberately scrambled order to make sure
        // the sort actually runs.
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%2", "@1", "$0", "win1-b", paneIndex = 1),
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "win0-a", paneIndex = 0),
                TmuxSessionViewModel.ParsedPane("%1", "@1", "$0", "win1-a", paneIndex = 0),
            ),
        )
        advanceUntilIdle()

        val order = vm.panes.value.map { it.paneId }
        assertEquals(listOf("%0", "%1", "%2"), order)
    }

    @Test
    fun reusesTerminalStateAcrossReconcilesForSamePaneId() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "before", paneIndex = 0)),
        )
        advanceUntilIdle()
        val firstState = vm.panes.value.single().terminalState

        // Re-reconcile with the same pane ID but updated title; the
        // TerminalSurfaceState must be reused so the emulator's
        // scrollback survives. This is the central reason the reconcile
        // path keys by paneId rather than re-creating rows.
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "after", paneIndex = 0)),
        )
        advanceUntilIdle()
        val secondRow = vm.panes.value.single()
        assertSame(firstState, secondRow.terminalState)
        assertEquals("after", secondRow.title)
    }

    @Test
    fun newPaneInReconcileGetsDistinctTerminalState() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0)),
        )
        advanceUntilIdle()
        val firstState = vm.panes.value.single().terminalState

        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0),
                TmuxSessionViewModel.ParsedPane("%1", "@0", "$0", "b", paneIndex = 1),
            ),
        )
        advanceUntilIdle()
        val rows = vm.panes.value
        assertEquals(2, rows.size)
        // Existing pane keeps its state.
        assertSame(firstState, rows[0].terminalState)
        // New pane has its own state.
        assertNotSame(firstState, rows[1].terminalState)
    }

    @Test
    fun closedPaneIsDroppedFromState() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0),
                TmuxSessionViewModel.ParsedPane("%1", "@0", "$0", "b", paneIndex = 1),
            ),
        )
        advanceUntilIdle()
        assertEquals(2, vm.panes.value.size)

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0)),
        )
        advanceUntilIdle()
        assertEquals(listOf("%0"), vm.panes.value.map { it.paneId })
    }

    @Test
    fun windowAddEventTriggersListPanesAndPopulatesState() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\tshell\t0"),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        assertEquals(1, vm.panes.value.size)
        assertEquals("%0", vm.panes.value.single().paneId)
        // The reconcile must have round-tripped a list-panes call through
        // sendCommand.
        assertTrue(
            "expected a list-panes command, got ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("list-panes") },
        )
    }

    @Test
    fun newPaneReconcileCapturesExistingVisibleContent() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("issue103-line-001", "issue103-line-002"),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        assertTrue(
            "expected a capture-pane prefill for the new pane, got ${client.sentCommands}",
            client.sentCommands.contains("capture-pane -p -e -S -200 -t %0"),
        )
    }

    @Test
    fun newPaneReconcileQueriesCursorPositionForTheSeed() = runTest {
        // Issue #259: after the capture-pane snapshot the seed path must ask
        // tmux for the pane's true cursor so the emulator's cursor can be
        // restored — otherwise the agent's next in-place spinner rewrite paints
        // on the wrong row and fragments of different frames coexist.
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("> committed", "Beboppin'... (thinking)"),
                isError = false,
            ),
        )
        client.cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,1"), isError = false),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        assertTrue(
            "expected a cursor-position query for the new pane, got ${client.sentCommands}",
            client.sentCommands.contains(
                "display-message -p -t %0 '#{cursor_x},#{cursor_y}'",
            ),
        )
        // The cursor query must come AFTER the capture for the same pane so the
        // builder can append the restore to the replayed snapshot.
        val captureIdx = client.sentCommands.indexOf("capture-pane -p -e -S -200 -t %0")
        val cursorIdx = client.sentCommands.indexOf(
            "display-message -p -t %0 '#{cursor_x},#{cursor_y}'",
        )
        assertTrue("capture must precede cursor query", captureIdx in 0 until cursorIdx)
    }

    @Test
    fun attachReadinessRecordsTmuxLatencyMetrics() = runTest {
        TmuxSessionLatencyTelemetry.resetForTest()
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("issue337-visible-output"),
                isError = false,
            ),
        )
        client.cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )

        vm.attachClientWithReadinessForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        client.emittedEvents.emit(ControlEvent.Output("%0", "live".toByteArray()))
        advanceUntilIdle()

        val names = TmuxSessionLatencyTelemetry.snapshot().map { it.name }
        assertTrue("expected list-panes timing in $names", "list_panes" in names)
        assertTrue("expected capture-pane timing in $names", "capture_pane" in names)
        assertTrue("expected cursor-query timing in $names", "cursor_query" in names)
        assertTrue(
            "expected append-to-buffer timing in $names",
            "terminal_output_append_to_buffer" in names,
        )
        assertTrue("expected first visible output timing in $names", "first_visible_output" in names)

        val artifactLines = TmuxSessionLatencyTelemetry.snapshot().map { it.toArtifactLine() }
        artifactLines.forEach(::println)
        assertTrue(
            "CI unit test reports should contain artifact-shaped timing lines, got $artifactLines",
            artifactLines.any { it.startsWith("tmux_latency_list_panes_ms=") },
        )
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun warmSwitchReadinessTelemetryFollowsAttachOrdering() = runTest {
        TmuxSessionLatencyTelemetry.resetForTest()
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\tother\tshell\t0"),
                isError = false,
            ),
        )
        client.responses.addLast(
            CommandResponse(
                number = 4L,
                output = listOf("%0\t@0\t\$0\tother\tshell\t0"),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("warm switch seed"),
                isError = false,
            ),
        )
        client.cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )

        vm.attachClientWithReadinessForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = client,
            trigger = TmuxConnectTrigger.FastSwitch,
        )
        vm.resizeRemotePty(columns = 100, rows = 30)
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.LayoutChange(sessionId = "", windowId = "@0", layout = "bf3d,80x24"),
        )
        advanceUntilIdle()

        val warmEvents = TmuxSessionLatencyTelemetry.snapshot()
            .filter { it.name.startsWith("warm_switch_") }
        val names = warmEvents.map { it.name }
        val expectedOrder = listOf(
            "warm_switch_start",
            "warm_switch_selected_session_state",
            "warm_switch_tmux_shell_attached",
            "warm_switch_pane_list_ready",
            "warm_switch_terminal_bridge_ready",
            "warm_switch_terminal_capture_ready",
            "warm_switch_panes_ready",
            "warm_switch_connect_ready",
            "warm_switch_remote_refresh_complete",
        )
        var previousIndex = -1
        var previousEvent = "start"
        for (event in expectedOrder) {
            val index = names.indexOf(event)
            assertTrue("expected $event in warm switch telemetry $names", index >= 0)
            assertTrue(
                "expected $event after $previousEvent in $names",
                index > previousIndex,
            )
            previousIndex = index
            previousEvent = event
        }
        assertTrue(
            "warm switch telemetry should be tagged with fast-switch: $warmEvents",
            warmEvents.all { it.trigger == TmuxConnectTrigger.FastSwitch.logValue },
        )
        assertEquals(
            "pane-list readiness must be a one-shot milestone after attach",
            1,
            names.count { it == "warm_switch_pane_list_ready" },
        )
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun parseTmuxPaneCursorReadsWellFormedReply() {
        // Issue #259: the cursor reply is `cursor_x,cursor_y` (0-based).
        assertEquals(TmuxPaneCursor(column = 0, row = 2), parseTmuxPaneCursor("0,2"))
        assertEquals(TmuxPaneCursor(column = 12, row = 5), parseTmuxPaneCursor(" 12 , 5 "))
    }

    @Test
    fun parseTmuxPaneCursorRejectsMalformedReplies() {
        // Issue #259: a missing/old/malformed reply degrades to no restore.
        assertNull(parseTmuxPaneCursor(null))
        assertNull(parseTmuxPaneCursor(""))
        assertNull(parseTmuxPaneCursor("3"))
        assertNull(parseTmuxPaneCursor("a,b"))
        assertNull(parseTmuxPaneCursor("1,2,3"))
        assertNull(parseTmuxPaneCursor("-1,2"))
        assertNull(parseTmuxPaneCursor("1,-2"))
    }

    @Test
    fun existingPaneReconcileDoesNotRecaptureContent() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.responses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.LayoutChange(sessionId = "", windowId = "@0", layout = "bf3d,80x24"),
        )
        advanceUntilIdle()

        assertEquals(1, client.sentCommands.count { it.startsWith("capture-pane") })
    }

    @Test
    fun reconcileScopesPanesAndWindowSummariesToActiveSession() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "%0\t@0\t\$0\twork\tshell\t0",
                    "%1\t@1\t\$0\twork\teditor\t0",
                    "%2\t@2\t\$1\tother\tlogs\t0",
                    "%3\t@3\t\$1\tother\tbuild\t0",
                ),
                isError = false,
            ),
        )
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "\$0", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        val command = client.sentCommands.single { it.startsWith("list-panes") }
        // Per #158: reconcilePanes now uses `-s` so the response covers
        // every window in the target session, not only the current
        // window. Without `-s`, a `new-window` reconcile would replace
        // the prior window's panes with just the new pane and the
        // WindowStrip would never see two entries.
        assertTrue(command.startsWith("list-panes -s -t 'work' -F "))
        assertTrue(command.contains(LIST_PANES_FIELD_SEPARATOR))
        assertFalse(command.contains(" -a "))

        val panes = vm.panes.value
        assertEquals(listOf("%0", "%1"), panes.map { it.paneId })
        assertEquals(listOf("@0", "@1"), panes.map { it.windowId })
        // Per #158: window summaries now carry a readable 1-based
        // ordinal as their title instead of the bare `@N` tmux ID.
        assertEquals(
            listOf(
                WindowSummary(windowId = "@0", title = "Window 1"),
                WindowSummary(windowId = "@1", title = "Window 2"),
            ),
            panes.toWindowSummaries(),
        )
    }

    @Test
    fun layoutChangeEventTriggersListPanesReconcile() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        // Two responses: bootstrap and layout-change reconcile.
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\tone\t0"),
                isError = false,
            ),
        )
        client.responses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf(
                    "%0\t@0\t\$0\tone\t0",
                    "%1\t@0\t\$0\ttwo\t1",
                ),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(1, vm.panes.value.size)

        client.emittedEvents.emit(
            ControlEvent.LayoutChange(sessionId = "", windowId = "@0", layout = "bf3d,80x24"),
        )
        advanceUntilIdle()
        assertEquals(2, vm.panes.value.size)
    }

    @Test
    fun windowCloseEventTriggersReconcileThatDropsThePane() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\ta\t0"),
                isError = false,
            ),
        )
        // Second reconcile after %window-close returns nothing — the
        // window (and its pane) are gone.
        client.responses.addLast(
            CommandResponse(number = 2L, output = emptyList(), isError = false),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(1, vm.panes.value.size)

        client.emittedEvents.emit(
            ControlEvent.WindowClose(sessionId = "", windowId = "@0"),
        )
        advanceUntilIdle()
        assertTrue(vm.panes.value.isEmpty())
    }

    @Test
    fun listPanesErrorLeavesExistingPaneListUntouched() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\ta\t0"),
                isError = false,
            ),
        )
        // Second response: a tmux error (e.g. server shutting down).
        client.responses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("server is shutting down"),
                isError = true,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        val before = vm.panes.value
        assertEquals(1, before.size)

        client.emittedEvents.emit(
            ControlEvent.LayoutChange(sessionId = "", windowId = "@0", layout = "bf3d"),
        )
        advanceUntilIdle()
        // Error must NOT wipe the state.
        val after = vm.panes.value
        assertEquals(1, after.size)
        assertSame(before.single().terminalState, after.single().terminalState)
    }

    @Test
    fun unrelatedEventDoesNotTriggerListPanes() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        client.emittedEvents.emit(ControlEvent.SessionsChanged)
        client.emittedEvents.emit(
            ControlEvent.Output(paneId = "%0", data = "hi".toByteArray()),
        )
        advanceUntilIdle()

        assertTrue(
            "no list-panes should fire on Output / SessionsChanged",
            client.sentCommands.none { it.startsWith("list-panes") },
        )
    }

    @Test
    fun writeInputToPaneIssuesSendKeysWithLiteralBytes() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Submission semantics: a `\r` byte (carriage return) is the
        // "Enter / submit" signal coming from the terminal emulator
        // and from the in-app callers (chips, snippets with-Enter,
        // composer send) — see [TmuxSessionScreen]. The single-line
        // route keeps the existing two-token send-keys shape so
        // keyboard typing still submits cleanly.
        //
        // A literal `\n` byte is reserved for the bracketed-paste
        // multi-line route (issue #209); we cover that in
        // [writeInputToPaneWrapsMultiLineInputInBracketedPaste].
        vm.writeInputToPane("%0", "ls\r".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(sent.toString(), 2, sent.size)
        assertEquals("send-keys -l -t %0 -- 'ls'", sent[0])
        assertEquals("send-keys -t %0 Enter", sent[1])
    }

    @Test
    fun writeInputToPaneExitsTmuxCopyModeBeforeSendingBytes() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "shell",
                    paneIndex = 0,
                    inCopyMode = true,
                ),
            ),
        )

        val result = vm.writeInputToPaneResult("%0", "ls\r".toByteArray(Charsets.UTF_8))
        runCurrent()

        assertTrue("copy-mode recovery should keep pane input successful", result.isSuccess)
        assertFalse("copy-mode recovery must not mark tmux disconnected", client.disconnected.value)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(
            listOf(
                "send-keys -X -t %0 cancel",
                "send-keys -l -t %0 -- 'ls'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
        assertFalse(vm.panes.value.single { it.paneId == "%0" }.inCopyMode)
    }

    @Test
    fun writeInputToPaneSendKeysFailureSurfacesFailedStatus() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "send-keys"
            closeAndThrowException = TmuxClientException(
                "tmux command `send-keys` timed out after 100ms",
            )
        }
        vm.attachClientForTest(client)
        runCurrent()

        vm.writeInputToPane("%0", "ls\r".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        assertTrue(
            "expected send-keys dispatch, got ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("send-keys") },
        )
        val status = vm.connectionStatus.value
        assertTrue(
            "expected Failed after send-keys failure, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Disconnected from test@test:0. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
    }

    @Test
    fun eofDisconnectUnregistersDeadClientAndPreservesReconnectTarget() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
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
        runCurrent()
        assertSame(client, registry.clients.value[7L]?.client)

        client.disconnectedSignal.value = true
        runCurrent()

        assertTrue(
            "dead active tmux client must be removed from dashboard registry",
            registry.clients.value.isEmpty(),
        )
        assertEquals("work", vm.activeSessionNameForTest())
        assertTrue("Reconnect must remain available after EOF disconnect", vm.canReconnect.value)
        val status = vm.connectionStatus.value
        assertTrue(
            "expected Failed after EOF disconnect, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Disconnected from alex@alpha.example:22. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
    }

    @Test
    fun eofDisconnectWaitsForExplicitReconnect() = runTest {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        val deadClient = FakeTmuxClient()
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
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

        deadClient.disconnectedSignal.value = true
        runCurrent()

        assertEquals(
            "passive EOF should not start a visible auto-reconnect loop before the user taps Reconnect",
            0,
            connector.connectCount,
        )
        assertEquals("work", vm.activeSessionNameForTest())
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed)
        assertTrue("manual reconnect must remain available after EOF disconnect", vm.canReconnect.value)

        assertTrue(vm.reconnect())
        advanceUntilIdle()

        assertEquals(1, connector.connectCount)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals("work", vm.activeSessionNameForTest())
    }

    @Test
    fun briefPassiveEofSilentlyReattachesWithoutDisconnectBandOrConnectAttempt() = runTest {
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 1_000L, silentReattachTimeoutMs = 1_000L)
        val session = FakeSshSession()
        val deadClient = FakeTmuxClient()
        val replacementClient = FakeTmuxClient().withSinglePane("work", "%1")
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            replacementClient
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = session,
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceUntilIdle()

        assertSame(replacementClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals("work", vm.activeSessionNameForTest())
        assertEquals(
            "silent same-SSH reattach must not count as a logical reconnect attempt",
            1,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        assertTrue("stale client should be closed after silent reattach", deadClient.closed)
        assertTrue("replacement control client should be opened", replacementClient.connectCalled)
    }

    @Test
    fun passiveEofShowsReconnectOnlyAfterSilentGraceExpires() = runTest {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = FailingLeaseConnector(IOException("network still unavailable"))
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 500L, silentReattachTimeoutMs = 500L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
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
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceTimeBy(499L)
        runCurrent()

        assertTrue(
            "disconnect band must be suppressed during the bounded grace",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertSame(
            "dashboard registry should still point at the held client during grace",
            deadClient,
            registry.clients.value[7L]?.client,
        )
        assertEquals(
            "silent fresh-transport probing must be bounded during grace",
            1,
            connector.connectCount,
        )

        advanceTimeBy(2L)
        runCurrent()

        val status = vm.connectionStatus.value
        assertTrue(
            "sustained passive EOF should surface the manual reconnect state after grace, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(registry.clients.value.isEmpty())
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
    }

    @Test
    fun passiveEofSilentlyReacquiresTransportWhenOldSessionIsBroken() = runTest {
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 1_000L, silentReattachTimeoutMs = 1_000L)
        val deadClient = FakeTmuxClient()
        val replacementClient = FakeTmuxClient().withSinglePane("work", "%1")
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            replacementClient
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceUntilIdle()

        assertEquals(1, connector.connectCount)
        assertSame(replacementClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(
            "hidden fresh-transport recovery must not increment the user-visible connect counter",
            1,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        assertTrue(replacementClient.connectCalled)
    }

    @Test
    fun failedSilentTransportReattachEvictsLeaseSoManualReconnectUsesFreshSession() = runTest {
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val failedReconnectSession = FakeSshSession()
        val manualReconnectSession = FakeSshSession()
        val connector = QueueLeaseConnector(failedReconnectSession, manualReconnectSession)
        val manager = SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        val vm = newVm(
            registry = registry,
            sshLeaseManager = manager,
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 100L, silentReattachTimeoutMs = 1L)
        val deadClient = FakeTmuxClient()
        val failedAttachClient = FakeTmuxClient()
        val manualReconnectClient = FakeTmuxClient().withSinglePane("work", "%9")
        val sessionsSeenByFactory = mutableListOf<com.pocketshell.core.ssh.SshSession>()
        val clients = ArrayDeque(listOf(failedAttachClient, manualReconnectClient))
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            sessionsSeenByFactory += session
            clients.removeFirstOrNull() ?: error("unexpected tmux client factory call")
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceUntilIdle()

        assertTrue(
            "failed hidden reattach should surface manual reconnect",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            "failed hidden reattach must close the half-attached tmux client",
            failedAttachClient.closed,
        )
        assertTrue(
            "failed hidden reattach must evict the acquired SSH lease instead of idling it",
            failedReconnectSession.closed,
        )
        assertEquals(1, connector.connectCount)

        assertTrue("manual reconnect should be available after failed hidden reattach", vm.reconnect())
        advanceUntilIdle()

        assertEquals(
            "manual reconnect must open a fresh SSH session, not reuse the failed hidden lease",
            2,
            connector.connectCount,
        )
        assertEquals(
            listOf(failedReconnectSession, manualReconnectSession),
            sessionsSeenByFactory,
        )
        assertSame(manualReconnectClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun manualReconnectCancelsInFlightSilentTransportReattachAndUsesFreshSession() = runTest {
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val hiddenReconnectSession = FakeSshSession()
        val manualReconnectSession = FakeSshSession()
        val connector = QueueLeaseConnector(hiddenReconnectSession, manualReconnectSession)
        val manager = SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        val vm = newVm(
            registry = registry,
            sshLeaseManager = manager,
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 60_000L, silentReattachTimeoutMs = 60_000L)
        val deadClient = FakeTmuxClient()
        val hiddenAttachGate = CompletableDeferred<Unit>()
        val hiddenAttachClient = FakeTmuxClient().apply {
            sendCommandGatePrefix = "list-panes"
            sendCommandGate = hiddenAttachGate
        }
        val manualReconnectClient = FakeTmuxClient().withSinglePane("work", "%9")
        val clients = ArrayDeque(listOf(hiddenAttachClient, manualReconnectClient))
        val sessionsSeenByFactory = mutableListOf<com.pocketshell.core.ssh.SshSession>()
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            sessionsSeenByFactory += session
            clients.removeFirstOrNull() ?: error("unexpected tmux client factory call")
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        runCurrent()

        assertTrue(
            "hidden reattach should be stalled while waiting for panes",
            hiddenAttachClient.sentCommands.any { it.startsWith("list-panes") },
        )
        assertEquals(1, connector.connectCount)

        assertTrue("manual reconnect should be accepted while hidden reattach is in flight", vm.reconnect())
        advanceUntilIdle()

        assertTrue(
            "interrupted hidden reattach must close its half-attached tmux client",
            hiddenAttachClient.closed,
        )
        assertTrue(
            "interrupted hidden reattach must evict its fresh SSH lease",
            hiddenReconnectSession.closed,
        )
        assertEquals(
            "manual reconnect must open a fresh SSH session after interrupting hidden reattach",
            2,
            connector.connectCount,
        )
        assertEquals(
            listOf(hiddenReconnectSession, manualReconnectSession),
            sessionsSeenByFactory,
        )
        assertSame(manualReconnectClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun networkChangeLifecycleHookEntersReconnectingWithoutConnectionError() = runTest {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
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
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "validated-default-network-changed"),
        )
        runCurrent()

        assertEquals(
            "network change should not wait for the tmux reader EOF",
            0,
            connector.connectCount,
        )
        val status = vm.connectionStatus.value
        assertTrue(
            "network change must show reconnect-in-progress, not a connection error; got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertTrue(
            "reconnect reason should avoid misleading manual retry wording",
            "Tap Reconnect" !in (status as TmuxSessionViewModel.ConnectionStatus.Reconnecting).reason,
        )
        assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
        assertTrue("manual reconnect remains available during proactive reconnect", vm.canReconnect.value)
        assertTrue(
            "proactive reconnect should remove the stale active client from the registry",
            registry.clients.value.isEmpty(),
        )
    }

    @Test
    fun restoredSameNetworkHintDuringActiveTerminalOutputDoesNotShowReconnect() = runTest {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L, 0L))
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
                    "%0",
                    "@0",
                    "\$0",
                    "work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        advanceUntilIdle()

        val state = vm.panes.value.single().terminalState
        val slowSideChannelCollector = launch {
            state.output.collect {
                delay(60_000)
            }
        }
        runCurrent()

        try {
            client.emittedEvents.emit(ControlEvent.Output("%0", issue576BackpressureOutputChunks().first()))
            runCurrent()

            val hook = registry.lifecycleHooksSnapshot().single()
            repeat(4) { index ->
                hook.onNetworkChanged(
                    networkChange(
                        previous = TerminalNetworkSnapshot.NoValidatedNetwork,
                        current = TerminalNetworkSnapshot.Validated("wifi"),
                        previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                        reason = "validated-network-during-output-$index",
                    ),
                )
                runCurrent()
            }

            assertTrue(
                "active terminal output proves the tmux stream is alive; network hints must not show reconnect",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertEquals(
                "network hints during active output must not enqueue reconnect attempts",
                0,
                connector.connectCount,
            )
            assertEquals(
                "network hints during active output must not increment the reconnect counter",
                0,
                TMUX_CONNECT_ATTEMPTS.get(),
            )
            assertFalse(
                "terminal-side backpressure must not flip the tmux disconnected signal",
                client.disconnectedSignal.value,
            )
        } finally {
            slowSideChannelCollector.cancel()
        }
    }

    @Test
    fun outputForOnlyTerminalOutputKeepsNetworkHintFromShowingReconnect() = runTest {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))
        val client = FakeTmuxClient()
        client.decoupleOutputForFromEvents = true
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
                    "%0",
                    "@0",
                    "\$0",
                    "work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        advanceUntilIdle()

        client.emittedPaneOutputs.emit(ControlEvent.Output("%0", "visible via outputFor".toByteArray()))
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.NoValidatedNetwork,
                current = TerminalNetworkSnapshot.Validated("wifi"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "same-network-hint-after-outputFor-only-output",
            ),
        )
        runCurrent()

        assertTrue(
            "visible output delivered without client.events %output must still suppress same-network reconnect",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(0, connector.connectCount)
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
    }

    @Test
    fun firstValidatedNetworkHintDoesNotReconnectIdleStableTerminal() = runTest {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        advanceUntilIdle()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.NoValidatedNetwork,
                current = TerminalNetworkSnapshot.Validated("wifi"),
                previousValidated = null,
                reason = "first-validated-network",
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "first validated callback is startup state discovery, not proof that SSH died",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(0, connector.connectCount)
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
    }

    @Test
    fun realNetworkIdentityChangeDuringActiveTerminalOutputStillReconnects() = runTest {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
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
        runCurrent()
        client.emittedEvents.emit(ControlEvent.Output("%0", "still streaming".toByteArray()))
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "wifi-cellular-handoff",
            ),
        )
        runCurrent()

        assertEquals(
            "real validated identity change should still use proactive reconnect despite recent output",
            TmuxConnectTrigger.NetworkReconnect,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting)
        assertEquals(0, connector.connectCount)
    }

    @Test
    fun networkReconnectAndPassiveDisconnectAreCoalescedIntoOneAttempt() = runTest {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )
        advanceUntilIdle()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "wifi-cellular-handoff"),
        )
        runCurrent()
        oldClient.disconnectedSignal.value = true
        runCurrent()

        assertTrue(
            "passive EOF during a scheduled network reconnect must not replace it with a second loop",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertEquals(0, connector.connectCount)
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())

        advanceTimeBy(60_000L)
        advanceUntilIdle()

        assertEquals(
            "only the already scheduled network reconnect should open a transport",
            1,
            connector.connectCount,
        )
        assertEquals(1, TMUX_CONNECT_ATTEMPTS.get())
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
    }

    @Test
    fun networkChangeLifecycleHookProactivelyReattachesTmuxSession() = runTest {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "validated-default-network-changed"),
        )
        advanceUntilIdle()

        assertEquals(1, connector.connectCount)
        assertTrue("old tmux control client must be detached during reconnect", oldClient.detachCleanlyCalled)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
    }

    @Test
    fun networkReconnectRetriesTransientFlapThenRecoversWithoutOverlappingAttempts() = runTest {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = FailingThenConnectingLeaseConnector(
            failures = listOf(
                SshException("SSH connect to alex@alpha.example:22 failed: temporary link cut"),
                SshException("SSH connect to alex@alpha.example:22 failed: transient latency timeout"),
            ),
            session = FakeSshSession(),
        )
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L, 250L, 250L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "wifi-cellular-flap"),
        )
        runCurrent()

        assertEquals(
            "first network reconnect attempt should run immediately and fail transiently",
            1,
            connector.connectCount,
        )
        assertTrue(
            "after the first transient failure the VM should stay in the bounded reconnect loop",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertEquals(1, TMUX_CONNECT_ATTEMPTS.get())

        advanceTimeBy(250L)
        runCurrent()

        assertEquals(
            "second network reconnect attempt should be the next bounded backoff step",
            2,
            connector.connectCount,
        )
        assertTrue(
            "after the second transient failure the VM should still wait for the final retry",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertEquals(2, TMUX_CONNECT_ATTEMPTS.get())

        advanceTimeBy(250L)
        advanceUntilIdle()

        assertEquals(
            "third network reconnect attempt should recover when the link returns",
            3,
            connector.connectCount,
        )
        assertEquals(
            "bounded reconnect loop must not overlap SSH dials while the network is flapping",
            1,
            connector.maxConcurrentConnects,
        )
        assertTrue("old tmux client must be closed during reconnect", oldClient.closed)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(
            "expected network reconnect to recover to Connected, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(3, TMUX_CONNECT_ATTEMPTS.get())
        assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
    }

    @Test
    fun eofDisconnectDoesNotBurnAutoReconnectAttempts() = runTest {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))
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

        deadClient.disconnectedSignal.value = true
        advanceUntilIdle()

        val status = vm.connectionStatus.value
        assertTrue(
            "expected manual reconnect failure state, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Disconnected from alex@alpha.example:22. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
        assertEquals("work", vm.activeSessionNameForTest())
        assertTrue("manual reconnect must remain available after passive EOF", vm.canReconnect.value)
    }

    @Test
    fun explicitReconnectAfterEofReportsNonRetryableAuthFailureOnce() = runTest {
        // Issue #440: a non-retryable failure (auth rejection) must NOT burn
        // the whole backoff schedule when a passive EOF has already surfaced
        // the manual Reconnect affordance.
        val registry = ActiveTmuxClients()
        val connector = FailingLeaseConnector(
            SshException(
                "SSH connect to alex@alpha.example:22 failed: UserAuthException: auth fail",
                UserAuthException("Exhausted available authentication methods"),
            ),
        )
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        // Four delays available — if the abort fails, all four would be used.
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L, 0L))
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

        deadClient.disconnectedSignal.value = true
        runCurrent()

        assertEquals(0, connector.connectCount)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed)

        assertTrue(vm.reconnect())
        advanceUntilIdle()

        assertEquals(
            "explicit reconnect after passive EOF must make one SSH attempt",
            1,
            connector.connectCount,
        )
        val status = vm.connectionStatus.value
        assertTrue(
            "expected Failed after non-retryable auth failure, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        val message = (status as TmuxSessionViewModel.ConnectionStatus.Failed).message
        assertTrue(
            "Failed message must explain the non-retryable cause, was: $message",
            message.contains("auth fail") || message.contains("authentication failed"),
        )
        assertFalse(
            "non-retryable abort must not report exhausting all attempts, was: $message",
            message.contains("Auto reconnect failed after"),
        )
    }

    @Test
    fun explicitReconnectAfterEofDoesNotLoopOnTransientFailure() = runTest {
        // Issue #440: a transient transport failure (e.g. connection refused
        // while the host reboots) should not become a reconnect storm after a
        // passive EOF has surfaced the manual Reconnect affordance.
        val registry = ActiveTmuxClients()
        val connector = FailingThenConnectingLeaseConnector(
            failures = listOf(
                SshException(
                    "SSH connect to alex@alpha.example:22 failed: ConnectException: Connection refused",
                    ConnectException("Connection refused"),
                ),
            ),
            session = FakeSshSession(),
        )
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L))
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

        deadClient.disconnectedSignal.value = true
        runCurrent()

        assertEquals(0, connector.connectCount)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed)

        assertTrue(vm.reconnect())
        advanceUntilIdle()

        assertEquals(
            "explicit reconnect after passive EOF should not retry in a tight loop",
            1,
            connector.connectCount,
        )
        assertTrue(
            "expected Failed after one transient reconnect failure, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
    }

    @Test
    fun tmuxAutoReconnectDelayIsCancelledWhenAppBackgrounds() = runTest {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
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

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "validated-default-network-changed"),
        )
        runCurrent()
        assertTrue(
            "network reconnect must enter retry delay before background",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )

        vm.onAppBackgrounded()
        advanceUntilIdle()
        advanceTimeBy(60_000L)
        advanceUntilIdle()

        assertEquals(
            "backgrounding during retry delay must cancel tmux reconnect attempts",
            0,
            connector.connectCount,
        )
        val status = vm.connectionStatus.value
        assertTrue(
            "backgrounded reconnect should settle in a manual retry state, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
            status.message.contains("Auto reconnect paused while PocketShell is in the background."),
        )
        assertEquals("work", vm.connectingSessionNameForTest())
        assertTrue("manual reconnect remains available after background pause", vm.canReconnect.value)
    }

    @Test
    fun foregroundReturnResumesBackgroundPausedAutoReconnect() = runTest {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
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

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "validated-default-network-changed"),
        )
        runCurrent()
        assertTrue(
            "network reconnect must be waiting in auto-reconnect delay before background",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )

        vm.onAppBackgrounded()
        advanceUntilIdle()
        val backgroundStatus = vm.connectionStatus.value
        assertTrue(
            "background pause should be represented as Failed while app is not visible",
            backgroundStatus is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            (backgroundStatus as TmuxSessionViewModel.ConnectionStatus.Failed).message,
            backgroundStatus.message.contains("Auto reconnect paused while PocketShell is in the background."),
        )
        assertEquals(
            "backgrounding during retry delay must not connect",
            0,
            connector.connectCount,
        )

        vm.onAppForegrounded()
        advanceUntilIdle()

        assertEquals(
            "foreground return must resume the paused reconnect automatically",
            1,
            connector.connectCount,
        )
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(
            "foreground resume should reconnect, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        val foregroundStatus = vm.connectionStatus.value
        assertFalse(
            "stale background-paused copy must not remain visible in foreground",
            foregroundStatus.toString().contains("Auto reconnect paused while PocketShell is in the background."),
        )
    }

    @Test
    fun writeInputToPaneSeparatesLeadingDashLiteralFromTmuxOptions() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", "-tproof".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val cmd = client.sentCommands.single { it.startsWith("send-keys") }
        assertEquals("send-keys -l -t %0 -- '-tproof'", cmd)
    }

    @Test
    fun writeInputToPaneEscapesSingleQuotesViaCloseEscapeOpen() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%2", "it's".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        // Single-quote sequence: closes ', escapes the literal quote, then
        // reopens. The composer wraps with outer single quotes too.
        val cmd = client.sentCommands.single { it.startsWith("send-keys") }
        assertTrue(
            "expected POSIX-shell-style escape in $cmd",
            cmd == "send-keys -l -t %2 -- 'it'\\''s'",
        )
    }

    @Test
    fun terminalStateInputRoutesThroughTmuxSendKeys() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()

        vm.panes.value.single().terminalState.writeInput("echo ok\r".toByteArray(Charsets.UTF_8))
        waitForSentCommandCount(client, expectedCount = 2)

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals("send-keys -l -t %0 -- 'echo ok'", sent[0])
        assertEquals("send-keys -t %0 Enter", sent[1])
    }

    @Test
    fun codexScaleTmuxOutputFloodKeepsTerminalAndConnectionStateConsistent() = runTest {
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

            val completed = withTimeoutOrNull(5_000) {
                while (sender.isActive) {
                    shadowOf(Looper.getMainLooper()).idle()
                    runCurrent()
                    delay(10)
                }
                sender.await()
                true
            } ?: false

            assertTrue(
                "tmux %output flood must not stall terminal pane output",
                completed,
            )
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()

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
        } finally {
            outputCollector.cancel()
        }
    }

    @Test
    fun slowTerminalOutputSideChannelDoesNotMisclassifyTmuxPaneAsDisconnected() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "codex", paneIndex = 0)),
        )
        advanceUntilIdle()

        val state = vm.panes.value.single().terminalState
        val observedSideChannelChunks = AtomicInteger(0)
        val slowSideChannelCollector = launch {
            state.output.collect {
                observedSideChannelChunks.incrementAndGet()
                delay(60_000)
            }
        }
        runCurrent()

        try {
            val payloads = issue576BackpressureOutputChunks()
            val sender = async {
                payloads.forEach { bytes ->
                    client.emittedEvents.emit(ControlEvent.Output("%0", bytes))
                }
            }

            val completed = withTimeoutOrNull(5_000) {
                while (sender.isActive) {
                    shadowOf(Looper.getMainLooper()).idle()
                    runCurrent()
                    delay(10)
                }
                sender.await()
                true
            } ?: false

            assertTrue(
                "slow terminal output side-channel collector must not stall tmux pane output",
                completed,
            )
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                "slow side-channel collector should prove the secondary output flow was subscribed",
                observedSideChannelChunks.get() > 0,
            )
            assertTrue(
                "terminal-side backpressure must not be diagnosed as a transport connection error",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertFalse(
                "terminal-side backpressure must not flip the tmux disconnected signal",
                client.disconnectedSignal.value,
            )
            assertFalse(
                "terminal-side backpressure must not mark the local terminal surface as failed",
                vm.panes.value.single().surfaceError,
            )
        } finally {
            slowSideChannelCollector.cancel()
        }
    }

    @Test
    fun codexLikePaneOutputOverflowStaysLocalAndNeverReconnectsStableTransport() = runTest {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
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
            val completed = withTimeoutOrNull(5_000) {
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
            val disconnectUiStatuses = emittedConnectionStatuses
                .map { it.toUiStatus() }
                .filter { uiStatus ->
                    uiStatus == com.pocketshell.uikit.model.ConnectionStatus.Connecting ||
                        uiStatus == com.pocketshell.uikit.model.ConnectionStatus.Error
                }
            assertTrue(
                "Codex-like output overflow must not map to the breadcrumb Reconnecting/Disconnected UI; " +
                    "observed=${emittedConnectionStatuses.map { it.toUiStatus() }}",
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
            assertTrue(
                "overflowed pane should expose local terminal recovery instead of fake reconnect",
                vm.panes.value.single().surfaceError,
            )
        } finally {
            statusCollector.cancel()
            terminalSideChannelCollector.cancel()
            slowPaneOutputCollector.cancel()
        }
    }

    @Test
    fun terminalOutputBacklogOverflowIsLocalPaneErrorNotReconnect() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
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
    }

    @Test
    fun terminalSurfaceFailureDoesNotMarkTmuxTransportDisconnectedOrReconnect() = runTest {
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
    fun repeatedTerminalSurfaceFailuresStopAtErrorStateInsteadOfReconnectStorm() = runTest {
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

    @Test
    fun tmuxHighRateInputStressBatchesWithBoundedBacklogAndNoContentLoss() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            sendCommandDelayMs = 3L
        }
        vm.attachClientForTest(client)
        val sink = vm.tmuxInputSinkForTest("%0")
        val chunks = List(2_000) { index ->
            ("stress-${index.toString().padStart(4, '0')}-" + "x".repeat(51))
                .toByteArray(Charsets.US_ASCII)
        }
        val expected = chunks.fold(ByteArray(0)) { acc, bytes -> acc + bytes }

        val writer = Thread({
            chunks.forEach { sink.write(it) }
        }, "tmux-input-stress-writer")
        writer.start()

        waitForTmuxInputBytes(vm, paneId = "%0", expectedBytes = expected.size.toLong(), writer = writer)
        writer.join(1_000)
        sink.close()

        val metrics = vm.tmuxInputMetricsForTest("%0")
            ?: error("stress metrics should be recorded")
        assertEquals(expected.size.toLong(), metrics.totalEnqueuedBytes)
        assertEquals(expected.size.toLong(), metrics.totalSentBytes)
        assertTrue(
            "stress should build real backlog; metrics=$metrics",
            metrics.maxPendingBytes > TMUX_INPUT_MAX_BATCH_BYTES,
        )
        assertTrue(
            "backlog must stay within bounded queue capacity; metrics=$metrics",
            metrics.maxPendingBytes <= vm.tmuxInputCapacityBytesForTest(),
        )
        assertTrue("input should be batched; metrics=$metrics", metrics.sentBatchCount < chunks.size)
        assertTrue("batch size metric should be recorded; metrics=$metrics", metrics.maxBatchBytes > chunks.first().size)
        assertTrue("batch size must remain bounded; metrics=$metrics", metrics.maxBatchBytes <= TMUX_INPUT_MAX_BATCH_BYTES)
        assertTrue("send latency metric should be recorded; metrics=$metrics", metrics.maxSendLatencyMs > 0.0)
        writeTmuxInputStressReport(metrics, expectedBytes = expected.size, chunks = chunks.size)

        val reconstructed = client.sentCommands
            .filter { it.startsWith("send-keys -l -t %0 -- '") }
            .joinToString(separator = "") { command ->
                command.substringAfter("-- '").removeSuffix("'")
            }
            .toByteArray(Charsets.US_ASCII)
        assertEquals(
            "high-rate stress must not lose or reorder input bytes",
            expected.toList(),
            reconstructed.toList(),
        )
    }

    @Test
    fun terminalDaQueryResponsesSuppressedInBridgeMode() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()

        val state = vm.panes.value.single().terminalState
        val queryBytes = "\u001b[c".toByteArray(Charsets.US_ASCII)
        state.appendRemoteOutput(queryBytes)
        drainTerminalBridgeHandler()

        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertTrue(
            "bridge-mode emulator must not generate DA query responses, got $sent",
            sent.none { it.contains("send-keys -H") || it.contains("send-keys -l") },
        )
    }

    @Test
    fun terminalOsc11QueryResponsesSuppressedInBridgeMode() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()

        val state = vm.panes.value.single().terminalState
        val queryBytes = "\u001b]11;?\u001b\\".toByteArray(Charsets.US_ASCII)
        state.appendRemoteOutput(queryBytes)
        drainTerminalBridgeHandler()

        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertTrue(
            "bridge-mode emulator must not generate OSC 11 color query responses, got $sent",
            sent.none { it.contains("send-keys -H") || it.contains("send-keys -l") },
        )
    }

    @Test
    fun terminalGeneratedInputResponseRoutesAsRawHex() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", "\u001b]11;rgb:0101/0404/0909\u001b\\".toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf("send-keys -H -t %0 1b 5d 31 31 3b 72 67 62 3a 30 31 30 31 2f 30 34 30 34 2f 30 39 30 39 1b 5c"),
            sent,
        )
    }

    @Test
    fun singleEscapeInputStillUsesNamedKeyPath() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", byteArrayOf(0x1B))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(listOf("send-keys -t %0 Escape"), sent)
    }

    @Test
    fun writeInputToPaneIgnoresEmptyBytes() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", ByteArray(0))
        advanceUntilIdle()

        assertTrue(
            "empty input must not produce a send-keys command",
            client.sentCommands.none { it.startsWith("send-keys") },
        )
    }

    @Test
    fun writeInputToPaneResultPropagatesFailedPaneWrite() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "send-keys"
            closeAndThrowException = TmuxClientException("failed to write tmux command `send-keys`")
        }
        vm.attachClientForTest(client)

        val result = vm.writeInputToPaneResult("%0", "hello".toByteArray(Charsets.UTF_8))

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("expected TmuxClientException, got ${error?.javaClass?.name}", error is TmuxClientException)
        assertTrue(error?.message?.contains("send-keys") == true)
        assertEquals(listOf("send-keys -l -t %0 -- 'hello'"), client.sentCommands.filter { it.startsWith("send-keys") })
        assertTrue("failed pane write must close the dead tmux client", client.closed)
    }

    // ------------------------------------------------------------- Issue #209
    // Bracketed-paste wrapping for multi-line input. Single-line input
    // must keep the existing `send-keys -l` + `send-keys ... Enter` shape
    // so we don't regress per-line named-key routing for normal typing.

    @Test
    fun writeInputToPaneWrapsMultiLineInputInBracketedPaste() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        val payload = "para one\npara two\npara three"
        vm.writeInputToPane("%4", payload.toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            "expected bracketed paste start, body, and end commands, got $sent",
            3,
            sent.size,
        )
        val cmd = sent[1]
        assertTrue(
            "expected send-keys -H targeting %4, got '$cmd'",
            cmd.startsWith("send-keys -H -t %4 "),
        )
        // The hex payload should begin with the bracketed-paste start
        // marker bytes (\e[200~ -> 1b 5b 32 30 30 7e) and end with the
        // matching end marker (\e[201~ -> 1b 5b 32 30 31 7e). The
        // newlines inside the body must reach tmux as `0a` bytes (i.e.
        // literal LF), NOT as separate `send-keys ... Enter` calls.
        assertTrue(
            "expected bracketed-paste start marker in hex payload, got '$cmd'",
            sent.first().endsWith("1b 5b 32 30 30 7e"),
        )
        assertTrue(
            "expected bracketed-paste end marker in hex payload, got '$cmd'",
            sent.last().endsWith("1b 5b 32 30 31 7e"),
        )
        // Three paragraphs separated by two `\n` bytes inside the
        // markers.
        val hexBody = cmd.substringAfter("send-keys -H -t %4 ")
        val newlineCount = hexBody.split(' ').count { it == "0a" }
        assertEquals(
            "expected exactly 2 literal LF bytes inside bracketed paste, got '$hexBody'",
            2,
            newlineCount,
        )
        // No standalone `send-keys ... Enter` should fire — the whole
        // block went through `-H`.
        assertTrue(
            "multi-line input must not emit a separate Enter named-key, got $sent",
            sent.none { it.contains(" Enter") },
        )
    }

    @Test
    fun writeInputToPaneNormalisesCrLfToLfInsideBracketedPaste() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Windows-style line endings should collapse to LF only — we
        // never want two paragraph separators where the source had one.
        vm.writeInputToPane("%0", "alpha\r\nbeta".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals("expected start, body, and end commands, got $sent", 3, sent.size)
        val cmd = sent[1]
        val hexBody = cmd.substringAfter("send-keys -H -t %0 ")
        val tokens = hexBody.split(' ')
        assertEquals(
            "expected exactly 1 LF (not CR LF) inside the paste, got '$hexBody'",
            1,
            tokens.count { it == "0a" },
        )
        assertEquals(
            "expected no CR bytes inside the paste, got '$hexBody'",
            0,
            tokens.count { it == "0d" },
        )
    }

    @Test
    fun writeInputToPaneKeepsSingleLineInputOnTheLiteralPath() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Single-line input must NOT be wrapped in bracketed-paste.
        // The existing send-keys -l shape is preserved so the regression
        // suite around named-key Enter routing (and the per-line Enter
        // semantics of `writeInputToPaneIssuesSendKeysWithLiteralBytes`)
        // keeps working.
        vm.writeInputToPane("%0", "hello".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(1, sent.size)
        assertEquals("send-keys -l -t %0 -- 'hello'", sent[0])
        assertTrue(
            "single-line input must not use the -H bracketed-paste path, got $sent",
            sent.none { it.startsWith("send-keys -H") },
        )
    }

    @Test
    fun writeInputToPaneTrailingNewlineGoesThroughBracketedPaste() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // "text\n" contains a `\n` so we route it through the paste path
        // even though there is only one line of content. This preserves
        // the design: any `\n` in the input means "treat as a paste".
        // Submission of the input is the caller's responsibility (they
        // can send a separate Enter named-key after the paste lands).
        vm.writeInputToPane("%0", "ls\n".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            "expected start, body, and end send-keys -H invocations for `\\n`-terminated input, got $sent",
            3,
            sent.size,
        )
        assertTrue(
            "expected send-keys -H, got '$sent'",
            sent.all { it.startsWith("send-keys -H -t %0 ") },
        )
    }

    @Test
    fun largeBracketedPasteIsSplitIntoBoundedSendKeysCommands() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        val payload = buildString {
            append("first line\n")
            repeat(TMUX_PASTE_BODY_CHUNK_BYTES * 3) { append(('a'.code + (it % 26)).toChar()) }
        }
        vm.writeInputToPane("%0", payload.toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys -H") }
        assertTrue("expected multiple bounded paste chunks, got ${sent.size}: $sent", sent.size > 3)
        assertTrue("paste start marker must be its own bounded command", sent.first().endsWith("1b 5b 32 30 30 7e"))
        assertTrue("paste end marker must be its own bounded command", sent.last().endsWith("1b 5b 32 30 31 7e"))
        val maxHexTokens = sent.drop(1).dropLast(1)
            .maxOf { command -> command.substringAfter("send-keys -H -t %0 ").split(' ').size }
        assertTrue(
            "body chunks must be bounded to $TMUX_PASTE_BODY_CHUNK_BYTES bytes; max tokens=$maxHexTokens",
            maxHexTokens <= TMUX_PASTE_BODY_CHUNK_BYTES,
        )
        assertTrue(
            "large paste must not fall back to one unbounded command",
            sent.none { it.substringAfter("send-keys -H -t %0 ").split(' ').size > TMUX_PASTE_BODY_CHUNK_BYTES },
        )
    }

    @Test
    fun buildBracketedPasteHexEmitsExpectedSequenceForKnownInput() {
        val vm = newVm()
        // Body: "a\nb" -> 0x61 0x0a 0x62.
        // Wrapped with the bracketed-paste markers:
        //   1b 5b 32 30 30 7e   <- ESC [ 2 0 0 ~
        //   61                  <- a
        //   0a                  <- LF
        //   62                  <- b
        //   1b 5b 32 30 31 7e   <- ESC [ 2 0 1 ~
        val hex = vm.buildBracketedPasteHexForTest("a\nb".toByteArray(Charsets.UTF_8))
        assertEquals(
            "1b 5b 32 30 30 7e 61 0a 62 1b 5b 32 30 31 7e",
            hex,
        )
    }

    @Test
    fun containsLineBreakIsTrueOnlyForLf() {
        val vm = newVm()
        assertTrue(vm.containsLineBreakForTest("a\nb".toByteArray(Charsets.UTF_8)))
        assertTrue(vm.containsLineBreakForTest("a\r\nb".toByteArray(Charsets.UTF_8)))
        assertFalse(vm.containsLineBreakForTest("a b".toByteArray(Charsets.UTF_8)))
        // A lone `\r` (rare on Android; carriage return without LF) is
        // intentionally NOT treated as a paragraph break — the input-
        // tokens path on the single-line route turns it into a tmux
        // `Enter` named key, which is the right thing for shell prompts.
        assertFalse(vm.containsLineBreakForTest("a\rb".toByteArray(Charsets.UTF_8)))
        assertFalse(vm.containsLineBreakForTest(ByteArray(0)))
    }

    @Test
    fun onKeyBarKeyTranslatesLabelsToTmuxNamedKeys() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.onKeyBarKey("%0", "Esc")
        vm.onKeyBarKey("%0", "Tab")
        vm.onKeyBarKey("%0", "‹")
        vm.onKeyBarKey("%0", "⌃")
        vm.onKeyBarKey("%0", "⌄")
        vm.onKeyBarKey("%0", "›")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(6, sent.size)
        assertTrue(sent[0].endsWith("Escape"))
        assertTrue(sent[1].endsWith("Tab"))
        assertTrue(sent[2].endsWith("Left"))
        assertTrue(sent[3].endsWith("Up"))
        assertTrue(sent[4].endsWith("Down"))
        assertTrue(sent[5].endsWith("Right"))
        // All addressed to the right pane.
        assertTrue(sent.all { it.contains("-t %0") })
    }

    @Test
    fun onKeyBarKeyEnterSendsTmuxEnterNamedKeyWithoutReflow() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Issue #527: the dedicated Enter/Return key submits a newline/CR to
        // the pane via the tmux named `Enter` key on the `send-keys` control
        // channel. Both the glyph label and the legacy "Enter" alias map to
        // the same sequence.
        vm.onKeyBarKey("%0", "⏎")
        vm.onKeyBarKey("%0", "Enter")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -t %0 Enter",
                "send-keys -t %0 Enter",
            ),
            sent,
        )
        // No resize/redraw path: the named-key route never uses
        // `refresh-client` and never the literal `send-keys -l`/`-H` byte
        // paths that would imply a paste/reflow.
        assertTrue(
            "Enter must not trigger a resize/refresh-client",
            client.sentCommands.none { it.startsWith("refresh-client") },
        )
    }

    @Test
    fun onKeyBarKeySendsCuratedCtrlCombosAsRawControlBytes() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Issue #458: the curated one-tap combos use the `^X` short labels
        // and each maps to its control byte via the `send-keys -H` overlay
        // path (no resize/redraw). The legacy `Ctrl-C` / `Ctrl-D` aliases
        // remain accepted for back-compat with the byte mapping.
        vm.onKeyBarKey("%0", "^C")
        vm.onKeyBarKey("%0", "^D")
        vm.onKeyBarKey("%0", "^Z")
        vm.onKeyBarKey("%0", "^O")
        vm.onKeyBarKey("%0", "^X")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -H -t %0 03",
                "send-keys -H -t %0 04",
                "send-keys -H -t %0 1a",
                "send-keys -H -t %0 0f",
                "send-keys -H -t %0 18",
            ),
            sent,
        )
    }

    @Test
    fun ctrlModifierChordsTheNextKeyAsAControlByte() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Issue #458: arm Ctrl (one-shot), then tap a letter in the bar.
        // The letter is sent as its Ctrl-chord control byte (`g` -> 0x07),
        // and the one-shot Ctrl auto-clears so the following plain tap is
        // NOT chorded.
        vm.onKeyBarModifierState("Ctrl", KeyModifierState.OneShot)
        assertEquals(KeyModifierState.OneShot, vm.ctrlModifierState.value)
        vm.onKeyBarKey("%0", "g")
        advanceUntilIdle()
        assertEquals(KeyModifierState.Off, vm.ctrlModifierState.value)

        vm.onKeyBarKey("%0", "Tab")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -H -t %0 07",
                "send-keys -t %0 Tab",
            ),
            sent,
        )
    }

    @Test
    fun lockedCtrlModifierChordsRepeatedlyUntilDisarmed() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Locked Ctrl survives a chord so several land in a row.
        vm.onKeyBarModifierState("Ctrl", KeyModifierState.Locked)
        vm.onKeyBarKey("%0", "a")
        advanceUntilIdle()
        assertEquals(KeyModifierState.Locked, vm.ctrlModifierState.value)
        vm.onKeyBarKey("%0", "B")
        advanceUntilIdle()
        assertEquals(KeyModifierState.Locked, vm.ctrlModifierState.value)
        // Disarm via an explicit Ctrl tap mirrored from the bar FSM.
        vm.onKeyBarModifierState("Ctrl", KeyModifierState.Off)
        // A bar key after disarm chords nothing — `^C` is its own raw byte.
        vm.onKeyBarKey("%0", "^C")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -H -t %0 01",
                // Uppercase B chords to the same byte as lowercase b.
                "send-keys -H -t %0 02",
                // Disarmed: the curated `^C` key sends its own raw byte.
                "send-keys -H -t %0 03",
            ),
            sent,
        )
    }

    @Test
    fun ctrlModifierChordsArrowsAndNamedKeysViaTmuxChordSyntax() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Issue #458: Ctrl + arrow / Tab / Esc route through tmux's own
        // `C-<key>` chord named keys so tmux emits the correct terminfo
        // encoding (e.g. Ctrl+Right word-jump). Each is one-shot so it
        // re-arms per chord here.
        vm.onKeyBarModifierState("Ctrl", KeyModifierState.OneShot)
        vm.onKeyBarKey("%0", "›")
        advanceUntilIdle()
        assertEquals(KeyModifierState.Off, vm.ctrlModifierState.value)

        vm.onKeyBarModifierState("Ctrl", KeyModifierState.OneShot)
        vm.onKeyBarKey("%0", "Esc")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -t %0 C-Right",
                "send-keys -t %0 C-Escape",
            ),
            sent,
        )
    }

    @Test
    fun keyBarControlEscapeAndHotkeysClearSmartTextBeforeTmuxRawSends() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()
        val state = vm.panes.value.single().terminalState
        val policies = mutableListOf<TerminalRawInputPolicy>()
        state.setSmartTextStagingBridgeForTest { policy ->
            policies += policy
            if (policy == TerminalRawInputPolicy.FlushSmartText) {
                client.sentCommands.add("flush-staged")
            }
        }

        vm.onKeyBarKey("%0", "^C")
        vm.onKeyBarKey("%0", "Esc")
        vm.onKeyBarModifierState("Ctrl", KeyModifierState.OneShot)
        vm.onKeyBarKey("%0", "›")
        advanceUntilIdle()

        assertEquals(
            listOf(
                TerminalRawInputPolicy.ClearSmartText,
                TerminalRawInputPolicy.ClearSmartText,
                TerminalRawInputPolicy.ClearSmartText,
            ),
            policies,
        )
        assertEquals(
            listOf(
                "send-keys -H -t %0 03",
                "send-keys -t %0 Escape",
                "send-keys -t %0 C-Right",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
        assertFalse(client.sentCommands.contains("flush-staged"))
    }

    @Test
    fun keyBarEnterFlushesSmartTextBeforeTmuxEnter() = runTest {
        val vm = newVm()
        val literalFlushGate = CompletableDeferred<Unit>()
        val client = FakeTmuxClient().apply {
            sendCommandGatePrefix = "send-keys -l -t %0 -- 'staged'"
            sendCommandGate = literalFlushGate
        }
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()
        val state = vm.panes.value.single().terminalState
        val policies = mutableListOf<TerminalRawInputPolicy>()
        state.setSmartTextStagingBridgeForTest { policy ->
            policies += policy
            if (policy == TerminalRawInputPolicy.FlushSmartText) {
                state.writeInput("staged".toByteArray(Charsets.UTF_8))
            }
        }

        try {
            vm.onKeyBarKey("%0", "⏎")
            waitForSentCommandCount(client, expectedCount = 1)

            assertEquals(listOf(TerminalRawInputPolicy.FlushSmartText), policies)
            assertEquals(
                "Enter must wait behind the queued SmartText flush while the literal send is suspended",
                listOf("send-keys -l -t %0 -- 'staged'"),
                client.sentCommands.filter { it.startsWith("send-keys") },
            )

            literalFlushGate.complete(Unit)
            waitForSentCommandCount(client, expectedCount = 2)

            assertEquals(
                listOf(
                    "send-keys -l -t %0 -- 'staged'",
                    "send-keys -t %0 Enter",
                ),
                client.sentCommands.filter { it.startsWith("send-keys") },
            )
        } finally {
            literalFlushGate.complete(Unit)
            state.setSmartTextStagingBridgeForTest(null)
        }
    }

    @Test
    fun directTmuxControlHotkeyClearsSmartTextBeforeSendingRawBytes() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()
        val state = vm.panes.value.single().terminalState
        val policies = mutableListOf<TerminalRawInputPolicy>()
        state.setSmartTextStagingBridgeForTest { policy ->
            policies += policy
            if (policy == TerminalRawInputPolicy.FlushSmartText) {
                client.sentCommands.add("flush-staged")
            }
        }

        vm.sendControlInputToPane("%0", CtrlCByte, repeatCount = 2)
        advanceUntilIdle()

        assertEquals(listOf(TerminalRawInputPolicy.ClearSmartText), policies)
        assertEquals(
            listOf("send-keys -H -t %0 03 03"),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
        assertFalse(client.sentCommands.contains("flush-staged"))
    }

    @Test
    fun sendControlInputToPaneCanSendDoublePressPayload() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.sendControlInputToPane("%0", CtrlCByte, repeatCount = 2)
        vm.sendControlInputToPane("%0", CtrlDByte, repeatCount = 2)
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -H -t %0 03 03",
                "send-keys -H -t %0 04 04",
            ),
            sent,
        )
    }

    private fun TestScope.waitForSentCommandCount(client: FakeTmuxClient, expectedCount: Int) {
        repeat(100) {
            advanceUntilIdle()
            if (client.sentCommands.count { command -> command.startsWith("send-keys") } >= expectedCount) {
                return
            }
            Thread.sleep(10)
        }
        advanceUntilIdle()
        assertTrue(
            "expected at least $expectedCount send-keys commands, got ${client.sentCommands}",
            client.sentCommands.count { it.startsWith("send-keys") } >= expectedCount,
        )
    }

    private fun TestScope.waitForTmuxInputBytes(
        vm: TmuxSessionViewModel,
        paneId: String,
        expectedBytes: Long,
        writer: Thread,
    ) {
        repeat(10_000) {
            advanceTimeBy(3L)
            runCurrent()
            val metrics = vm.tmuxInputMetricsForTest(paneId)
            if (metrics?.totalSentBytes == expectedBytes && !writer.isAlive) return
            Thread.sleep(1)
        }
        val metrics = vm.tmuxInputMetricsForTest(paneId)
        assertEquals(
            "timed out waiting for tmux input stress drain; metrics=$metrics writerAlive=${writer.isAlive}",
            expectedBytes,
            metrics?.totalSentBytes,
        )
    }

    private fun writeTmuxInputStressReport(
        metrics: TmuxInputStressMetrics,
        expectedBytes: Int,
        chunks: Int,
    ) {
        val outputDir = if (File("settings.gradle.kts").isFile) {
            File("app/build/reports/tmux-input-stress")
        } else {
            File("build/reports/tmux-input-stress")
        }
        val report = File(outputDir, "high-rate-input.json")
        report.parentFile?.mkdirs()
        report.writeText(
            """
            {
              "stress": "tmux-high-rate-input",
              "input_chunks": $chunks,
              "expected_bytes": $expectedBytes,
              "max_pending_capacity_bytes": $TMUX_INPUT_MAX_PENDING_BYTES,
              "max_batch_capacity_bytes": $TMUX_INPUT_MAX_BATCH_BYTES,
              "metrics": {
                "total_enqueued_bytes": ${metrics.totalEnqueuedBytes},
                "total_sent_bytes": ${metrics.totalSentBytes},
                "max_pending_bytes": ${metrics.maxPendingBytes},
                "max_pending_chunks": ${metrics.maxPendingChunks},
                "max_batch_bytes": ${metrics.maxBatchBytes},
                "max_batch_chunks": ${metrics.maxBatchChunks},
                "sent_batch_count": ${metrics.sentBatchCount},
                "max_send_latency_ms": ${metrics.maxSendLatencyMs}
              },
              "no_content_loss": ${metrics.totalEnqueuedBytes == expectedBytes.toLong() && metrics.totalSentBytes == expectedBytes.toLong()}
            }
            """.trimIndent(),
        )
    }

    private fun drainTerminalBridgeHandler() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun onKeyBarKeyIgnoresUnknownLabel() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.onKeyBarKey("%0", "ZorkKey")
        advanceUntilIdle()

        assertTrue(client.sentCommands.none { it.startsWith("send-keys") })
    }

    @Test
    fun lifecycleCommandsTargetActiveSessionAndWindow() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.createSession("next")
        vm.renameCurrentSession("renamed")
        vm.newWindow()
        vm.selectWindow("@2")
        vm.renameWindow("@2", "logs")
        vm.killWindow("@2")
        vm.killCurrentSession()
        advanceUntilIdle()

        assertTrue(client.sentCommands.contains("new-session -d -s 'next' -c '~'"))
        assertTrue(client.sentCommands.contains("rename-session -t 'work' 'renamed'"))
        assertTrue(client.sentCommands.contains("new-window -t 'work'"))
        assertTrue(client.sentCommands.contains("select-window -t @2"))
        assertTrue(client.sentCommands.contains("rename-window -t @2 'logs'"))
        assertTrue(client.sentCommands.contains("kill-window -t @2"))
        assertTrue(client.sentCommands.contains("kill-session -t 'work'"))
    }

    @Test
    fun lifecycleCommandsDeriveCreateNameButIgnoreBlankRenameNames() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.createSession(" ")
        vm.renameCurrentSession("")
        vm.renameWindow("@2", " ")
        advanceUntilIdle()

        val command = client.sentCommands.single()
        assertTrue(command.startsWith("new-session -d -s 'pocketshell-"))
        assertTrue(command.endsWith("' -c '~'"))
    }

    @Test
    fun escapeSingleQuotedRoundTripsBytesWithoutQuotes() {
        val vm = newVm()
        assertEquals("hello world", vm.escapeSingleQuoted("hello world"))
        assertEquals("\n\t", vm.escapeSingleQuoted("\n\t"))
        // Empty input → empty output.
        assertEquals("", vm.escapeSingleQuoted(""))
    }

    // ----- Issue #285: automatic tmux control-client sizing.

    @Test
    fun resizeRemotePtyReportsPhoneSizeToTmuxControlClient() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.resizeRemotePty(columns = 85, rows = 30)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "set-window-option -t 'work' window-size latest",
                "refresh-client -C 85x30",
            ),
            client.sentCommands,
        )
    }

    @Test
    fun resizeRemotePtyIsIdempotentForSameDimensions() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.resizeRemotePty(columns = 48, rows = 96)
        vm.resizeRemotePty(columns = 48, rows = 96)
        vm.resizeRemotePty(columns = 48, rows = 96)
        advanceUntilIdle()

        assertEquals(1, client.sentCommands.count { it.startsWith("set-window-option") })
        assertEquals(1, client.sentCommands.count { it.startsWith("refresh-client") })
    }

    @Test
    fun resizeRemotePtyRefreshesAgainWhenPhoneDimensionsChange() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.resizeRemotePty(columns = 48, rows = 95)
        advanceUntilIdle()
        vm.resizeRemotePty(columns = 50, rows = 95)
        advanceUntilIdle()
        vm.resizeRemotePty(columns = 50, rows = 94)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "set-window-option -t 'work' window-size latest",
                "refresh-client -C 48x95",
                "refresh-client -C 50x95",
                "refresh-client -C 50x94",
            ),
            client.sentCommands,
        )
    }

    @Test
    fun resizeRemotePtyIgnoresZeroAndNegativeDimensions() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.resizeRemotePty(columns = 0, rows = 0)
        vm.resizeRemotePty(columns = -1, rows = 96)
        vm.resizeRemotePty(columns = 48, rows = 0)
        advanceUntilIdle()

        assertTrue(client.sentCommands.none { it.startsWith("refresh-client") })
        assertTrue(client.sentCommands.none { it.startsWith("set-window-option") })
    }

    @Test
    fun resizeRemotePtyIsNoOpBeforeConnect() = runTest {
        val vm = newVm()

        vm.resizeRemotePty(columns = 48, rows = 96)
        advanceUntilIdle()

        assertEquals(48 to 96, vm.remoteDimensionsForTest())
    }

    @Test
    fun resizeRemotePtyEscapesSessionNameSingleQuotesForPolicyCommand() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            // tmux session names may contain `'` (rare but legal); the
            // resize command must close-escape-open so the shell does
            // not parse half the name as a positional arg.
            sessionName = "it's work",
            client = client,
        )

        vm.resizeRemotePty(columns = 60, rows = 24)
        advanceUntilIdle()

        assertEquals(
            "set-window-option -t 'it'\\''s work' window-size latest",
            client.sentCommands.single { it.startsWith("set-window-option") },
        )
    }

    @Test
    fun resizeRemotePtyFailureDoesNotBlockLaterSizeChangeRetry() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.refreshClientSizeException = IllegalStateException("boom")
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.resizeRemotePty(columns = 85, rows = 30)
        runCurrent()
        client.refreshClientSizeException = null
        vm.resizeRemotePty(columns = 86, rows = 30)
        advanceUntilIdle()

        assertEquals(
            "a failed refresh must not mark the size applied forever",
            1,
            client.sentCommands.count { it == "refresh-client -C 86x30" },
        )
    }

    @Test
    fun outputForReceivesEventsRoutedThroughEventsFlow() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Pre-seed list-panes so reconcile creates a pane row with the
        // bridge attached to client.outputFor("%0").
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\tt\t0"),
                isError = false,
            ),
        )
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(1, vm.panes.value.size)

        // The view model wires its bridge through client.outputFor(...)
        // which filters [ControlEvent.Output]. Verify the filter shape by
        // collecting outputFor() directly from a sibling test scope.
        val output = client.outputFor("%0")
        val firstEvent = async(start = CoroutineStart.UNDISPATCHED) {
            output.first()
        }
        client.emittedEvents.emit(
            ControlEvent.Output(paneId = "%1", data = "wrong-pane".toByteArray()),
        )
        client.emittedEvents.emit(
            ControlEvent.Output(paneId = "%0", data = "right-pane".toByteArray()),
        )
        advanceUntilIdle()

        val evt = firstEvent.await()
        assertEquals("%0", evt.paneId)
        assertEquals("right-pane", String(evt.data, Charsets.UTF_8))
    }

    @Test
    fun listPanesRowWithFewerFieldsIsSkipped() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "garbage\twithout\tenough\tfields",  // 4 fields — skipped
                    "%0\t@0\t\$0\tok\t0",                // 5 fields — kept
                    "",                                  // empty — skipped
                ),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(1, vm.panes.value.size)
        assertEquals("%0", vm.panes.value.single().paneId)
    }

    @Test
    fun listPanesRowWithWrongIdPrefixIsSkipped() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "0\t@0\t\$0\tno-prefix\t0",   // bad pane id — no leading %
                    "%0\twindow\t\$0\tno-at\t0",   // bad window id — no leading @
                    "%0\t@0\tsession\tno-dollar\t0", // bad session id — no leading $
                    "%1\t@0\t\$0\tgood\t1",
                ),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(listOf("%1"), vm.panes.value.map { it.paneId })
    }

    @Test
    fun connectionStatusFlipsToConnectedAfterAttachForTest() {
        val vm = newVm()
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Idle)
        vm.attachClientForTest(FakeTmuxClient())
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun replacingClientClosesOldClientAndUpdatesRegistry() {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry)
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "old",
            client = oldClient,
        )
        assertSame(oldClient, registry.clients.value[1L]?.client)

        vm.replaceClientForTest(
            hostId = 2L,
            hostName = "bravo",
            host = "bravo.example",
            port = 2222,
            user = "root",
            keyPath = "/keys/b",
            sessionName = "new",
            client = newClient,
        )

        assertTrue(oldClient.closed)
        assertNull(registry.clients.value[1L])
        val entry = registry.clients.value[2L]
        assertNotNull(entry)
        assertSame(newClient, entry?.client)
        assertEquals("bravo", entry?.hostName)
        assertEquals("bravo.example", entry?.hostname)
        assertEquals(2222, entry?.port)
        assertEquals("root", entry?.username)
        assertEquals("/keys/b", entry?.keyPath)
    }

    // ─── Issue #282: detected agents no longer seed a popup hint ────

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

    @Test
    fun detectedAgentConversationStartsWithoutHintPopupState() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "shell", paneIndex = 0)),
        )

        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val state = vm.agentConversations.value["%0"]
        assertNotNull(state)
        assertEquals(SessionTab.Terminal, state!!.selectedTab)
        assertEquals(AgentKind.ClaudeCode, state.detection?.agent)
    }

    @Test
    fun agentConversationStillReceivesEventsAfterPopupRemoval() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.Message(
                    id = "assistant-1",
                    agent = AgentKind.ClaudeCode,
                    atMillis = 1L,
                    role = ConversationRole.Assistant,
                    text = "still here",
                ),
            ),
        )

        val after = vm.agentConversations.value["%0"]!!
        assertEquals(
            "the new event should reach the conversation feed",
            "assistant-1",
            after.events.last().id,
        )
        assertEquals(AgentKind.ClaudeCode, after.detection?.agent)
    }

    // ─── Issue #256: current-pane conversation semantics ──────────────

    @Test
    fun selectingConversationTabOnlyMutatesTheCurrentPaneState() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.startAgentConversationForTest(
            "%1",
            AgentDetection(
                agent = AgentKind.Codex,
                sourcePath = "/home/u/.codex/sessions/xyz.jsonl",
                sessionId = "xyz",
                confidence = AgentDetection.Confidence.ProcessConfirmed,
            ),
        )

        vm.selectSessionTab("%0", SessionTab.Conversation)

        assertEquals(SessionTab.Conversation, vm.agentConversations.value["%0"]!!.selectedTab)
        assertEquals(SessionTab.Terminal, vm.agentConversations.value["%1"]!!.selectedTab)
    }

    @Test
    fun selectingTmuxConversationAndTerminalTabsRecordsExplicitDiagnostics() = runTest {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val vm = newVm()
            vm.attachClientForTest(FakeTmuxClient())
            val event = ConversationEvent.Message(
                id = "message-1",
                agent = AgentKind.ClaudeCode,
                role = ConversationRole.Assistant,
                text = "do not record this transcript text",
            )
            vm.startAgentConversationForTest("%0", newClaudeDetection(), listOf(event))

            vm.selectSessionTab("%0", SessionTab.Conversation)
            vm.selectSessionTab("%0", SessionTab.Terminal)

            val events = diagnostics.eventsNamed("conversation_terminal_tab_switch")
            assertEquals(2, events.size)
            assertEquals("tmux", events[0].fields["mode"])
            assertEquals("%0", events[0].fields["paneId"])
            assertEquals("Terminal", events[0].fields["fromTab"])
            assertEquals("Conversation", events[0].fields["toTab"])
            assertEquals("terminal_to_conversation", events[0].fields["direction"])
            assertEquals(true, events[0].fields["hasConversation"])
            assertEquals(1, events[0].fields["eventCount"])
            assertEquals("Conversation", events[1].fields["fromTab"])
            assertEquals("Terminal", events[1].fields["toTab"])
            assertFalse(events[0].fields.containsValue(event.text))
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun selectingConversationTabForUnknownOrPlainPaneIsNoOp() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        val before = vm.agentConversations.value

        vm.selectSessionTab("%missing", SessionTab.Conversation)

        assertEquals(before, vm.agentConversations.value)
    }

    // ─── Issue #495: remember agent sessions across reconnect ─────────

    /**
     * Connects the VM to a stable host/session and applies a single pane in
     * window @0, so reconnect simulations can re-apply panes under the same
     * window with a rotated pane id.
     */
    private fun TmuxSessionViewModel.connectWithPaneForTest(
        paneId: String,
        windowId: String = "@0",
        sessionName: String = "work",
    ) {
        replaceClientForTest(
            hostId = 42L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
            client = FakeTmuxClient(),
        )
        applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId,
                    windowId,
                    "$0",
                    "shell",
                    paneIndex = 0,
                    sessionName = sessionName,
                ),
            ),
        )
    }

    @Test
    fun agentSessionRememberedAndRestoredImmediatelyAfterReconnect() = runTest {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        // The user opens Conversation — this remembers the window's status.
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        // Reconnect: tmux re-attach assigns a NEW pane id under the SAME
        // window. The old pane row is gone; nothing is seeded by the live
        // detection round-trip yet.
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        // Conversation tab is available immediately on the new pane id,
        // without waiting for live re-detection.
        assertNull("old pane id is gone after reattach", vm.agentConversations.value["%0"])
        val restored = vm.agentConversations.value["%7"]
        assertNotNull("agent status restored immediately on the new pane", restored)
        assertEquals(AgentKind.ClaudeCode, restored!!.detection?.agent)
        assertEquals(
            "the Conversation tab is gated on agentForWindow being non-null",
            AgentKind.ClaudeCode,
            vm.agentForWindow("@0"),
        )
    }

    @Test
    fun reconnectReselectsConversationWhenUserWasOnConversation() = runTest {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        assertEquals(
            "user who was in Conversation stays on Conversation after reconnect",
            SessionTab.Conversation,
            vm.agentConversations.value["%7"]!!.selectedTab,
        )
    }

    @Test
    fun reconnectKeepsTerminalWhenUserWasOnTerminal() = runTest {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        // User saw the agent but stayed on Terminal — remember Terminal.
        vm.selectSessionTab("%0", SessionTab.Terminal)
        runCurrent()

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        val restored = vm.agentConversations.value["%7"]!!
        assertEquals(AgentKind.ClaudeCode, restored.detection?.agent)
        assertEquals(
            "Conversation tab available but Terminal stays selected",
            SessionTab.Terminal,
            restored.selectedTab,
        )
    }

    @Test
    fun reconnectDoesNotResurrectAgentThatExited() = runTest {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        // Live detection reports the window no longer hosts an agent (the
        // user exited Claude). This reconciles the remembered status.
        vm.clearAgentDetectionForPaneForTest("%0")
        runCurrent()

        // Reconnect: the same window must NOT light up a phantom
        // Conversation tab.
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        assertNull(
            "an exited agent must not be restored on reconnect",
            vm.agentConversations.value["%7"],
        )
        assertNull("no Conversation tab for the exited agent's window", vm.agentForWindow("@0"))
    }

    // ─── Issue #554: transient null detection must not forget the agent ──

    /**
     * Issue #554: on reconnect, live detection routinely reads "no agent" for
     * a beat before the agent's JSONL log / process is observable on the fresh
     * connection. A remembered (seeded) agent window must NOT be downgraded to
     * a plain shell on that FIRST transient null — the seeded Conversation UI
     * stays up and detection re-confirms. Downgrading there was the
     * "we forget it's an agent and bounce to plain-shell-then-back" regression.
     */
    @Test
    fun transientNullDetectionDoesNotForgetRememberedAgentOnReconnect() = runTest {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        // Reconnect: a rotated pane id under the same window, seeded from
        // memory so the agent UI shows immediately.
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()
        assertEquals(
            "precondition: agent UI restored immediately on reattach",
            AgentKind.ClaudeCode,
            vm.agentConversations.value["%7"]?.detection?.agent,
        )

        // First live-detection null right after the reattach: DEFER, do not
        // forget. The seeded agent UI must survive.
        val downgraded = vm.handleNullAgentDetectionForTest("%7")
        runCurrent()
        assertFalse("first transient null must be deferred, not a downgrade", downgraded)
        assertEquals(
            "the seeded agent UI must survive a single transient null",
            AgentKind.ClaudeCode,
            vm.agentConversations.value["%7"]?.detection?.agent,
        )
        assertEquals(
            "the Conversation tab stays available for the window",
            AgentKind.ClaudeCode,
            vm.agentForWindow("@0"),
        )
    }

    /**
     * Issue #554: the deferral is a confirmation window, not a permanent
     * pin. A genuinely-exited agent (null detection persisting past
     * [AGENT_EXIT_CONFIRMATIONS]) still reconciles away so a stale
     * Conversation tab does not linger.
     */
    @Test
    fun persistentNullDetectionEventuallyDowngradesAnExitedAgent() = runTest {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        // Drive null detections up to the confirmation threshold. The last one
        // must downgrade the window to a plain shell.
        var downgraded = false
        repeat(AGENT_EXIT_CONFIRMATIONS) {
            downgraded = vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
        }

        assertTrue("a persistently-null agent must eventually downgrade", downgraded)
        assertNull(
            "an agent that genuinely exited must reconcile away after confirmation",
            vm.agentConversations.value["%7"],
        )
        assertNull("no Conversation tab once the agent is confirmed gone", vm.agentForWindow("@0"))
    }

    /**
     * Issue #554: a null detection for a window that was NEVER an agent (no
     * remembered status, no seeded UI) downgrades immediately — the
     * confirmation window is only for protecting a remembered agent seed, not
     * for delaying the normal plain-shell verdict.
     */
    @Test
    fun nullDetectionDowngradesImmediatelyWhenWindowWasNeverAnAgent() = runTest {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        val downgraded = vm.handleNullAgentDetectionForTest("%0")
        runCurrent()

        assertTrue("a never-agent window has no seed to protect — downgrade now", downgraded)
        assertNull(vm.agentConversations.value["%0"])
    }

    @Test
    fun liveDetectionRefiningSameAgentKeepsRestoredConversationTab() = runTest {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()
        // Seed restored Conversation.
        assertEquals(SessionTab.Conversation, vm.agentConversations.value["%7"]!!.selectedTab)

        // Live re-detection lands for the SAME agent + same log but with a
        // drifted confidence. The user must NOT be bounced to Terminal.
        vm.markAgentTailLiveForTest(
            "%7",
            newClaudeDetection().copy(confidence = AgentDetection.Confidence.RecentFile),
        )
        runCurrent()

        val state = vm.agentConversations.value["%7"]!!
        assertEquals(
            "same-agent refinement preserves the user's Conversation tab",
            SessionTab.Conversation,
            state.selectedTab,
        )
        assertEquals(
            AgentDetection.Confidence.RecentFile,
            state.detection?.confidence,
        )
    }

    @Test
    fun sendToAgentPaneAppendsOptimisticMessageAndWritesCarriageReturn() = runTest {
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
    fun sendToAgentPaneExitsTmuxCopyModeBeforeTypingPrompt() = runTest {
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

        val result = vm.sendToAgentPaneResult("%0", "  run tests  ")
        runCurrent()

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

    @Test
    fun codexAgentSubmitDelaysFinalEnterAfterTextInsertion() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newCodexDetection())

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
            "Codex submit must not press Enter before the submit delay elapses",
            listOf("send-keys -l -t %0 -- 'run tests'"),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )

        advanceTimeBy(1L)
        assertTrue(send.await().isSuccess)
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'run tests'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    @Test
    fun codexSendInFlightSurvivesTerminalOverflowWithoutReconnectOrDuplicateSend() = runTest {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
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
        assertTrue("overflowed pane is shown as a surface error", vm.panes.value.single().surfaceError)
    }

    @Test
    fun agentSubmitDelaysFinalEnterByConfiguredDelayForClaudeCode() = runTest {
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
    fun agentSubmitWithZeroConfiguredDelaySendsEnterBackToBack() = runTest {
        // Issue #526: a 0ms delay restores the pre-#526 back-to-back behaviour
        // for users whose agent never races — no spurious suspension between
        // the text and the submit Enter.
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.setAgentSubmitEnterDelayForTest(0)

        val result = vm.sendToAgentPaneResult("%0", "ship it")
        runCurrent()

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'ship it'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    @Test
    fun rawPaneInputDoesNotUseCodexAgentSubmitDelay() = runTest {
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
    fun sendToAgentPaneLongSingleLineUsesBoundedBracketedChunksThenEnter() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val draft = "x".repeat(TMUX_PASTE_BODY_CHUNK_BYTES * 3 + 17)
        val result = vm.sendToAgentPaneResult("%0", draft)
        runCurrent()

        assertTrue("expected long single-line send to succeed", result.isSuccess)
        val sendKeys = client.sentCommands.filter { it.startsWith("send-keys") }
        assertTrue(
            "long single-line draft must not create one unbounded literal command: $sendKeys",
            sendKeys.none { it.startsWith("send-keys -l") },
        )
        assertTrue(
            "expected bracketed-paste chunks for long single-line draft, got $sendKeys",
            sendKeys.count { it.startsWith("send-keys -H -t %0 ") } > 3,
        )
        assertEquals("send-keys -t %0 Enter", sendKeys.last())
        val maxExpectedCommandLength =
            "send-keys -H -t %0 ".length + (TMUX_PASTE_BODY_CHUNK_BYTES * 3 - 1)
        val longest = sendKeys.maxOf { it.length }
        assertTrue(
            "tmux commands must stay bounded; longest=$longest max=$maxExpectedCommandLength commands=$sendKeys",
            longest <= maxExpectedCommandLength,
        )
    }

    @Test
    fun sendToAgentPaneLongDictationWithAttachmentBlockSubmitsFinalEnter() = runTest {
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
        val result = vm.sendToAgentPaneResult("%0", payload)
        runCurrent()

        assertTrue("expected long dictation plus attachment send to succeed", result.isSuccess)
        val sendKeys = client.sentCommands.filter { it.startsWith("send-keys") }
        assertTrue(
            "combined dictation/attachment prompt must use bounded hex paste chunks, got $sendKeys",
            sendKeys.count { it.startsWith("send-keys -H -t %0 ") } > 3,
        )
        assertTrue(
            "combined prompt must not use one unbounded literal send-keys command: $sendKeys",
            sendKeys.none { it.startsWith("send-keys -l") },
        )
        assertEquals(
            "combined prompt must be submitted after paste chunks",
            "send-keys -t %0 Enter",
            sendKeys.last(),
        )
        val maxExpectedCommandLength =
            "send-keys -H -t %0 ".length + (TMUX_PASTE_BODY_CHUNK_BYTES * 3 - 1)
        val longest = sendKeys.maxOf { it.length }
        assertTrue(
            "tmux commands must stay bounded; longest=$longest max=$maxExpectedCommandLength commands=$sendKeys",
            longest <= maxExpectedCommandLength,
        )
    }

    @Test
    fun sendToAgentPaneResultFailureDuringLargePasteKeepsConversationAndReconnectAvailable() = runTest {
        val vm = newVm()
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val client = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "send-keys -H"
            closeAndThrowException = TmuxClientException("failed to write tmux command `send-keys`")
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
        // Issue #494: a failed send no longer drops the user's text. The
        // optimistic turn is shown and flipped to Failed (with retry).
        val failed = vm.agentConversations.value["%0"]!!.events.single() as ConversationEvent.Message
        assertEquals(MessageSendState.Failed, failed.sendState)
        assertTrue(failed.id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX))
    }

    @Test
    fun sendToAgentPaneResultPasteChunkTmuxErrorMarksOptimisticMessageFailed() = runTest {
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
        // Issue #494: the optimistic turn is shown and flipped to Failed.
        val failed = vm.agentConversations.value["%0"]!!.events.single() as ConversationEvent.Message
        assertEquals(MessageSendState.Failed, failed.sendState)
        assertTrue(
            "failed paste must stop before submitting Enter: ${client.sentCommands}",
            client.sentCommands.none { it == "send-keys -t %0 Enter" },
        )
    }

    @Test
    fun sendToAgentPaneResultFinalEnterTmuxErrorMarksOptimisticMessageFailed() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            repeat(5) {
                responses.addLast(
                    CommandResponse(number = it.toLong(), output = emptyList(), isError = false),
                )
            }
            responses.addLast(
                CommandResponse(
                    number = 6L,
                    output = listOf("can't find pane: %0"),
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

        assertTrue("expected tmux %error from final Enter to fail the send result", result.isFailure)
        // Issue #494: the optimistic turn is shown and flipped to Failed.
        val failed = vm.agentConversations.value["%0"]!!.events.single() as ConversationEvent.Message
        assertEquals(MessageSendState.Failed, failed.sendState)
        assertEquals(
            "send-keys -t %0 Enter",
            client.sentCommands.filter { it.startsWith("send-keys") }.last(),
        )
    }

    @Test
    fun sendToAgentPaneBlankTextIsNoOp() = runTest {
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
    fun sendToAgentPaneResultMarksOptimisticMessageFailedWhenDisconnected() = runTest {
        // Issue #494: a disconnected send no longer silently drops the
        // user's text. The optimistic turn is shown and flipped to Failed
        // (with a retry affordance), while the result still reports failure
        // so the unified composer can keep the draft editable. With no
        // remembered target there is nothing send-time reconnect can dial.
        val vm = newVm()
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val result = vm.sendToAgentPaneResult("%0", "preserve this prompt")
        runCurrent()

        assertTrue("disconnected tmux agent send must report failure", result.isFailure)
        val failed = vm.agentConversations.value["%0"]!!.events.single() as ConversationEvent.Message
        assertEquals("preserve this prompt", failed.text)
        assertEquals(ConversationRole.User, failed.role)
        assertEquals(MessageSendState.Failed, failed.sendState)
        assertTrue(failed.id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX))
    }

    @Test
    fun sendToAgentPaneResultReconnectsAndSendsWhenDisconnectedRecoverable() = runTest {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%0")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
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
        advanceUntilIdle()
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
    }

    @Test
    fun retryFailedAgentSendDropsFailedTurnAndReSendsWithoutDoubleSend() = runTest {
        // Issue #494: retrying a failed tmux send drops the failed
        // placeholder and re-sends. With a live client the re-send inserts
        // a fresh pending turn and submits the keys — exactly one user turn
        // remains (no double-send, no orphaned failed row).
        val vm = newVm()
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        // First attempt: disconnected -> Failed turn.
        assertTrue(vm.sendToAgentPaneResult("%0", "retry me").isFailure)
        runCurrent()
        val failed = vm.agentConversations.value["%0"]!!.events.single() as ConversationEvent.Message
        assertEquals(MessageSendState.Failed, failed.sendState)

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

    // ─── Issue #154: conversation search query persistence ─────────────

    @Test
    fun setAgentSearchQueryUpdatesPaneState() = runTest {
        // Acceptance criterion #5: the search query is hoisted into the
        // pane's `AgentConversationUiState` so a Terminal ↔ Conversation
        // tab round-trip cannot clear it. The screen wires the search
        // field's `onValueChange` into `setAgentSearchQuery`; this test
        // pins that contract at the view-model level.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        assertEquals(
            "fresh pane state must start with an empty search query",
            "",
            vm.agentConversations.value["%0"]!!.searchQuery,
        )

        vm.setAgentSearchQuery("%0", "kubectl")

        assertEquals(
            "the query must reach the pane's state for tab-flip survival",
            "kubectl",
            vm.agentConversations.value["%0"]!!.searchQuery,
        )

        // A tab flip is just a copy() that preserves the searchQuery
        // field; we can also exercise the explicit flow by selecting
        // Terminal and asserting nothing changed.
        vm.selectSessionTab("%0", SessionTab.Terminal)
        assertEquals(
            "query must survive a Conversation ↔ Terminal flip",
            "kubectl",
            vm.agentConversations.value["%0"]!!.searchQuery,
        )
    }

    @Test
    fun setAgentSearchQueryIsNoOpForUnknownPane() = runTest {
        // Calling the setter against a pane the VM has never seen must
        // not crash and must not populate a phantom row in the map.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        vm.setAgentSearchQuery("%nope", "anything")

        assertTrue(
            "unknown pane must not be silently created",
            vm.agentConversations.value.isEmpty(),
        )
    }

    @Test
    fun stoppedAgentLogTailMarksConversationStaleWhileTmuxStaysConnected() = runTest {
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

        assertSame(tailJob, started)
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
    fun stoppedAgentLogTailPreservesConcurrentConversationState() = runTest {
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
        vm.setAgentSearchQuery("%0", "deploy")
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
        assertEquals("deploy", after.searchQuery)
        assertEquals("assistant-late", after.events.single().id)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun staleOldAgentLogTailCompletionDoesNotMarkRestartedPaneStale() = runTest {
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
    fun failedAgentLogTailMarksConversationLogUnavailableWhileTmuxStaysConnected() = runTest {
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
    fun retryAgentLogTailForPaneRestartsOneTailAndKeepsTmuxConnected() = runTest {
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
    fun codexAgentLogRetryWithTerminalFloodKeepsConversationAndTerminalConsistent() = runTest {
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
            val completed = withTimeoutOrNull(5_000) {
                while (sender.isActive) {
                    shadowOf(Looper.getMainLooper()).idle()
                    runCurrent()
                    delay(10)
                }
                sender.await()
                true
            } ?: false

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
    fun retryAgentLogTailForPaneDetectionFailureReturnsLogUnavailable() = runTest {
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

    // ─── Issue #186: per-window agent detection state ──────────────────
    //
    // Detection is per-pane (and therefore per-window for the simple
    // case of one pane per window). The view-model surface that the
    // screen drives off is [agentForWindow] — it must return the
    // current window's agent kind regardless of which window's pane the
    // user is currently viewing, so the Conversation tab can hide on
    // plain-shell windows even when a sibling window has a live agent.

    @Test
    fun agentForWindowReturnsNullForUnknownWindowId() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        assertNull(vm.agentForWindow(null))
        assertNull(vm.agentForWindow(""))
        assertNull(vm.agentForWindow("@nonexistent"))
    }

    @Test
    fun agentForWindowReturnsKindOfDetectedAgentInThatWindow() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "agent",
                    paneIndex = 0,
                ),
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%1",
                    windowId = "@1",
                    sessionId = "$0",
                    title = "shell",
                    paneIndex = 0,
                ),
            ),
        )
        // Only window @0's pane has a detection — window @1 is a plain
        // shell with no agent.
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        assertEquals(
            "the agent-running window must report its agent kind",
            AgentKind.ClaudeCode,
            vm.agentForWindow("@0"),
        )
        assertNull(
            "a plain-shell window must NOT inherit a sibling window's agent kind " +
                "even when they share a cwd",
            vm.agentForWindow("@1"),
        )
    }

    @Test
    fun agentForWindowPicksLowestPaneIndexWhenMultipleAgentsInOneWindow() = runTest {
        // Edge case: two panes in the same window with detections.
        // Stable behaviour: the pane that appears first in the panes
        // list wins (which is paneIndex ascending per the reconcile
        // sort).
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "a",
                    paneIndex = 0,
                ),
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%1",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "b",
                    paneIndex = 1,
                ),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.startAgentConversationForTest(
            "%1",
            AgentDetection(
                agent = AgentKind.Codex,
                sourcePath = "/home/u/.codex/sessions/x.jsonl",
                sessionId = "x",
                confidence = AgentDetection.Confidence.ProcessConfirmed,
            ),
        )

        assertEquals(AgentKind.ClaudeCode, vm.agentForWindow("@0"))
    }

    @Test
    fun listPanesParserAcceptsPrintableFieldSeparator() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    listOf(
                        "%0",
                        "@0",
                        "\$0",
                        "work",
                        "shell",
                        "0",
                        "/workspace",
                        "bash",
                        "/dev/pts/3",
                    ).joinToString(LIST_PANES_FIELD_SEPARATOR),
                ),
                isError = false,
            ),
        )
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "\$0", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        val pane = vm.panes.value.single()
        assertEquals("%0", pane.paneId)
        assertEquals("/workspace", pane.cwd)
        assertEquals("bash", pane.currentCommand)
        assertEquals("/dev/pts/3", pane.paneTty)
    }

    @Test
    fun listPanesFormatRequestsPaneTty() = runTest {
        // Issue #186: the list-panes format must include
        // `#{pane_tty}` so per-pane detection can scope its process
        // scan to a TTY. Without this, the per-window fix has no
        // signal to act on.
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0\t/workspace\tbash\t/dev/pts/3"),
                isError = false,
            ),
        )
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "\$0", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        val listPanesCmd = client.sentCommands.single { it.startsWith("list-panes") }
        assertTrue(
            "list-panes format must include #{pane_tty}; got `$listPanesCmd`",
            listPanesCmd.contains("#{pane_tty}"),
        )
        assertTrue(
            "list-panes format must include #{pane_in_mode}; got `$listPanesCmd`",
            listPanesCmd.contains("#{pane_in_mode}"),
        )

        val pane = vm.panes.value.single()
        assertEquals("/dev/pts/3", pane.paneTty)
        assertFalse(pane.inCopyMode)
    }

    @Test
    fun listPanesParserCapturesTmuxWindowIndex() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@7\t2\t\$0\twork\tshell\t0\t/workspace\tbash\t/dev/pts/3\t0"),
                isError = false,
            ),
        )
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "\$0", windowId = "@7", name = ""),
        )
        advanceUntilIdle()

        val pane = vm.panes.value.single()
        assertEquals("@7", pane.windowId)
        assertEquals(2, pane.windowIndex)
    }

    @Test
    fun paneModeChangedRefreshesCopyModeStateFromListPanes() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0\t/workspace\tbash\t/dev/pts/3\t1"),
                isError = false,
            ),
        )
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        client.emittedEvents.emit(ControlEvent.PaneModeChanged("%0"))
        advanceUntilIdle()

        val pane = vm.panes.value.single()
        assertEquals("%0", pane.paneId)
        assertTrue("pane_in_mode=1 must mark the pane as copy-mode/action mode", pane.inCopyMode)
    }

    @Test
    fun agentForWindowClearsAfterPaneLosesDetection() = runTest {
        // The screen drives Conversation-tab visibility off
        // [agentForWindow]. When a pane that previously had a
        // detection no longer reports one (the user exited Claude),
        // `agentForWindow` for that window must return null so the tab
        // disappears.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        assertEquals(AgentKind.ClaudeCode, vm.agentForWindow("@0"))

        // Simulate the production clear path: drop the conversation
        // entry directly (mirrors what `clearAgentDetectionForPane`
        // does when [detectForPane] returns null on a subsequent
        // probe).
        vm.clearAgentDetectionForPaneForTest("%0")

        assertNull(
            "agentForWindow must return null once the pane's detection is cleared",
            vm.agentForWindow("@0"),
        )
    }

    @Test
    fun conversationStateClearsWhenPaneLosesDetection() = runTest {
        // If the user has opened the Conversation tab on the agent pane
        // and then exits the agent in that pane, the per-pane
        // conversation state must disappear so the screen falls back to
        // Terminal for that visible pane.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "agent", paneIndex = 0),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        assertEquals(SessionTab.Conversation, vm.agentConversations.value["%0"]!!.selectedTab)

        vm.clearAgentDetectionForPaneForTest("%0")

        assertNull(
            "conversation state must clear when the pane loses its detection",
            vm.agentConversations.value["%0"],
        )
        assertNull(
            "agentForWindow must report null once the pane lost its detection",
            vm.agentForWindow("@0"),
        )
    }

    @Test
    fun parsedPanePaneTtyDefaultsToEmptyWhenOmitted() = runTest {
        // Defensive: an older tmux that doesn't emit the new field, or
        // a unit test passing the legacy shape, must still produce a
        // valid TmuxPaneState (with an empty paneTty so per-pane
        // detection short-circuits to null).
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0)),
        )
        advanceUntilIdle()

        assertEquals("", vm.panes.value.single().paneTty)
    }

    // ----- Issue #188: killWindow error surfacing + deterministic refresh.

    @Test
    fun killWindowStartsAtNullWindowKillErrorAndStaysNullOnHappyPath() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Seed an initial pane so the WindowStrip / panes list is non-empty
        // before the kill. The kill command targets @0.
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\ta\t0"),
                isError = false,
            ),
        )
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(1, vm.panes.value.size)
        assertNull(
            "windowKillError must default to null before any kill is attempted",
            vm.windowKillError.value,
        )

        // Reconcile after the kill returns no rows — the window is gone.
        client.responses.addLast(
            CommandResponse(number = 2L, output = emptyList(), isError = false),
        )

        // Issue the kill, then deliver tmux's deterministic
        // %window-close event the view model is waiting on.
        vm.killWindow("@0")
        // Yield so the UNDISPATCHED subscriber installs and the
        // sendCommand call records on `sentCommands`.
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.WindowClose(sessionId = "", windowId = "@0"),
        )
        advanceUntilIdle()

        // The command actually went out.
        assertTrue(
            "expected kill-window dispatch, got ${client.sentCommands}",
            client.sentCommands.contains("kill-window -t @0"),
        )
        // The post-kill reconcile dropped the pane.
        assertTrue(
            "panes must be empty once the killed window's reconcile lands",
            vm.panes.value.isEmpty(),
        )
        // Happy path stays silent.
        assertNull(
            "windowKillError must remain null after a successful kill",
            vm.windowKillError.value,
        )
    }

    @Test
    fun killWindowSurfacesTransportFailureAndSkipsRefresh() = runTest {
        // sendCommand throws → user must see a banner-targetable error
        // string AND no reconcile must fire (a reconcile after a failed
        // kill would re-render the still-alive window and look like the
        // very bug #188 fixes).
        val vm = newVm()
        val client = ThrowingTmuxClient(
            throwOnCommand = "kill-window",
            exception = IllegalStateException("transport gone"),
        )
        vm.attachClientForTest(client)
        // Seed a pane named "@5" so the label says "Window 1".
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@5", "$0", "work", paneIndex = 0),
            ),
        )
        advanceUntilIdle()
        val sentBefore = client.sentCommands.toList()

        vm.killWindow("@5")
        advanceUntilIdle()

        // sendCommand fired and threw; no follow-up list-panes was issued
        // (which the kill-window code skips on transport failure).
        val newCommands = client.sentCommands.drop(sentBefore.size)
        assertTrue(
            "expected a kill-window dispatch, got $newCommands",
            newCommands.any { it.startsWith("kill-window") },
        )
        assertTrue(
            "must NOT refresh after a failed kill — found ${newCommands}",
            newCommands.none { it.startsWith("list-panes") },
        )
        val msg = vm.windowKillError.value
        assertNotNull("expected windowKillError to surface transport failure", msg)
        assertTrue(
            "expected error message to name the window, got '$msg'",
            msg!!.contains("Window 1"),
        )
        assertTrue(
            "expected error message to carry the exception detail, got '$msg'",
            msg.contains("transport gone"),
        )
    }

    @Test
    fun killWindowSurfacesTmuxErrorResponseAndSkipsRefresh() = runTest {
        // tmux responds with %error — same skip-refresh rule.
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            // First response is the seeding list-panes triggered by
            // WindowAdd below — happy.
            responses.addLast(
                CommandResponse(
                    number = 1L,
                    output = listOf("%0\t@5\t\$0\twork\ta\t0"),
                    isError = false,
                ),
            )
            // Second response: the kill-window command itself, returning
            // an isError=true block with a tmux-style detail line.
            responses.addLast(
                CommandResponse(
                    number = 2L,
                    output = listOf("can't find window @5"),
                    isError = true,
                ),
            )
        }
        vm.attachClientForTest(client)
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@5", name = ""),
        )
        advanceUntilIdle()
        val sentBefore = client.sentCommands.toList()

        vm.killWindow("@5")
        advanceUntilIdle()

        val newCommands = client.sentCommands.drop(sentBefore.size)
        assertTrue(
            "expected kill-window dispatch, got $newCommands",
            newCommands.any { it.startsWith("kill-window") },
        )
        assertTrue(
            "no refresh must run after a tmux %error response — found $newCommands",
            newCommands.none { it.startsWith("list-panes") },
        )
        val msg = vm.windowKillError.value
        assertNotNull("expected windowKillError to surface tmux %error", msg)
        assertTrue(
            "expected tmux error message to carry the detail, got '$msg'",
            msg!!.contains("can't find window"),
        )
    }

    @Test
    fun killWindowRefreshesAfterDeterministicWindowCloseEvent() = runTest {
        // Happy path: sendCommand succeeds → view model waits for
        // %window-close → fires an explicit reconcile. Asserting the
        // ordering: until the event arrives no reconcile fires; once the
        // event arrives the reconcile lands.
        val vm = newVm()
        val client = FakeTmuxClient()
        // Seeding list-panes for the WindowAdd below.
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@9\t\$0\twork\ta\t0"),
                isError = false,
            ),
        )
        // kill-window's own response: empty success.
        client.responses.addLast(
            CommandResponse(number = 2L, output = emptyList(), isError = false),
        )
        // Post-kill reconcile list-panes returns no rows.
        client.responses.addLast(
            CommandResponse(number = 3L, output = emptyList(), isError = false),
        )
        vm.attachClientForTest(client)
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@9", name = ""),
        )
        advanceUntilIdle()
        val listPanesCountBefore = client.sentCommands.count { it.startsWith("list-panes") }

        vm.killWindow("@9")
        // runCurrent (NOT advanceUntilIdle) so the test scheduler does NOT
        // auto-roll virtual time past the 2s fallback — we want to assert
        // ordering: WITHOUT the %window-close event the kill-window
        // refresh is gated on the event-deferred join. runCurrent pumps
        // already-scheduled tasks at the current virtual time, so the
        // sendCommand fires and the post-kill collector installs, but the
        // 2s timeout has NOT yet popped.
        runCurrent()

        // sendCommand fired.
        assertTrue(
            "expected kill-window dispatch on the wire",
            client.sentCommands.any { it == "kill-window -t @9" },
        )
        // Before the %window-close arrives the kill-window-side reconcile
        // is gated on the event-deferred join. The production
        // [onControlEvent] subscriber sees only WindowAdd / WindowClose /
        // LayoutChange so a successful kill response on its own won't
        // trigger a refresh.
        assertEquals(
            "kill-window must NOT refresh until %window-close arrives or " +
                "the 2s fallback fires",
            listPanesCountBefore,
            client.sentCommands.count { it.startsWith("list-panes") },
        )

        client.emittedEvents.emit(
            ControlEvent.WindowClose(sessionId = "", windowId = "@9"),
        )
        advanceUntilIdle()

        // Two reconciles can fire: one from the event-loop's
        // onControlEvent and one from kill-window's explicit refresh.
        // Either way it MUST be at least one more than before.
        assertTrue(
            "expected at least one post-kill list-panes refresh",
            client.sentCommands.count { it.startsWith("list-panes") } > listPanesCountBefore,
        )
        assertNull(
            "happy path must not raise a windowKillError",
            vm.windowKillError.value,
        )
    }

    @Test
    fun killWindowRefreshesAfterFallbackTimeoutWhenEventNeverArrives() = runTest {
        // A degenerate tmux that swallows the %window-close notification
        // must not wedge the UI indefinitely — the view model falls back
        // to an unconditional reconcile after KILL_WINDOW_EVENT_WAIT_MS.
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@7\t\$0\twork\ta\t0"),
                isError = false,
            ),
        )
        client.responses.addLast(
            CommandResponse(number = 2L, output = emptyList(), isError = false),
        )
        client.responses.addLast(
            CommandResponse(number = 3L, output = emptyList(), isError = false),
        )
        vm.attachClientForTest(client)
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@7", name = ""),
        )
        advanceUntilIdle()
        val listPanesCountBefore = client.sentCommands.count { it.startsWith("list-panes") }

        vm.killWindow("@7")
        // advanceUntilIdle on the test scheduler will roll virtual time
        // past the 2s fallback because nothing else is pending.
        advanceUntilIdle()

        assertTrue(
            "expected fallback reconcile after the 2s wait — got " +
                "${client.sentCommands}",
            client.sentCommands.count { it.startsWith("list-panes") } > listPanesCountBefore,
        )
        assertNull(
            "fallback path must not surface an error",
            vm.windowKillError.value,
        )
    }

    @Test
    fun clearWindowKillErrorRemovesPriorMessage() = runTest {
        // The screen's banner dismiss action wires to this method; it must
        // null out the StateFlow so subsequent recompositions hide the band.
        val vm = newVm()
        val client = ThrowingTmuxClient(
            throwOnCommand = "kill-window",
            exception = IllegalStateException("broken"),
        )
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "work", paneIndex = 0),
            ),
        )
        vm.killWindow("@0")
        advanceUntilIdle()
        assertNotNull(vm.windowKillError.value)

        vm.clearWindowKillError()

        assertNull(vm.windowKillError.value)
    }

    @Test
    fun killWindowIgnoresBlankWindowIdAndNeverDispatches() = runTest {
        // Defensive: the screen passes `currentWindowId.orEmpty()` so a
        // pre-attach kill tap must be a no-op rather than a wire
        // request for `kill-window -t `.
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.killWindow("")
        vm.killWindow("   ")
        advanceUntilIdle()

        assertTrue(
            "blank windowId must not dispatch — got ${client.sentCommands}",
            client.sentCommands.none { it.startsWith("kill-window") },
        )
        assertNull(vm.windowKillError.value)
    }

    /**
     * Test double that throws on a chosen command — exercises the
     * "sendCommand throws" branch of [TmuxSessionViewModel.killWindow]
     * without touching SSH. Mirrors the same-named fake in
     * `SessionsDashboardViewModelTest` so the two ViewModels' error
     * surfacing stays comparable.
     */
    private class ThrowingTmuxClient(
        private val throwOnCommand: String,
        private val exception: Throwable,
    ) : com.pocketshell.core.tmux.TmuxClient {
        private val delegate = FakeTmuxClient()
        override val events = delegate.events
        override val disconnected = delegate.disconnected
        override val outputBacklogOverflows = delegate.outputBacklogOverflows
        val sentCommands: MutableList<String> get() = delegate.sentCommands
        override suspend fun connect() = delegate.connect()
        override suspend fun sendCommand(cmd: String): CommandResponse {
            delegate.sentCommands += cmd
            if (cmd.startsWith(throwOnCommand)) throw exception
            return CommandResponse(0L, emptyList(), false)
        }
        override fun outputFor(paneId: String) = delegate.outputFor(paneId)
        override fun close() = delegate.close()
        // Issue #215: detachCleanly added to the interface; the
        // throwing fake delegates to FakeTmuxClient so kill-window
        // tests don't have to reason about the new method's
        // semantics — they only care that sendCommand throws on the
        // configured command name.
        override suspend fun detachCleanly(timeoutMs: Long) =
            delegate.detachCleanly(timeoutMs)
        override suspend fun setWindowSizeLatest(sessionId: String) =
            delegate.setWindowSizeLatest(sessionId)
        override suspend fun refreshClientSize(cols: Int, rows: Int) =
            delegate.refreshClientSize(cols, rows)
    }

    // ─── Issue #178: same-host fast-switch reuses the SSH transport ───
    //
    // The connect() path detects "same host, different tmux session"
    // and routes through [closeCurrentClientKeepSession] +
    // [runFastSessionSwitch] instead of the full SSH-handshake teardown
    // + reconnect. Production exercise lives in the connected
    // TmuxSessionSwitchSameHostReusesSshE2eTest because a unit test
    // cannot reach into [SshConnection.connect] without a real
    // network. The unit tests below pin the predicate behaviour, the
    // teardown-keep-session invariant, and the registry side-effects
    // through the dedicated test seams the VM exposes.

    @Test
    fun isFastSwitchEligibleRequiresAnActiveTargetAndSession() {
        val vm = newVm()
        assertFalse(
            "no active target -> not eligible",
            vm.isFastSwitchEligibleForTest(
                host = "h",
                port = 22,
                user = "u",
                keyPath = "/k",
                sessionName = "s",
            ),
        )

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            // No session injected — fast switch must require a live
            // SshSession reference, not just an active target.
        )
        assertFalse(
            "no SshSession reference -> not eligible",
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
    }

    @Test
    fun isFastSwitchEligibleRejectsDifferentHostParameters() {
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )
        // Different host
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "bravo.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
        // Different port
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 2222,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
        // Different user
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "other",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
        // Different key path
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/other",
                sessionName = "other",
            ),
        )
    }

    @Test
    fun isFastSwitchEligibleRejectsSameSessionName() {
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )
        // Same host + same session name = no-op, not a fast switch.
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
            ),
        )
    }

    @Test
    fun isFastSwitchEligibleAcceptsSameHostDifferentSession() {
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )
        assertTrue(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
    }

    @Test
    fun isFastSwitchEligibleRejectsDeadSshSession() {
        val vm = newVm()
        // Inject a session that says it is not connected — the predicate
        // must not pretend the transport is reusable.
        val deadSession = FakeSshSession(isConnectedValue = false)
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = deadSession,
        )
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
    }

    @Test
    fun fastSwitchCachesOldRuntimeAndReusesSshSession() = runTest {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(registry = registry, runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
            session = session,
        )
        assertSame(oldClient, registry.clients.value[1L]?.client)
        assertFalse("session must not be closed before fast switch", session.closed)
        assertFalse("old client should still be open before fast switch", oldClient.closed)

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = session,
        )
        advanceUntilIdle()

        // Old tmux client stays warm in the runtime cache; SSH session is
        // reused (NOT closed). Registry now points at the new client.
        assertFalse("old tmux client must stay warm after fast switch", oldClient.closed)
        assertTrue(
            "old runtime must be cached by host/session key",
            runtimeCache.contains(
                TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "work"),
            ),
        )
        assertFalse(
            "fast switch must NOT close the underlying SSH session",
            session.closed,
        )
        assertSame(newClient, registry.clients.value[1L]?.client)
        assertTrue("new tmux client must be connect()ed", newClient.connectCalled)
        // After the fast switch a same-host probe for yet another
        // session name should still report eligible, because the
        // session ref was preserved.
        assertTrue(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "third",
            ),
        )
    }

    @Test
    fun activatingCachedRuntimePublishesPanesSynchronouslyWithoutTmuxCommands() = runTest {
        TmuxSessionLatencyTelemetry.resetForTest()
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(registry = registry, runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val clientA = FakeTmuxClient()
        val clientB = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = clientA,
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "cached-work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        val cachedTerminalState = vm.panes.value.single().terminalState

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = clientB,
            session = session,
        )
        runCurrent()
        clientA.sentCommands.clear()
        clientB.sentCommands.clear()
        clientA.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "%0\t@0\t\$0\twork\tcached-work\t0",
                    "%1\t@0\t\$0\twork\tfresh-remote\t1",
                ),
                isError = false,
            ),
        )
        clientA.capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("fresh remote line"), isError = false),
        )
        clientA.cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )

        val activateStartedAtMs = SystemClock.elapsedRealtime()
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        val activateMs = SystemClock.elapsedRealtime() - activateStartedAtMs

        val panes = vm.panes.value
        val telemetryBeforeRefresh = TmuxSessionLatencyTelemetry.snapshot()
        val firstCachedFrame = telemetryBeforeRefresh.single {
            it.name == "warm_switch_first_cached_frame"
        }
        val forbiddenCommandEventsBeforeFrame = telemetryBeforeRefresh.filter {
            it.elapsedRealtimeMs <= firstCachedFrame.elapsedRealtimeMs &&
                (
                    it.name == "tmux_control_attach_count" ||
                        it.name == "list_panes" ||
                        it.name == "capture_pane" ||
                        it.name == "cursor_query"
                    )
        }
        assertTrue(
            "cached pointer-swap activation must publish visible pane state under 100ms; " +
                "activateMs=$activateMs",
            activateMs < TMUX_WARM_SWITCH_LOCAL_P95_BUDGET_MS,
        )
        assertTrue(
            "first cached-frame telemetry must stay under 100ms; event=$firstCachedFrame",
            firstCachedFrame.durationMs < TMUX_WARM_SWITCH_LOCAL_P95_BUDGET_MS,
        )
        assertTrue(
            "cached first frame must not require synchronous tmux control/list/capture work; " +
                "forbidden=$forbiddenCommandEventsBeforeFrame events=$telemetryBeforeRefresh",
            forbiddenCommandEventsBeforeFrame.isEmpty(),
        )
        assertEquals(
            "work",
            firstCachedFrame.sessionName,
        )
        assertTrue(
            "first cached-frame artifact must include cache-hit detail",
            firstCachedFrame.toArtifactLine(prefix = "warm_switch").contains("cacheHit=true"),
        )
        assertEquals(listOf("%0"), panes.map { it.paneId })
        assertSame(cachedTerminalState, panes.single().terminalState)
        assertSame(clientA, registry.clients.value[1L]?.client)
        assertFalse("cached activation must not create a new tmux client", clientA.connectCalled)
        assertTrue(
            "cached activation must avoid tmux list/capture commands, got ${clientA.sentCommands}",
            clientA.sentCommands.none {
                it.startsWith("list-panes") ||
                    it.startsWith("capture-pane") ||
                    it.startsWith("display-message")
            },
        )
        assertTrue(
            "previous active runtime should now be cached",
            runtimeCache.contains(
                TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "other"),
            ),
        )

        advanceTimeBy(CACHED_RUNTIME_REMOTE_REFRESH_DELAY_MS)
        runCurrent()
        assertTrue(
            "cached activation should refresh list-panes asynchronously after the visible swap",
            clientA.sentCommands.any { it.startsWith("list-panes") },
        )
        assertTrue(
            "new panes discovered by the async refresh should be seeded after the visible swap",
            clientA.sentCommands.contains("capture-pane -p -e -S -200 -t %1"),
        )
        assertTrue(
            "async seed should query the cursor after capture-pane",
            clientA.sentCommands.contains("display-message -p -t %1 '#{cursor_x},#{cursor_y}'"),
        )
        assertEquals(listOf("%0", "%1"), vm.panes.value.map { it.paneId })
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun staleCachedRuntimeRefreshCannotOverwriteNewerSelection() = runTest {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(registry = registry, runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val clientA = FakeTmuxClient()
        val clientB = FakeTmuxClient()
        val listPanesGate = CompletableDeferred<Unit>()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = clientA,
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "cached-work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = clientB,
            session = session,
        )
        runCurrent()
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%9",
                    windowId = "@9",
                    sessionId = "$9",
                    title = "other-visible",
                    paneIndex = 0,
                    sessionName = "other",
                ),
            ),
        )
        clientA.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "%7\t@7\t\$7\twork\tstale-refresh-pane\t0",
                ),
                isError = false,
            ),
        )
        clientA.capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("must not seed"), isError = false),
        )
        clientA.sendCommandGatePrefix = "list-panes"
        clientA.sendCommandGate = listPanesGate

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        assertEquals(listOf("%0"), vm.panes.value.map { it.paneId })
        advanceTimeBy(CACHED_RUNTIME_REMOTE_REFRESH_DELAY_MS)
        runCurrent()
        assertTrue(
            "precondition: cached work refresh is suspended in list-panes",
            clientA.sentCommands.any { it.startsWith("list-panes") },
        )
        clientB.responses.addLast(
            CommandResponse(
                number = 10L,
                output = listOf("%9\t@9\t\$9\tother\tother-visible\t0"),
                isError = false,
            ),
        )

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "other",
        )
        assertEquals("other", vm.activeSessionNameForTest())
        assertEquals(listOf("%9"), vm.panes.value.map { it.paneId })

        listPanesGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "stale work refresh must not replace the newer active session panes",
            listOf("%9"),
            vm.panes.value.map { it.paneId },
        )
        assertEquals("other", vm.activeSessionNameForTest())
        assertFalse(
            "stale refresh must not run capture-pane after losing the runtime guard",
            clientA.sentCommands.any { it.startsWith("capture-pane") },
        )
        assertSame(clientB, registry.clients.value[1L]?.client)
    }

    @Test
    fun activatingCachedRuntimeReinstallsDisconnectedObserver() = runTest {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(registry = registry, runtimeCache = runtimeCache)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val session = FakeSshSession()
        val clientA = FakeTmuxClient()
        val clientB = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = clientA,
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = clientB,
            session = session,
        )
        runCurrent()

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        runCurrent()
        clientA.disconnectedSignal.value = true
        runCurrent()

        assertTrue(
            "restored cached client must use the normal disconnected observer",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertNull(registry.clients.value[1L])
    }

    @Test
    fun activatingCachedRuntimeRestartsAgentConversationTail() = runTest {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val clientA = FakeTmuxClient()
        val clientB = FakeTmuxClient()
        val detection = newClaudeDetection()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = clientA,
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        vm.startAgentConversationForTest("%0", detection)
        vm.startAgentTailForTest("%0", session, detection, fromLineExclusive = 0L)
        assertEquals(1, session.tailCalls)

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = clientB,
            session = session,
        )
        runCurrent()

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        advanceUntilIdle()

        assertEquals(
            "cached restore must restart the conversation tail producer",
            2,
            session.tailCalls,
        )
        assertEquals(AgentConversationSyncStatus.Live, vm.agentConversations.value["%0"]?.syncStatus)
    }

    @Test
    fun restoredRuntimeTailRefreshPreservesConcurrentConversationState() = runTest {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        val execGate = CompletableDeferred<Unit>()
        val clientA = FakeTmuxClient()
        val clientB = FakeTmuxClient()
        val detection = newClaudeDetection()
        val refreshedLog = """
            {"type":"assistant","uuid":"cached","message":{"role":"assistant","content":"cached event"}}
            {"type":"assistant","uuid":"backlog","message":{"role":"assistant","content":"backlog while cached"}}
        """.trimIndent()
        val cachedEvent = ConversationEvent.Message(
            id = "cached",
            agent = AgentKind.ClaudeCode,
            atMillis = 1L,
            role = ConversationRole.Assistant,
            text = "cached event",
        )
        val newerEvent = ConversationEvent.Message(
            id = "newer",
            agent = AgentKind.ClaudeCode,
            atMillis = 2L,
            role = ConversationRole.Assistant,
            text = "newer event",
        )
        val session = FakeSshSession(
            execGate = execGate,
            wcOutput = "2\n",
            initialEventsOutput = refreshedLog,
        )

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = clientA,
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        vm.startAgentConversationForTest("%0", detection, initialEvents = listOf(cachedEvent))

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = clientB,
            session = session,
        )
        runCurrent()

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        runCurrent()

        vm.selectSessionTab("%0", SessionTab.Conversation)
        vm.setAgentSearchQuery("%0", "deploy")
        vm.appendAgentEventsForTest("%0", listOf(newerEvent))

        execGate.complete(Unit)
        advanceUntilIdle()

        val after = vm.agentConversations.value["%0"]!!
        assertEquals(AgentConversationSyncStatus.Live, after.syncStatus)
        assertEquals(SessionTab.Conversation, after.selectedTab)
        assertEquals("deploy", after.searchQuery)
        assertEquals(listOf("cached", "newer", "backlog"), after.events.map { it.id })
        assertEquals(
            "cached restore plus async restart should start exactly one new tail",
            1,
            session.tailCalls,
        )
        assertEquals(listOf("/home/u/.claude/sessions/abc.jsonl" to 2L), session.tailFromLineCalls)
    }

    @Test
    fun runtimeCacheEvictsLeastRecentlyUsedRuntimePerHost() = runTest {
        val cache = TmuxSessionRuntimeCache(maxEntries = 2)
        val first = cachedRuntimeForTest("one")
        val second = cachedRuntimeForTest("two")
        val third = cachedRuntimeForTest("three")

        assertTrue(cache.put(first).isEmpty())
        assertTrue(cache.put(second).isEmpty())
        assertSame(first, cache.activate(first.key).runtime)
        assertTrue(cache.put(first).isEmpty())
        val evicted = cache.put(third)

        assertEquals(
            "second should be the least recently used runtime for the host",
            listOf(second),
            evicted,
        )
        assertTrue(cache.contains(first.key))
        assertTrue(cache.contains(third.key))
        assertFalse(cache.contains(second.key))
    }

    @Test
    fun runtimeCachePerHostCapDoesNotEvictOtherHosts() = runTest {
        val cache = TmuxSessionRuntimeCache(maxEntries = 1)
        val hostOneFirst = cachedRuntimeForTest("one", hostId = 1L)
        val hostTwoFirst = cachedRuntimeForTest("one", hostId = 2L)
        val hostOneSecond = cachedRuntimeForTest("two", hostId = 1L)

        assertTrue(cache.put(hostOneFirst).isEmpty())
        assertTrue(cache.put(hostTwoFirst).isEmpty())
        val evicted = cache.put(hostOneSecond)

        assertEquals(listOf(hostOneFirst), evicted)
        assertFalse(cache.contains(hostOneFirst.key))
        assertTrue(cache.contains(hostOneSecond.key))
        assertTrue(cache.contains(hostTwoFirst.key))
    }

    @Test
    fun runtimeCacheEvictsExpiredRuntimesDeterministically() = runTest {
        var nowMs = 0L
        val cache = TmuxSessionRuntimeCache(
            maxEntries = 2,
            ttlMs = 100L,
            nowMs = { nowMs },
        )
        val expired = cachedRuntimeForTest("expired")
        val fresh = cachedRuntimeForTest("fresh")

        assertTrue(cache.put(expired).isEmpty())
        nowMs = 100L

        val evicted = cache.put(fresh)

        assertEquals(listOf(expired), evicted)
        assertFalse(cache.contains(expired.key))
        assertTrue(cache.contains(fresh.key))
        assertNull(cache.activate(expired.key).runtime)
    }

    @Test
    fun fastSwitchEvictionClosesOldWarmRuntimeAsynchronously() = runTest {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 1)
        val vm = newVm(registry = registry, runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val clientA = FakeTmuxClient()
        val clientB = FakeTmuxClient()
        val clientC = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "one",
            client = clientA,
            session = session,
        )
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "two",
            client = clientB,
            session = session,
        )
        runCurrent()
        assertTrue(runtimeCache.contains(tmuxRuntimeKeyForTest("one")))
        assertFalse("first warm runtime should not close before eviction", clientA.closed)
        clientA.detachCleanlyGate = CompletableDeferred()

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "three",
            client = clientC,
            session = session,
        )

        assertFalse(
            "eviction cleanup is launched asynchronously, outside the switch call",
            clientA.closed,
        )
        assertTrue("eviction cleanup should have started detach work", clientA.detachCleanlyCalled)
        clientA.detachCleanlyGate?.complete(Unit)
        runCurrent()

        assertTrue("evicted warm client must detach/close", clientA.detachCleanlyCalled)
        assertTrue("evicted warm client must close", clientA.closed)
        assertFalse("active selected client must remain usable", clientC.closed)
        assertSame(clientC, registry.clients.value[1L]?.client)
        assertTrue(runtimeCache.contains(tmuxRuntimeKeyForTest("two")))
        assertFalse(runtimeCache.contains(tmuxRuntimeKeyForTest("one")))
    }

    @Test
    fun sessionSwitcherPrewarmCachesOnlyBoundedLikelyTargets() = runTest {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession(), FakeSshSession(), FakeSshSession())
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val activeSession = FakeSshSession()
        val prewarmClients = listOf(
            FakeTmuxClient().withSinglePane("recent-a", "%1"),
            FakeTmuxClient().withSinglePane("recent-b", "%2"),
            FakeTmuxClient().withSinglePane("recent-c", "%3"),
        )
        val clients = ArrayDeque(prewarmClients)
        vm.setTmuxClientFactoryForTest { _, _, _ -> clients.removeFirst() }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = activeSession,
        )

        vm.prewarmLikelySwitchTargets(listOf("work", "recent-a", "recent-b", "recent-c"))
        advanceUntilIdle()

        assertTrue(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent-a")))
        assertTrue(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent-b")))
        assertFalse(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent-c")))
        assertEquals(
            "prewarm must stay capped to likely switch targets",
            TMUX_SESSION_PREWARM_MAX_TARGETS,
            prewarmClients.count { it.connectCalled },
        )
        assertEquals(
            "prewarm should reuse the warm SSH lease when possible",
            1,
            connector.connectCount,
        )
    }

    @Test
    fun switchingToPrewarmedTargetUsesCachedRuntimeFirstFramePath() = runTest {
        TmuxSessionLatencyTelemetry.resetForTest()
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            runtimeCache = runtimeCache,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val activeClient = FakeTmuxClient()
        val prewarmClient = FakeTmuxClient().withSinglePane("recent", "%4")
        vm.setTmuxClientFactoryForTest { _, _, _ -> prewarmClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = activeClient,
            session = FakeSshSession(),
        )

        vm.prewarmLikelySwitchTargets(listOf("recent"))
        advanceUntilIdle()
        prewarmClient.sentCommands.clear()

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "recent",
        )

        assertEquals(listOf("%4"), vm.panes.value.map { it.paneId })
        assertSame(prewarmClient, registry.clients.value[1L]?.client)
        assertTrue(
            TmuxSessionLatencyTelemetry.snapshot().any { it.name == "warm_switch_first_cached_frame" },
        )
        assertTrue(
            "cached activation must not attach a second tmux client",
            prewarmClient.sentCommands.none { it.startsWith("list-panes") },
        )
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun cancelledSessionPrewarmClosesPartialRuntimeWithoutCachingIt() = runTest {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val gate = CompletableDeferred<Unit>()
        val prewarmClient = FakeTmuxClient().withSinglePane("recent", "%5").apply {
            sendCommandGatePrefix = "list-panes"
            sendCommandGate = gate
        }
        vm.setTmuxClientFactoryForTest { _, _, _ -> prewarmClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )

        vm.prewarmLikelySwitchTargets(listOf("recent"))
        runCurrent()
        vm.cancelTmuxSessionPrewarm()
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent")))
        assertTrue("cancelled prewarm must detach its tmux client", prewarmClient.detachCleanlyCalled)
    }

    @Test
    fun switchingToColdTargetStillFallsBackToFastAttach() = runTest {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val coldClient = FakeTmuxClient().withSinglePane("cold", "%6")
        vm.setTmuxClientFactoryForTest { _, _, _ -> coldClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )

        vm.prewarmLikelySwitchTargets(emptyList())
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "cold",
        )
        advanceUntilIdle()

        assertTrue("cold fallback must attach a tmux client", coldClient.connectCalled)
        assertEquals(listOf("%6"), vm.panes.value.map { it.paneId })
        assertTrue(
            "cold fallback should still use normal list-panes attach",
            coldClient.sentCommands.any { it.startsWith("list-panes") },
        )
    }

    // ─── Issue #437 (slice A): same-host switch must NOT blank to ───
    // the full-screen "Connecting" overlay. The cold same-host switch
    // (target not yet cached) enters the new [Switching] state, keeps the
    // previous frame painted, gates input until the new -CC control client
    // attaches, then flips to [Connected] with the new session's panes.

    @Test
    fun sameHostSwitchToUncachedSessionEntersSwitchingNotConnecting() = runTest {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val registry = ActiveTmuxClients()
        val vm = newVm(
            registry = registry,
            runtimeCache = runtimeCache,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        // Gate the cold client's list-panes so the new -CC attach stays
        // in flight while we observe the visible state during the switch.
        val attachGate = CompletableDeferred<Unit>()
        val coldClient = FakeTmuxClient().withSinglePane("cold", "%6").apply {
            sendCommandGatePrefix = "list-panes"
            sendCommandGate = attachGate
        }
        vm.setTmuxClientFactoryForTest { _, _, _ -> coldClient }

        // Establish a live same-host session with a rendered pane so the
        // switch is fast-switch eligible AND has a previous frame to keep.
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "work-pane",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        runCurrent()
        assertEquals(listOf("%0"), vm.panes.value.map { it.paneId })
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)

        // Switch to a session that has never been opened this app-session
        // (cache miss). This is the maintainer's repro.
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "cold",
        )
        runCurrent()

        // While the new -CC client is attaching the screen must be in
        // Switching — NOT the blanking full-screen Connecting overlay —
        // and the previous frame must still be painted.
        val midStatus = vm.connectionStatus.value
        assertTrue(
            "same-host switch must enter Switching, got $midStatus",
            midStatus is TmuxSessionViewModel.ConnectionStatus.Switching,
        )
        assertFalse(
            "same-host switch must never show the blanking Connecting overlay",
            midStatus is TmuxSessionViewModel.ConnectionStatus.Connecting,
        )
        assertEquals(
            "previous frame must stay painted during a same-host switch (no blank)",
            listOf("%0"),
            vm.panes.value.map { it.paneId },
        )
        // Input is gated: only Connected counts as live (mirrors the
        // screen's sessionLive = status is Connected).
        assertFalse(
            "input must stay gated while switching (status != Connected)",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // Complete the attach: the viewport now swaps to the new session's
        // panes and the status flips to Connected (input ungated).
        attachGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            "switch must complete to Connected once the new client attaches",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(
            "viewport must swap to the new session's panes after attach",
            listOf("%6"),
            vm.panes.value.map { it.paneId },
        )
        assertTrue("new -CC client must attach", coldClient.connectCalled)
        assertSame(coldClient, registry.clients.value[1L]?.client)
    }

    @Test
    fun firstConnectToHostStillShowsConnectingNotSwitching() = runTest {
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val vm = newVm(
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        // Gate the first attach so we can observe the visible state before
        // it completes.
        val attachGate = CompletableDeferred<Unit>()
        val client = FakeTmuxClient().withSinglePane("work", "%0").apply {
            sendCommandGatePrefix = "list-panes"
            sendCommandGate = attachGate
        }
        vm.setTmuxClientFactoryForTest { _, _, _ -> client }

        // No prior active session => genuine first-connect to the host.
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        runCurrent()

        val status = vm.connectionStatus.value
        assertTrue(
            "first-connect must show the full-screen Connecting overlay, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Connecting,
        )
        assertFalse(
            "first-connect must NOT use the same-host Switching state",
            status is TmuxSessionViewModel.ConnectionStatus.Switching,
        )

        attachGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(listOf("%0"), vm.panes.value.map { it.paneId })
    }

    @Test
    fun sameHostSwitchDeadSshSessionEscalatesToConnectingAndBlanks() = runTest {
        // #178 dead-session fallback preserved: if the reused SSH session
        // died mid-switch we do a genuine reconnect, so the UI must
        // escalate from Switching to the full-screen Connecting overlay
        // and drop the now-stale previous frame (no painting a dead pane).
        // The active session is LIVE at the eligibility check (so the
        // switch is fast-switch eligible and enters Switching), but the
        // lease the fast-switch reacquires comes back dead — exactly the
        // #178 race. The fast-switch fallback then reconnects with a fresh
        // live lease.
        val activeSession = FakeSshSession()
        val deadLeaseSession = FakeSshSession(isConnectedValue = false)
        val fallbackSession = FakeSshSession()
        val connector = QueueLeaseConnector(deadLeaseSession, fallbackSession)
        val vm = newVm(
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val reconnectGate = CompletableDeferred<Unit>()
        val fallbackClient = FakeTmuxClient().withSinglePane("cold", "%6").apply {
            sendCommandGatePrefix = "list-panes"
            sendCommandGate = reconnectGate
        }
        vm.setTmuxClientFactoryForTest { _, _, _ -> fallbackClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = activeSession,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "work-pane",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        runCurrent()

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "cold",
        )
        runCurrent()

        // The reused session is dead, so the fast-switch path falls back
        // to runConnect: UI escalates to Connecting and the stale frame is
        // dropped while the fresh handshake runs.
        val midStatus = vm.connectionStatus.value
        assertTrue(
            "dead-session fallback must escalate to the full-screen Connecting overlay, got $midStatus",
            midStatus is TmuxSessionViewModel.ConnectionStatus.Connecting,
        )
        assertTrue(
            "dead-session fallback must drop the stale previous frame",
            vm.panes.value.isEmpty(),
        )

        reconnectGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(listOf("%6"), vm.panes.value.map { it.paneId })
    }

    @Test
    fun closeCachedRuntimeDetachesClientBeforeReleasingLease() = runTest {
        val session = FakeSshSession()
        val manager = SshLeaseManager(
            connector = SshLeaseConnector { Result.success(session) },
            idleTtlMillis = 0L,
        )
        val lease = manager.acquire(testLeaseTarget()).getOrThrow()
        val client = FakeTmuxClient()
        val runtime = cachedRuntimeForTest(
            sessionName = "work",
            client = client,
            session = session,
            lease = lease,
        )

        runtime.closeCachedRuntime()

        assertTrue(
            "cached tmux client must detach before lease release completes",
            client.detachCleanlyCalled,
        )
        assertTrue("final cached lease release should close the idle SSH session", session.closed)
    }

    @Test
    fun fastSwitchDeadSessionFallbackReleasesAcquiredLeaseBeforeRetry() = runTest {
        val deadLeaseSession = FakeSshSession(isConnectedValue = false)
        val fallbackSession = FakeSshSession()
        val connector = QueueLeaseConnector(deadLeaseSession, fallbackSession)
        val manager = SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 0L,
        )
        val vm = newVm(sshLeaseManager = manager)
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "other",
        )
        advanceUntilIdle()

        assertEquals(
            "dead fast-switch lease should be released before the fallback reacquires",
            2,
            connector.connectCount,
        )
        assertTrue("dead fast-switch lease must be closed by release", deadLeaseSession.closed)
    }

    @Test
    fun fastSwitchKeepsPreviousFrameUntilNewSessionPanesReconcile() = runTest {
        // Issue #437 (slice A): a same-host fast switch must NOT blank the
        // viewport. The previous frame stays painted (no "Connecting"
        // blank) until the new session's panes reconcile and atomically
        // replace it. This reverses the old "clear pane state on fast
        // switch" behaviour — hard-cut, per D22.
        val vm = newVm()
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
            session = session,
        )

        // Populate a pane row that represents the previous frame. The
        // sessionName must match the active target's session name;
        // otherwise [applyParsedPanes] filters it out.
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "old",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        assertEquals(1, vm.panes.value.size)

        // The new client reports no panes here, so nothing reconciles to
        // replace the previous frame — letting us assert the frame is kept
        // rather than blanked during the switch.
        val newClient = FakeTmuxClient()
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = session,
        )
        advanceUntilIdle()

        // Previous frame stays painted across the switch (no blank).
        assertEquals(
            "previous frame must be kept until the new session reconciles, was ${vm.panes.value}",
            listOf("%0"),
            vm.panes.value.map { it.paneId },
        )
    }

    @Test
    fun fastSwitchDoesNotDetachPreviousClientOnCriticalPath() = runTest {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(registry = registry, runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
            session = session,
        )

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = session,
        )
        runCurrent()

        assertFalse(
            "warm runtime caching must not detach the old client on the switch path",
            oldClient.detachCleanlyCalled,
        )
        assertFalse(vm.hasInFlightOrphanDetachForTest())
        assertTrue("new tmux client must be connect()ed", newClient.connectCalled)
        assertSame(newClient, registry.clients.value[1L]?.client)
        assertTrue(
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse(
            "old client must stay open as a warm cached runtime",
            oldClient.closed,
        )
        assertTrue(
            runtimeCache.contains(
                TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "work"),
            ),
        )
        assertFalse(
            "SSH session must be reused, never closed by a fast switch",
            session.closed,
        )
    }

    @Test
    fun fastSwitchIncrementsTmuxConnectCounterButNotSshHandshakeCounter() = runTest {
        val vm = newVm()
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
            session = session,
        )

        // Snapshot the SSH-handshake counter — the fast switch must
        // NOT increment it (the test seam bypasses runConnect entirely,
        // matching what production does when the eligibility predicate
        // passes).
        val handshakesBefore = SSH_HANDSHAKE_ATTEMPTS.get()
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = session,
        )
        advanceUntilIdle()

        val handshakesAfter = SSH_HANDSHAKE_ATTEMPTS.get()
        assertEquals(
            "SSH handshakes must not advance when fast-switch reuses the transport",
            handshakesBefore,
            handshakesAfter,
        )
    }

    @Test
    fun fastSwitchTelemetryUsesVisibleSwitchBaseline() = runTest {
        TmuxSessionLatencyTelemetry.resetForTest()
        val vm = newVm()
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()
        val visibleSwitchStartedAtMs = SystemClock.elapsedRealtime() - 250L

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
            session = session,
        )

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = session,
            startedAtMs = visibleSwitchStartedAtMs,
        )
        advanceUntilIdle()

        val warmEvents = TmuxSessionLatencyTelemetry.snapshot()
            .filter { it.name.startsWith("warm_switch_") }
        val start = warmEvents.single { it.name == "warm_switch_start" }
        val shellAttached = warmEvents.single { it.name == "warm_switch_tmux_shell_attached" }
        val connectReady = warmEvents.single { it.name == "warm_switch_connect_ready" }

        assertTrue(
            "start should use the caller's visible-switch baseline: $warmEvents",
            start.durationMs >= 250L,
        )
        assertTrue(
            "shell-attached must keep the same baseline instead of restarting after teardown",
            shellAttached.durationMs >= start.durationMs,
        )
        assertTrue(
            "connect-ready must keep the same baseline instead of restarting after teardown",
            connectReady.durationMs >= shellAttached.durationMs,
        )
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun closedPaneClearsConversationRoutingState() = runTest {
        // A pane that tmux removes between reconciles cannot keep any
        // conversation state behind.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0)),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        assertEquals(SessionTab.Conversation, vm.agentConversations.value["%0"]!!.selectedTab)

        // Reconcile with the pane gone.
        vm.applyParsedPanesForTest(emptyList())

        assertTrue(
            "conversation state must be empty when the pane disappears",
            vm.agentConversations.value.isEmpty(),
        )
    }

    // Issue #165 — cancelConnect tests. The progress overlay's 15s
    // Cancel affordance routes through [TmuxSessionViewModel.cancelConnect];
    // these tests assert it cancels the in-flight connect job AND flips
    // status to Failed so the screen renders a deterministic post-cancel
    // state instead of staying stuck on Connecting.

    @Test
    fun cancelConnectFlipsConnectingStatusToFailedAndCancelsJob() = runTest {
        val vm = newVm()
        // A real Job we can inspect post-cancel, parented to the test
        // scope. The production [connect] launches into [viewModelScope];
        // for this seam we just need the cancelable handle.
        val job = kotlinx.coroutines.Job()
        vm.beginConnectingForTest(host = "alpha.example", port = 22, user = "alex", job = job)
        assertTrue(
            "precondition: status must be Connecting",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connecting,
        )

        val fired = vm.cancelConnect()

        assertTrue("cancelConnect() must report success when called during Connecting", fired)
        val status = vm.connectionStatus.value
        assertTrue(
            "status must be Failed after cancel, was $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Connect cancelled by user.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
        assertTrue("connectJob must be cancelled by cancelConnect()", job.isCancelled)
    }

    @Test
    fun cancelConnectIsNoOpWhenNotConnecting() = runTest {
        val vm = newVm()
        // Status starts Idle — cancel must be a no-op.
        val firedFromIdle = vm.cancelConnect()
        assertFalse("cancelConnect() must no-op when status is Idle", firedFromIdle)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Idle)

        // Drive to Connected via the test seam and verify cancel is a
        // no-op there too — the screen's Cancel button is gated on
        // Connecting, but the defensive check inside cancelConnect()
        // is the safety net for direct programmatic callers.
        vm.attachClientForTest(FakeTmuxClient())
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        val firedFromConnected = vm.cancelConnect()
        assertFalse("cancelConnect() must no-op when status is Connected", firedFromConnected)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    /**
     * Issue #235: `onAppBackgrounded` must call `detachCleanly` on the
     * live tmux client when the process goes to background. We drive
     * the VM into a connected state via [replaceClientForTest] (which
     * also stamps an [activeTarget] in place) so the backgrounded hook
     * has something to tear down.
     */
    @Test
    fun onAppBackgroundedCallsDetachCleanlyOnLiveClient() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertFalse("detach must not have fired before background", client.detachCleanlyCalled)

        vm.onAppBackgrounded()
        advanceUntilIdle()

        assertTrue(
            "onAppBackgrounded must invoke detachCleanly via closeCurrentConnectionAndJoin",
            client.detachCleanlyCalled,
        )
        assertTrue("client must be closed after backgrounded detach", client.closed)
    }

    @Test
    fun onClearedWhileBackgroundedParksLiveRuntimeInsteadOfClosingLeaseBeforeGrace() = runTest {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        val client = FakeTmuxClient()
        val session = FakeSshSession()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
            session = session,
        )
        vm.setProcessForegroundForClearedForTest(false)

        vm.clearForTest()
        runCurrent()

        assertFalse(
            "background ViewModel clear must not detach the live tmux client before grace elapses",
            client.detachCleanlyCalled,
        )
        assertFalse(
            "background ViewModel clear must not close the live tmux client before grace elapses",
            client.closed,
        )
        assertFalse(
            "background ViewModel clear must leave the SSH session open for the grace handoff",
            session.closed,
        )
        assertEquals(
            "parked runtime should be available for the recreated Activity/ViewModel",
            listOf(tmuxRuntimeKeyForTest("work")),
            runtimeCache.snapshotKeys(),
        )
    }

    @Test
    fun onClearedAfterScreenStopParksRuntimeDuringProcessLifecycleStopDebounce() = runTest {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        val client = FakeTmuxClient()
        val session = FakeSshSession()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
            session = session,
        )
        vm.setProcessForegroundForClearedForTest(true)

        vm.onScreenStopped()
        vm.clearForTest()
        runCurrent()

        assertFalse(
            "screen-stopped clear must not detach during ProcessLifecycleOwner ON_STOP debounce",
            client.detachCleanlyCalled,
        )
        assertFalse("screen-stopped clear must keep the tmux client alive", client.closed)
        assertFalse("screen-stopped clear must keep the SSH session alive", session.closed)
        assertEquals(listOf(tmuxRuntimeKeyForTest("work")), runtimeCache.snapshotKeys())
    }

    @Test
    fun onClearedWhileBackgroundedClosesEvictedParkedRuntimeOutsideViewModelScope() = runTest {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 1)
        val evictedSession = FakeSshSession()
        val evictedLeaseManager = SshLeaseManager(
            connector = SshLeaseConnector { Result.success(evictedSession) },
            idleTtlMillis = 0L,
        )
        val evictedLease = evictedLeaseManager.acquire(testLeaseTarget()).getOrThrow()
        val evictedClient = FakeTmuxClient()
        runtimeCache.put(
            cachedRuntimeForTest(
                sessionName = "old",
                client = evictedClient,
                session = evictedSession,
                lease = evictedLease,
            ),
        )
        val vm = newVm(runtimeCache = runtimeCache)
        val activeClient = FakeTmuxClient()
        val activeSession = FakeSshSession()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = activeClient,
            session = activeSession,
        )
        vm.setProcessForegroundForClearedForTest(false)

        vm.clearForTest()
        runCurrent()

        assertTrue("evicted parked client must detach during background clear", evictedClient.detachCleanlyCalled)
        assertTrue("evicted parked client must close during background clear", evictedClient.closed)
        assertTrue("evicted SSH lease must be released during background clear", evictedSession.closed)
        assertFalse("newly parked active client must remain live for grace handoff", activeClient.closed)
        assertFalse("active SSH session must remain live for grace handoff", activeSession.closed)
        assertEquals(listOf(tmuxRuntimeKeyForTest("work")), runtimeCache.snapshotKeys())
        assertFalse(runtimeCache.contains(tmuxRuntimeKeyForTest("old")))
    }

    @Test
    fun foregroundOnClearedStillClosesLiveRuntimeForUserNavigation() = runTest {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        val client = FakeTmuxClient()
        val session = FakeSshSession()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
            session = session,
        )
        vm.setProcessForegroundForClearedForTest(true)

        vm.clearForTest()
        runCurrent()

        assertTrue("foreground clear must keep the explicit detach/close behavior", client.detachCleanlyCalled)
        assertTrue("foreground clear must close the tmux client", client.closed)
        assertTrue("foreground clear must close the SSH session", session.closed)
        assertTrue("foreground clear must not leave a parked runtime", runtimeCache.snapshotKeys().isEmpty())
    }

    @Test
    fun restoredParkedRuntimeRebindsViewModelScopedPaneJobsWithoutSshReconnect() = runTest {
        val runtimeCache = TmuxSessionRuntimeCache()
        val parkedClient = FakeTmuxClient()
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(FakeSshSession())
        val pane = TmuxPaneState(
            paneId = "%0",
            windowId = "@0",
            sessionId = "\$0",
            title = "work",
            cwd = "/repo",
            currentCommand = "bash",
            paneTty = "/dev/pts/1",
            terminalState = TerminalSurfaceState(),
        )
        runtimeCache.put(
            CachedTmuxRuntime(
                key = tmuxRuntimeKeyForTest("work"),
                hostName = "alpha",
                startDirectory = null,
                session = session,
                client = parkedClient,
                panes = listOf(pane),
                paneRows = mapOf("%0" to pane),
                paneProducerJobs = mapOf("%0" to Job().also { it.cancel() }),
                paneInputQueues = emptyMap(),
                paneInputJobs = mapOf("%0" to Job().also { it.cancel() }),
                paneAgentJobs = emptyMap(),
                paneAgentInputs = emptyMap(),
                agentConversations = emptyMap(),
                remoteColumns = 80,
                remoteRows = 24,
            ),
        )
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        runCurrent()

        assertEquals("cache hit must avoid opening a fresh SSH lease", 0, connector.connectCount)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(listOf("%0"), vm.panes.value.map { it.paneId })
        assertTrue("restored pane must be reattached to the new ViewModel scope", pane.terminalState.isAttached)
        vm.tmuxInputSinkForTest("%0").write("x".toByteArray())
        runCurrent()
        assertTrue(
            "rebuilt input queue must send through the parked tmux client",
            parkedClient.sentCommands.any { it.startsWith("send-keys -l -t %0") },
        )
    }

    @Test
    fun foregroundReturnWithinGraceRestoresParkedRuntimeWithoutVisibleReconnectState() = runTest {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val firstVm = newVm(registry = registry, runtimeCache = runtimeCache)
        val client = FakeTmuxClient()
        val session = FakeSshSession()
        firstVm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
            session = session,
        )
        firstVm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        firstVm.setProcessForegroundForClearedForTest(true)

        firstVm.onScreenStopped()
        firstVm.clearForTest()
        runCurrent()

        assertFalse("within-grace screen clear must keep tmux client live", client.closed)
        assertFalse("within-grace screen clear must keep SSH session live", session.closed)
        assertEquals(listOf(tmuxRuntimeKeyForTest("work")), runtimeCache.snapshotKeys())

        val connector = QueueLeaseConnector(FakeSshSession())
        val restoredVm = newVm(
            registry = registry,
            runtimeCache = runtimeCache,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val observedStatuses = mutableListOf<TmuxSessionViewModel.ConnectionStatus>()
        val statusJob = backgroundScope.launch {
            restoredVm.connectionStatus.collect { observedStatuses += it }
        }
        runCurrent()

        restoredVm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        runCurrent()
        statusJob.cancel()

        assertEquals("cache hit must avoid opening a fresh SSH lease", 0, connector.connectCount)
        assertEquals(listOf("%0"), restoredVm.panes.value.map { it.paneId })
        assertSame(client, registry.clients.value[1L]?.client)
        assertTrue(restoredVm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertTrue(
            "foreground return inside grace must not flash user-visible reconnect/teardown status; " +
                "observed=$observedStatuses",
            observedStatuses.none {
                it is TmuxSessionViewModel.ConnectionStatus.Connecting ||
                    it is TmuxSessionViewModel.ConnectionStatus.Switching ||
                    it is TmuxSessionViewModel.ConnectionStatus.Reconnecting ||
                    it is TmuxSessionViewModel.ConnectionStatus.Failed
            },
        )
    }

    @Test
    fun onAppBackgroundedClosesInactiveCachedRuntimesForHost() = runTest {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val cachedClient = FakeTmuxClient()
        val foregroundClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = cachedClient,
            session = session,
        )
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = foregroundClient,
            session = session,
        )
        runCurrent()
        assertTrue(
            runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "work")),
        )

        vm.onAppBackgrounded()
        advanceUntilIdle()

        assertTrue("foreground runtime must detach on background", foregroundClient.detachCleanlyCalled)
        assertTrue("inactive cached runtime must also detach on background", cachedClient.detachCleanlyCalled)
        assertFalse(
            "background detach must remove inactive cached runtimes for the host",
            runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "work")),
        )
    }

    /**
     * Issue #235 integration race: foreground can arrive after
     * `ON_STOP` starts the background detach but before
     * [closeCurrentConnectionAndJoin] clears the still-connected VM
     * state. The pending reattach must survive that window; otherwise
     * connect() sees the old active target, returns as already
     * connected, and the later detach leaves the screen disconnected
     * with no pending reattach left.
     */
    @Test
    fun onAppForegroundedWaitsForInFlightBackgroundDetachBeforeConsumingReattach() = runTest {
        val vm = newVm()
        val detachGate = CompletableDeferred<Unit>()
        val client = FakeTmuxClient().apply {
            detachCleanlyGate = detachGate
        }
        var foregroundReattachCount = 0
        vm.setForegroundReattachForTest {
            foregroundReattachCount += 1
        }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.onAppBackgrounded()
        runCurrent()
        assertTrue("detach must have started", client.detachCleanlyCalled)
        assertFalse("detach is intentionally still in flight", client.closed)
        assertTrue("background detach must seed pending reattach", vm.hasPendingReattachForTest())

        vm.onAppForegrounded()
        runCurrent()

        assertTrue(
            "foreground must not consume pending reattach until detach finishes",
            vm.hasPendingReattachForTest(),
        )
        assertEquals(
            "foreground reattach must wait for background detach",
            0,
            foregroundReattachCount,
        )

        detachGate.complete(Unit)
        advanceUntilIdle()

        assertFalse(
            "pending reattach should be consumed after detach completes",
            vm.hasPendingReattachForTest(),
        )
        assertEquals(1, foregroundReattachCount)
        assertTrue("client must be closed after backgrounded detach", client.closed)
    }

    /**
     * Issue #272: if the user starts switching from session A to session
     * B and the app backgrounds before B finishes attaching, lifecycle
     * foreground must not silently reattach A. The newer route/connect
     * intent owns foreground, so lifecycle reattach is an explicit no-op
     * and the B target remains available for the in-flight connect path.
     */
    @Test
    fun onAppForegroundedSkipsDetachedSessionWhenNewerConnectIntentExists() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        var foregroundReattachCount = 0
        vm.setForegroundReattachForTest {
            foregroundReattachCount += 1
        }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "session-a",
            client = client,
        )
        vm.beginConnectingForTest(
            host = "alpha.example",
            port = 22,
            user = "alex",
            sessionName = "session-b",
            job = Job(),
        )

        vm.onAppBackgrounded()
        advanceUntilIdle()

        assertTrue("background detach must seed pending reattach", vm.hasPendingReattachForTest())
        assertEquals(
            "newer connecting session must survive lifecycle teardown",
            "session-b",
            vm.connectingSessionNameForTest(),
        )

        vm.onAppForegrounded()
        advanceUntilIdle()

        assertFalse(
            "pending reattach should be consumed as a deliberate newer-intent no-op",
            vm.hasPendingReattachForTest(),
        )
        assertEquals(
            "lifecycle must not reattach the detached A session when B is intended",
            0,
            foregroundReattachCount,
        )
        assertEquals(
            "session-b",
            vm.connectingSessionNameForTest(),
        )
    }

    /**
     * Issue #235: with no live client, `onAppBackgrounded` must be a
     * no-op. The lifecycle observer fires for every process-level
     * `ON_STOP`, so the hook is invoked even when the user never
     * opened a tmux session.
     */
    @Test
    fun onAppBackgroundedIsNoOpWhenNoActiveClient() = runTest {
        val vm = newVm()
        // Status is Idle, no client attached.
        vm.onAppBackgrounded()
        advanceUntilIdle()
        // No exception and the VM stays Idle.
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Idle)
    }

    /**
     * Issue #235: the manual Detach button drives [detachAndExit],
     * which must run the same detach-cleanly + close path AND clear
     * any pending reattach so a subsequent background event does not
     * unexpectedly resurrect the session the user just walked away
     * from.
     */
    @Test
    fun detachAndExitTearsClientDownAndClearsPendingReattach() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.detachAndExit()
        advanceUntilIdle()

        assertTrue("detachAndExit must invoke detachCleanly", client.detachCleanlyCalled)
        assertTrue("client must be closed after manual detach", client.closed)

        // After detachAndExit, a follow-up onAppForegrounded must NOT
        // resurrect the session — the user explicitly walked away.
        // The hook should find no pending target and do nothing.
        val priorStatus = vm.connectionStatus.value
        vm.onAppForegrounded()
        advanceUntilIdle()
        // The status the screen reads stays consistent with "no
        // connection in flight". (The full-cycle assertion lives in the
        // connected E2E test; here we just verify the no-op invariant.)
        assertSame(
            "onAppForegrounded after detachAndExit must not transition status",
            priorStatus,
            vm.connectionStatus.value,
        )
    }

    /**
     * Issue #235: `ActiveTmuxClients.registerLifecycleHooks` is the
     * seam the [com.pocketshell.app.App] observer reads off. Every
     * successful attach (slow-path, fast-switch, or
     * `replaceClientForTest`) must install hooks under the connected
     * host id so the application-scope fanout can find them.
     */
    @Test
    fun replaceClientInstallsLifecycleHooksIntoRegistry() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
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

        val hooks = registry.lifecycleHooksSnapshot()
        assertEquals("expected exactly one hook installed", 1, hooks.size)
    }

    /**
     * Issue #235: a connection teardown (e.g. lifecycle-driven
     * auto-detach) must NOT remove the lifecycle hook from the
     * registry — otherwise the very first auto-detach would drop the
     * foreground reattach hook and the app would never reattach on
     * `ON_START`. Hooks are removed only on `onCleared` (separate
     * test below).
     */
    @Test
    fun connectionTeardownPreservesLifecycleHooks() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.replaceClientForTest(
            hostId = 9L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        assertEquals(1, registry.lifecycleHooksSnapshot().size)

        // `detachAndExit` drives `closeCurrentConnectionAndJoin`, which
        // ends in `activeTmuxClients.unregister(hostId)`. The hooks
        // must survive — they are tied to the VM, not the client.
        vm.detachAndExit()
        advanceUntilIdle()

        assertEquals(
            "lifecycle hook must survive connection teardown",
            1,
            registry.lifecycleHooksSnapshot().size,
        )
        // Client entry IS gone — that's the per-cycle state.
        assertTrue(
            "client entry must be unregistered after detach",
            registry.clients.value.isEmpty(),
        )
    }

    /**
     * Issue #235: `onCleared` is the only path that drops the
     * lifecycle hook. Sanity-checks the hook lifetime invariant.
     */
    @Test
    fun onClearedRemovesLifecycleHooksFromRegistry() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.replaceClientForTest(
            hostId = 11L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        assertEquals(1, registry.lifecycleHooksSnapshot().size)

        // `clearForTest` is the reflective seam tests use to drive
        // `onCleared` outside the Android lifecycle machinery.
        vm.clearForTest()
        advanceUntilIdle()

        assertTrue(
            "lifecycle hook must be removed when VM is cleared",
            registry.lifecycleHooksSnapshot().isEmpty(),
        )
    }

    // ---- Issue #448 (epic #432 slice C): new-port detection overlay ----

    /**
     * Attach a client + session that reports [listeningPorts] from its
     * `ss` confirm scan, then materialise one pane so the detection
     * collector is wired onto the pane's shared output flow.
     */
    private fun TmuxSessionViewModel.attachForPortDetection(
        client: FakeTmuxClient,
        session: FakeSshSession,
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
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "shell", paneIndex = 0),
            ),
        )
    }

    @Test
    fun confirmedNewPortSurfacesOverlay() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(5173))
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
    fun assistantConversationLocalhostUrlSurfacesPortForwardOverlay() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(5173))
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
    fun assistantConversationLoopbackPortPhraseSurfacesPortForwardOverlay() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(3000))
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
    fun agentToolResultLoopbackPortSurfacesPortForwardOverlay() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(8000))
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
    fun userConversationLocalhostUrlDoesNotSurfacePortForwardOverlay() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(5173))
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
    fun echoedPortNotListeningDoesNotSurfaceOverlay() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        // ss reports nothing listening — the regex hit is an echoed/old URL.
        val session = FakeSshSession(ssListeningPorts = emptySet())
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()

        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on http://127.0.0.1:8000\n".toByteArray()),
        )
        advanceUntilIdle()

        assertNull("unconfirmed port must not surface an overlay", vm.detectedPort.value)
    }

    @Test
    fun acceptingDetectedPortReturnsItAndClearsOverlay() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(8000))
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
    fun dismissedPortDoesNotReSurfaceInSameSession() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(8000))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertEquals(8000, vm.detectedPort.value)

        vm.dismissDetectedPort()
        assertNull(vm.detectedPort.value)

        // Same port reprinted later in the session — must not re-prompt.
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertNull("dismissed port must not re-prompt", vm.detectedPort.value)
    }

    @Test
    fun forwardedPortDoesNotReSurfaceInSameSession() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(8000))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertEquals(8000, vm.acceptDetectedPort())

        // Same port reprinted after the user forwarded it — no re-prompt.
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertNull("forwarded port must not re-prompt", vm.detectedPort.value)
    }

    private fun cachedRuntimeForTest(
        sessionName: String,
        hostId: Long = 1L,
        client: FakeTmuxClient = FakeTmuxClient(),
        session: FakeSshSession = FakeSshSession(),
        lease: SshLease? = null,
    ): CachedTmuxRuntime {
        val key = tmuxRuntimeKeyForTest(sessionName = sessionName, hostId = hostId)
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
            paneAgentJobs = emptyMap(),
            paneAgentInputs = emptyMap(),
            agentConversations = emptyMap(),
            remoteColumns = 0,
            remoteRows = 0,
            lease = lease,
        )
    }

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

    private fun tmuxRuntimeKeyForTest(
        sessionName: String,
        hostId: Long = 1L,
    ): TmuxRuntimeKey =
        TmuxRuntimeKey(
            hostId = hostId,
            hostname = "alpha.example",
            port = 22,
            username = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
        )

    private fun testLeaseTarget(): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "alpha.example",
                port = 22,
                user = "alex",
                credentialId = "1:/keys/a",
            ),
            key = SshKey.Path(File("/keys/a")),
        )

    private fun codexScaleOutputChunk(index: Int): ByteArray {
        val linePrefix = "codex-overload-${index.toString().padStart(4, '0')}"
        val line = "$linePrefix " + "x".repeat(240) + "\r\n"
        return buildString {
            repeat(CODEX_SCALE_OUTPUT_LINES_PER_CHUNK) { append(line) }
        }.toByteArray(Charsets.UTF_8)
    }

    private fun codexTranscriptWithToolFlood(toolResults: Int): List<String> = buildList {
        add(
            """{"type":"session_meta","payload":{"id":"xyz","cwd":"/work"}}""",
        )
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

    private fun issue576BackpressureOutputChunks(): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        chunks += "\u001b[31mISSUE576-VM-START\u001b[0m\r\n".toByteArray(Charsets.UTF_8)
        chunks += ("VM-LONG-LINE-" + "B".repeat(10_000) + "\r\n").toByteArray(Charsets.UTF_8)
        chunks += "\u001b[".toByteArray(Charsets.UTF_8)
        chunks += "?25l".toByteArray(Charsets.UTF_8)
        repeat(180) { index ->
            chunks += buildString {
                append("\u001b[38;5;")
                append(index % 256)
                append('m')
                append("vm-frag-")
                append(index.toString().padStart(3, '0'))
                append(' ')
                append(('A'.code + (index % 26)).toChar().toString().repeat(700))
                if (index % 7 == 0) append("\u001b[2K")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)
        }
        chunks += "\u001b[?25h\u001b[0m\r\nISSUE576-VM-DONE\r\n".toByteArray(Charsets.UTF_8)
        return chunks
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
    ): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = previous,
            current = current,
            previousValidated = previousValidated,
            reason = reason,
            sequence = sequence,
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

    private companion object {
        const val CODEX_SCALE_OUTPUT_CHUNKS = 320
        const val CODEX_SCALE_OUTPUT_LINES_PER_CHUNK = 20
        const val CODEX_SCALE_OUTPUT_BYTES = 1_500_000
        const val ISSUE_576_CODEX_USER_PROMPT = "issue 576 synthetic Codex prompt before tool flood"
        const val ISSUE_576_CODEX_ASSISTANT_REPLY = "issue 576 synthetic Codex final reply"
    }

    /**
     * Issue #440: lease connector that always fails with [failure]. Used to
     * drive the non-retryable abort path of the auto-reconnect loop. Counts
     * connect attempts so the test can assert the backoff loop stopped after
     * a single try instead of exhausting the whole schedule.
     */
    private class FailingLeaseConnector(
        private val failure: Throwable,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<com.pocketshell.core.ssh.SshSession> {
            connectCount += 1
            return Result.failure(failure)
        }
    }

    /**
     * Issue #440: lease connector that fails for the first [failures].size
     * attempts (transient failures), then returns [session]. Used to prove
     * the backoff loop keeps retrying through retryable failures and
     * recovers once the transport comes back.
     */
    private class FailingThenConnectingLeaseConnector(
        private val failures: List<Throwable>,
        private val session: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set
        private val inFlightConnects: AtomicInteger = AtomicInteger(0)

        var maxConcurrentConnects: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<com.pocketshell.core.ssh.SshSession> {
            val inFlight = inFlightConnects.incrementAndGet()
            maxConcurrentConnects = maxOf(maxConcurrentConnects, inFlight)
            return try {
                val index = connectCount
                connectCount += 1
                failures.getOrNull(index)?.let { Result.failure(it) }
                    ?: Result.success(session)
            } finally {
                inFlightConnects.decrementAndGet()
            }
        }
    }

    /**
     * Issue #440: a stand-in whose [Class.getSimpleName] is exactly
     * `UserAuthException` — the sshj authentication-failure type the
     * production classifier keys off — so the unit test exercises the
     * non-retryable abort without pulling the sshj hierarchy onto the
     * test classpath. The classifier matches on the simple name, so the
     * nested class name is what matters here.
     */
    private class UserAuthException(message: String) : Exception(message)

    /**
     * Issue #440: a stand-in whose simple name is `ConnectException`
     * (mirroring `java.net.ConnectException`) so the test can confirm
     * "connection refused" stays on the retryable path — it must NOT match
     * the non-retryable classifier.
     */
    private class ConnectException(message: String) : IOException(message)

    /**
     * Issue #178: minimal in-memory [SshSession] double for the
     * fast-switch unit tests. Mirrors the same shape as
     * `FakeSshSession` in `SessionViewModelTest` (intentionally a local
     * private class so that file keeps its own seam) — the production
     * code only consults [isConnected] / [close] from the fast-switch
     * path, but we still implement the rest of the interface as no-ops
     * so any future change to the VM that touches another method on
     * the session does not silently break this test.
     */
    private class FakeSshSession(
        private val isConnectedValue: Boolean = true,
        private val tailJob: CompletableJob = Job(),
        private val execGate: CompletableDeferred<Unit>? = null,
        private val wcOutput: String = "0\n",
        private val initialEventsOutput: String = "",
        private val agentLogLines: List<String>? = null,
        private val detectionOutput: String = "",
        private val processOutput: String = "",
        // Issue #448: ports the `ss -tlnp` confirm scan reports as
        // LISTENing. The detection collector calls PortScanner.scan, which
        // runs `ss -tlnp ... | awk ...`; we answer that with one line per
        // listening port in the `addr:port process` shape PortScanner parses.
        private val ssListeningPorts: Set<Int> = emptySet(),
    ) : com.pocketshell.core.ssh.SshSession {
        @Volatile
        var closed: Boolean = false

        val execCommands = mutableListOf<String>()

        override val isConnected: Boolean
            get() = isConnectedValue && !closed

        override suspend fun exec(command: String): com.pocketshell.core.ssh.ExecResult {
            execCommands += command
            execGate?.await()
            val stdout = when {
                command.contains("ss -tlnp") ->
                    ssListeningPorts.joinToString("\n") { "0.0.0.0:$it users:((\"server\",pid=1,fd=3))" }
                command.contains("netstat -tlnp") || command.contains("ss -tln") -> ""
                command.contains("wc -l < ") -> wcOutput
                command.contains("pocketshell agent-log") -> agentLogEnvelope(command) ?: initialEventsOutput
                command.startsWith("tail -n ") -> initialEventsOutput
                command.contains("ps -eo pid,ppid,tty,comm,args") -> processOutput
                command.contains(".claude") ||
                    command.contains(".codex") ||
                    command.contains("opencode") -> detectionOutput
                else -> ""
            }
            return com.pocketshell.core.ssh.ExecResult(stdout = stdout, stderr = "", exitCode = 0)
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

        var tailCalls: Int = 0
            private set

        val tailFromLineCalls = mutableListOf<Pair<String, Long>>()

        override fun tail(path: String, onLine: (String) -> Unit): kotlinx.coroutines.Job {
            tailCalls += 1
            return tailJob
        }

        override fun tail(
            path: String,
            fromLineExclusive: Long,
            onLine: (String) -> Unit,
        ): kotlinx.coroutines.Job {
            tailFromLineCalls += path to fromLineExclusive
            tailCalls += 1
            return tailJob
        }

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): com.pocketshell.core.ssh.SshPortForward {
            throw NotImplementedError()
        }

        override fun startShell(): com.pocketshell.core.ssh.SshShell {
            throw NotImplementedError()
        }

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() {
            closed = true
        }
    }
}
