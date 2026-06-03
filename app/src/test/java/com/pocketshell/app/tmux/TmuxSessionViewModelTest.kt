package com.pocketshell.app.tmux

import android.os.Looper
import android.os.SystemClock
import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.session.OPTIMISTIC_USER_MESSAGE_ID_PREFIX
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.protocol.ControlEvent
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
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
import java.io.File
import java.io.IOException

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
    ): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = registry,
        runtimeCache = runtimeCache,
        sshLeaseManager = sshLeaseManager,
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
            "expected Reconnecting after EOF disconnect, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertEquals(
            "Disconnected from alex@alpha.example:22. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Reconnecting).reason,
        )
    }

    @Test
    fun eofDisconnectAutoReconnectsTmuxSession() = runTest {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
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
        advanceUntilIdle()

        assertEquals(1, connector.connectCount)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals("work", vm.activeSessionNameForTest())
    }

    @Test
    fun eofDisconnectAutoReconnectStopsAfterBoundedFailures() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
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
            "expected bounded auto reconnect failure, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
            status.message.contains("Auto reconnect failed after 2 attempts."),
        )
        assertEquals("work", vm.connectingSessionNameForTest())
        assertTrue("manual reconnect must remain available after bounded auto failure", vm.canReconnect.value)
    }

    @Test
    fun eofDisconnectAutoReconnectAbortsImmediatelyOnNonRetryableAuthFailure() = runTest {
        // Issue #440: a non-retryable failure (auth rejection) must NOT burn
        // the whole backoff schedule — the first failed reconnect attempt
        // should fall straight back to the manual Reconnect affordance.
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
        advanceUntilIdle()

        assertEquals(
            "non-retryable auth failure must stop after a single reconnect attempt",
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
            message.contains("authentication failed"),
        )
        assertFalse(
            "non-retryable abort must not report exhausting all attempts, was: $message",
            message.contains("Auto reconnect failed after"),
        )
        assertTrue("manual reconnect must remain available after non-retryable abort", vm.canReconnect.value)
    }

    @Test
    fun eofDisconnectAutoReconnectRetriesTransientFailures() = runTest {
        // Issue #440: a transient transport failure (e.g. connection refused
        // while the host reboots) is retryable, so the backoff loop keeps
        // trying. A connection that fails twice then succeeds must recover
        // without manual input.
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
        advanceUntilIdle()

        assertEquals(
            "transient failure must be retried until the connect succeeds",
            2,
            connector.connectCount,
        )
        assertTrue(
            "expected Connected after a retried transient failure, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
    }

    @Test
    fun tmuxAutoReconnectDelayIsCancelledWhenAppBackgrounds() = runTest {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
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
        )

        deadClient.disconnectedSignal.value = true
        runCurrent()
        assertTrue(
            "disconnect must enter retry delay before background",
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
    fun onKeyBarKeySendsCtrlCAndCtrlDAsRawControlBytes() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.onKeyBarKey("%0", "Ctrl-C")
        vm.onKeyBarKey("%0", "Ctrl-D")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -H -t %0 03",
                "send-keys -H -t %0 04",
            ),
            sent,
        )
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
    fun selectingConversationTabForUnknownOrPlainPaneIsNoOp() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        val before = vm.agentConversations.value

        vm.selectSessionTab("%missing", SessionTab.Conversation)

        assertEquals(before, vm.agentConversations.value)
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
        assertTrue(
            "failed paste must not append an optimistic user message",
            vm.agentConversations.value["%0"]!!.events.isEmpty(),
        )
    }

    @Test
    fun sendToAgentPaneResultPasteChunkTmuxErrorDoesNotAppendOptimisticMessage() = runTest {
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
            "failed paste chunk must not append an optimistic user message",
            vm.agentConversations.value["%0"]!!.events.isEmpty(),
        )
        assertTrue(
            "failed paste must stop before submitting Enter: ${client.sentCommands}",
            client.sentCommands.none { it == "send-keys -t %0 Enter" },
        )
    }

    @Test
    fun sendToAgentPaneResultFinalEnterTmuxErrorDoesNotAppendOptimisticMessage() = runTest {
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
        assertTrue(
            "failed final Enter must not append an optimistic user message",
            vm.agentConversations.value["%0"]!!.events.isEmpty(),
        )
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
    fun sendToAgentPaneResultFailsWithoutOptimisticMessageWhenDisconnected() = runTest {
        val vm = newVm()
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val result = vm.sendToAgentPaneResult("%0", "preserve this prompt")
        runCurrent()

        assertTrue("disconnected tmux agent send must report failure", result.isFailure)
        assertTrue(
            "disconnected send must not append optimistic messages",
            vm.agentConversations.value["%0"]!!.events.isEmpty(),
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

        val pane = vm.panes.value.single()
        assertEquals("/dev/pts/3", pane.paneTty)
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
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
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

        override suspend fun connect(target: SshLeaseTarget): Result<com.pocketshell.core.ssh.SshSession> {
            val index = connectCount
            connectCount += 1
            return failures.getOrNull(index)?.let { Result.failure(it) }
                ?: Result.success(session)
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

        override val isConnected: Boolean
            get() = isConnectedValue && !closed

        override suspend fun exec(command: String): com.pocketshell.core.ssh.ExecResult {
            execGate?.await()
            val stdout = when {
                command.contains("ss -tlnp") ->
                    ssListeningPorts.joinToString("\n") { "0.0.0.0:$it users:((\"server\",pid=1,fd=3))" }
                command.contains("netstat -tlnp") || command.contains("ss -tln") -> ""
                command.contains("wc -l < ") -> wcOutput
                command.startsWith("tail -n ") -> initialEventsOutput
                command.contains("ps -eo pid,ppid,tty,comm,args") -> processOutput
                command.contains(".claude") ||
                    command.contains(".codex") ||
                    command.contains("opencode") -> detectionOutput
                else -> ""
            }
            return com.pocketshell.core.ssh.ExecResult(stdout = stdout, stderr = "", exitCode = 0)
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
