package com.pocketshell.app.tmux

import android.os.Looper
import com.pocketshell.core.terminal.bridge.SshTerminalBridge
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionViewModelAttachReadinessTest : TmuxSessionViewModelTestBase() {
    @Test
    fun commandTimeoutDuringReconcileSurfacesFailedStatus() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "list-panes"
            closeAndThrowException = TmuxClientException(
                "tmux command `list-panes` timed out after 100ms",
            )
            closeAndThrowDisconnectEvent = TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.CommandTimeout,
                source = "command_timeout",
                intent = "command_timeout",
                commandKind = "list-panes",
                timeoutMode = "fatal",
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
            "Tmux command timed out from test@test:0. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
    }

    @Test
    fun attachReadinessTimeoutFailsWithRetryableMessageAndClosesClient() = runTest(scheduler) {
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
    fun attachReadinessRetriesEmptyPaneListUntilPanesArrive() = runTest(scheduler) {
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
    fun applyParsedPanesPopulatesStateFlowWithOnePerPane() = runTest(scheduler) {
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
    fun applyParsedPanesSortsByWindowThenPaneIndex() = runTest(scheduler) {
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
    fun reusesTerminalStateAcrossReconcilesForSamePaneId() = runTest(scheduler) {
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

    /**
     * Issue #959 — DURABLE JVM GUARD for the beyond-grace
     * background→foreground "terminal frozen (no I/O) but app responsive"
     * symptom, at the precise re-bind site ([applyParsedPanes]'s reuse branch).
     *
     * A ~2-minute background fires the grace-elapsed teardown; the foreground
     * `connect(TmuxConnectTrigger.LifecycleReattach)` forces a FRESH SSH lease +
     * a brand-new [TmuxClient]. tmux preserves pane ids across detach/reattach,
     * so the reconcile takes the REUSE branch: it keeps the existing
     * [TerminalSurfaceState] (scrollback survives) and — before this fix —
     * copied metadata ONLY, leaving the pane's output producer (subscribed to
     * the DEAD client's `outputFor(paneId)`) and its input drain still wired to
     * the dead client. Result: the stale buffer stays painted, NEW `%output`
     * never reaches the emulator, and key bar / IME bytes route nowhere — the
     * exact "content visible but frozen" report. The post-grace `connect`
     * reattach had no `rebindVisiblePaneProducersToClient` call (only the silent
     * passive-reattach paths did).
     *
     * This guard reproduces the client swap directly: connect client A, seed +
     * prove I/O is live on A, swap [clientRef] to a fresh client B, then run the
     * reconcile that REUSES pane `%0`. The load-bearing assertions are
     * BEHAVIORAL, on BOTH directions:
     *  - OUTPUT: a `%output` emitted on client B reaches the pane's emulator
     *    (proves the producer re-subscribed to B's `outputFor`).
     *  - INPUT: a key written to the pane's input sink lands as a `send-keys` on
     *    client B (proves the input drain re-bound to B).
     *
     * RED on base: output emitted on B never renders (producer still on A) and
     * the test fails on the OUTPUT assertion. GREEN with the reuse-branch
     * re-bind. Covers the class — output AND input — for the reused-pane swap.
     */
    @Test
    fun reusedPaneRebindsProducerAndInputToNewClientAfterClientSwap() = runTest(scheduler) {
        val vm = newVm()
        // Pin the producer's collect dispatcher to the shared virtual clock so the
        // %output -> emulator feed drains deterministically under advanceUntilIdle
        // (production default is a real Dispatchers.IO thread). Same harness the
        // #576 overflow regression uses.
        vm.setTerminalSurfaceStateFactoryForTest {
            TerminalSurfaceState(StandardTestDispatcher(scheduler))
        }
        // decoupleOutputForFromEvents: outputFor() reads emittedPaneOutputs, so a
        // test can emit pane %output directly and watch its producer subscription.
        val clientA = FakeTmuxClient().apply { decoupleOutputForFromEvents = true }
        vm.attachClientForTest(clientA)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        runCurrent()
        // Wait until client A's pane-output collectors (producer + activity)
        // are subscribed before emitting. The port detector is now
        // target/generation-owned and intentionally does not start from this
        // attachClientForTest-only harness because it has no active target.
        assertTrue(
            "client A pane-output collectors must subscribe",
            withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
                clientA.emittedPaneOutputs.subscriptionCount.first { it >= 2 }
                true
            } ?: false,
        )

        // Open the seed gate so live %output flushes to the emulator, then prove
        // I/O is wired to client A: an %output emitted on A renders.
        val state = vm.panes.value.single().terminalState
        state.appendRemoteOutput("SEED-A\r\n".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        clientA.emittedPaneOutputs.emit(
            ControlEvent.Output("%0", "OUTPUT-ON-A\r\n".toByteArray(Charsets.US_ASCII)),
        )
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "precondition: A's %output reaches the emulator transcript",
            renderedTranscriptFrom(state).contains("OUTPUT-ON-A"),
        )

        // The beyond-grace reattach: a fresh client B becomes the live control
        // client (mirrors `connect(LifecycleReattach)` swapping clientRef to the
        // fresh-lease client), then tmux re-lists the SAME pane id %0.
        val clientB = FakeTmuxClient().apply { decoupleOutputForFromEvents = true }
        vm.attachClientForTest(clientB)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        runCurrent()
        // After the fix, the reused pane's producer + activity + port detector
        // re-subscribe to client B. On base they stay on the dead client A, so
        // this wait TIMES OUT — making the bug observable as a re-bind failure.
        val reboundToB = withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
            clientB.emittedPaneOutputs.subscriptionCount.first { it >= 1 }
            true
        } ?: false
        assertTrue(
            "REGRESSION (#959): after a beyond-grace reattach the reused pane's " +
                "output producer must re-subscribe to the NEW client. On base it " +
                "stayed bound to the dead client -> no collector on B.",
            reboundToB,
        )

        // The reused pane must keep its TerminalSurfaceState (scrollback survives)
        // — the whole reason the reuse branch exists.
        assertSame(
            "reused pane must keep its emulator/scrollback across the reattach",
            state,
            vm.panes.value.single().terminalState,
        )

        // The rebind re-arms the producer's seed gate (awaitSeed=true closes it
        // until the reattach re-seed lands — in production that is
        // reseedActivePaneForReattach). Open it so live %output from B flushes,
        // exactly as the production reattach re-seed does.
        state.appendRemoteOutput("SEED-B\r\n".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()

        // LOAD-BEARING (output): a %output emitted on the NEW client B must now
        // reach the emulator. RED on base — the producer was still subscribed to
        // the dead client A, so B's output was dropped (the frozen terminal).
        clientB.emittedPaneOutputs.emit(
            ControlEvent.Output("%0", "OUTPUT-ON-B\r\n".toByteArray(Charsets.US_ASCII)),
        )
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "REGRESSION (#959): fresh %output on the NEW client must render after " +
                "the reattach. On base it stayed bound to the dead client -> frozen.",
            renderedTranscriptFrom(state).contains("OUTPUT-ON-B"),
        )

        // LOAD-BEARING (input): a key written to the pane's input sink must drain
        // to the NEW client B (a send-keys on B), NOT the dead client A.
        val keysOnBBefore = clientB.sentCommands.count { it.startsWith("send-keys") }
        vm.tmuxInputSinkForTest("%0").write("x".toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        assertTrue(
            "REGRESSION (#959): input after a reattach must reach the NEW client " +
                "(a send-keys on B), not the dead client.",
            clientB.sentCommands.count { it.startsWith("send-keys") } > keysOnBBefore,
        )
        assertTrue(
            "the input key must carry the typed byte to client B",
            clientB.sentCommands.any { it.startsWith("send-keys") && it.contains("'x'") },
        )
    }

    @Test
    fun newPaneInReconcileGetsDistinctTerminalState() = runTest(scheduler) {
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
    fun closedPaneIsDroppedFromState() = runTest(scheduler) {
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
    fun windowAddEventTriggersListPanesAndPopulatesState() = runTest(scheduler) {
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
    fun newPaneReconcileCapturesExistingVisibleContent() = runTest(scheduler) {
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
            client.sentCommands.contains(seedCaptureCommand("%0")),
        )
    }

    @Test
    fun newPaneReconcileSeedsCaptureWithRestoredCursor() = runTest(scheduler) {
        // Issue #259/#640: the seed pairs the capture with the pane's true
        // cursor (so the agent's next in-place spinner rewrite lands on the
        // right row) via [TmuxClient.captureWithCursor]. The capture command
        // must run, and the cursor query must follow the capture so the builder
        // can append the restore. (The single-flight FOLD that avoids a second
        // wire round-trip is verified in the core-tmux RealTmuxClient tests; the
        // [FakeTmuxClient] uses the two-command default, which exercises the
        // same observable command sequence here.)
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
            "expected a capture-pane seed for the new pane, got ${client.sentCommands}",
            client.sentCommands.contains(seedCaptureCommand("%0")),
        )
        assertTrue(
            "expected a cursor-position query paired with the capture, " +
                "got ${client.sentCommands}",
            client.sentCommands.contains(seedCursorCommand("%0")),
        )
        // The cursor query must come AFTER the capture for the same pane so the
        // builder can append the restore to the replayed snapshot.
        val captureIdx = client.sentCommands.indexOf(seedCaptureCommand("%0"))
        val cursorIdx = client.sentCommands.indexOf(seedCursorCommand("%0"))
        assertTrue("capture must precede cursor query", captureIdx in 0 until cursorIdx)
    }

    @Test
    fun bestEffortCaptureFailureDuringAttachKeepsConnectionConnectedAndClientOpen() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.failBestEffortOnCommandPrefix = "capture-pane"
        client.bestEffortException = TmuxClientException("tmux command `capture-pane` timed out")

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
        advanceUntilIdle()

        assertTrue(
            "best-effort capture timeout must not surface as Failed/Reconnecting; " +
                "status=${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse("best-effort capture timeout must not close tmux client", client.closed)
        assertTrue(
            "expected capture-pane seed attempt, got ${client.sentCommands}",
            client.sentCommands.contains(seedCaptureCommand("%0")),
        )
    }

    @Test
    fun missingCursorReplyDuringSeedDegradesToSeedWithoutRestore() = runTest(scheduler) {
        // Issue #259/#640: the seed pairs the capture with a cursor query via
        // [TmuxClient.captureWithCursor]. When tmux returns no usable cursor
        // (older tmux / dropped reply) the seed must still apply (no explicit
        // cursor restore) and the attach must reach Connected — the
        // graceful-degradation contract the cursor restore depends on.
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
                output = listOf("visible"),
                isError = false,
            ),
        )
        // Cursor query returns an error so captureWithCursor yields a null
        // cursor reply, modelling tmux that did not emit a usable cursor.
        client.cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = emptyList(), isError = true),
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
        advanceUntilIdle()

        assertTrue(
            "missing cursor reply must not surface as Failed/Reconnecting; " +
                "status=${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse("missing cursor reply must not close tmux client", client.closed)
        assertTrue(
            "expected the capture seed command, got ${client.sentCommands}",
            client.sentCommands.contains(seedCaptureCommand("%0")),
        )
    }

    @Test
    fun attachReadinessRecordsTmuxLatencyMetrics() = runTest(scheduler) {
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
        // Issue #1224: the first-visible-output milestone is fed by the per-pane
        // outputFor tap (recordVisiblePaneOutput), NOT by %output on the
        // structural events bus. The fake's outputFor reads emittedEvents, so
        // emitting a pane %output here drives that tap.
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
    fun coldOpenRevealsConnectedOnlyAfterTheVisiblePaneIsSeeded() = runTest(scheduler) {
        // Issue #640 (seed-before-reveal): the terminal surface must NOT flip
        // to the revealed Connected state until the initial capture-pane seed
        // for the visible pane has been APPLIED. Otherwise the user watches the
        // live agent spinner paint onto a black/partial grid until the seed
        // lands (the reported fragments-then-fill). We snapshot the sent
        // commands at the exact moment the status first becomes Connected and
        // assert the seed capture is already present.
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
                output = listOf("issue640-seed-line-001", "issue640-seed-line-002"),
                isError = false,
            ),
        )
        client.cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,1"), isError = false),
        )

        // Capture the connection status synchronously at the exact moment the
        // seed capture command reaches the wire. If the surface were revealed
        // before seeding, the status would already be Connected here.
        var statusWhenSeedSent: TmuxSessionViewModel.ConnectionStatus? = null
        client.onCommandSent = { cmd ->
            if (cmd == seedCaptureCommand("%0") && statusWhenSeedSent == null) {
                statusWhenSeedSent = vm.connectionStatus.value
            }
        }

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
        advanceUntilIdle()

        assertTrue(
            "status must reach Connected after seeding; got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        val seedStatus = statusWhenSeedSent
        assertNotNull("the visible pane was never seeded", seedStatus)
        assertFalse(
            "Connected was revealed BEFORE the visible pane was seeded — the user " +
                "would see a blank/partial grid with only the live spinner painting. " +
                "Status when seed sent: $seedStatus",
            seedStatus is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    @Test
    fun coldOpenSeedsEachVisiblePaneExactlyOnceWithNoRedundantReseed() = runTest(scheduler) {
        // Issue #640: on a cold open every visible pane is freshly created and
        // seeded by the preload pass; the post-attach reseed must NOT re-capture
        // those same panes (the redundant second full reseed the diagnosis
        // flagged). Assert each visible pane's capture seed runs exactly once.
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "%0\t@0\t\$0\twork\tshell\t0",
                    "%1\t@1\t\$0\twork\teditor\t0",
                ),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("pane0-content"), isError = false),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(number = 3L, output = listOf("pane1-content"), isError = false),
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
        advanceUntilIdle()

        assertEquals(
            "each visible pane must be seeded exactly once on cold open " +
                "(no redundant second reseed); got ${client.sentCommands}",
            1,
            client.sentCommands.count { it == seedCaptureCommand("%0") },
        )
        assertEquals(
            "each visible pane must be seeded exactly once on cold open " +
                "(no redundant second reseed); got ${client.sentCommands}",
            1,
            client.sentCommands.count { it == seedCaptureCommand("%1") },
        )
    }

    @Test
    fun warmSwitchReadinessTelemetryFollowsAttachOrdering() = runTest(scheduler) {
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

    /**
     * Issue #1169 (Codex/agent pane cut — tmux window resized too small and not
     * restored). Reproduce the maintainer's mechanism at the size-derivation
     * layer: PocketShell measures the live viewport once (full grid), then a warm
     * reattach / within-grace foreground return / session switch REPLAYS a cached
     * (shrunk) grid through the same `maybeRefreshControlClientSize` path. On base
     * that replays the SMALL cached size — `refresh-client -C 80x12` — which
     * shrinks the shared tmux window so the alt-screen agent TUI draws cut with
     * black below (and, via `window-size latest`, a second client inherits the
     * cut). The fix floor-guards the size to the live measured viewport, so the
     * replay never sends a size smaller than what the emulator will render.
     */
    @Test
    fun cachedSizeReplayNeverShrinksTmuxBelowLiveViewport() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\tcodex\tshell\t0"),
                isError = false,
            ),
        )
        client.responses.addLast(
            CommandResponse(
                number = 4L,
                output = listOf("%0\t@0\t\$0\tcodex\tshell\t0"),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("codex seed"), isError = false),
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
            sessionName = "codex",
            client = client,
            trigger = TmuxConnectTrigger.FastSwitch,
        )

        // (1) Live viewport measured once at the FULL Pixel-7-ish grid.
        val fullCols = 85
        val fullRows = 40
        vm.resizeRemotePty(columns = fullCols, rows = fullRows)
        advanceUntilIdle()
        assertTrue(
            "the initial full-viewport measure must reach tmux; sent=${client.sentCommands}",
            client.sentCommands.contains("refresh-client -C ${fullCols}x${fullRows}"),
        )

        // (2) A warm reattach / within-grace foreground return / switch REPLAYS a
        //     cached, shrunk grid (what a transient keyboard/composer/switch parked
        //     in the runtime cache). SOFT_INPUT_ADJUST_NOTHING means Compose does
        //     NOT re-measure on return, so this replay is the only size assertion.
        val shrunkCols = 80
        val shrunkRows = 12
        vm.replayCachedControlClientSizeForTest(cachedColumns = shrunkCols, cachedRows = shrunkRows)
        advanceUntilIdle()

        // (3) The load-bearing assertion: the shrunk size must NEVER have been sent
        //     to tmux. On base this fails (the replay sends `refresh-client -C
        //     80x12`); with the floor guard the replay re-derives the full live
        //     viewport instead.
        assertFalse(
            "cached replay must not shrink the tmux window below the live viewport; " +
                "sent=${client.sentCommands}",
            client.sentCommands.contains("refresh-client -C ${shrunkCols}x${shrunkRows}"),
        )
        // The live viewport floor is preserved across the cached replay.
        assertEquals(
            "live measured viewport must remain the full grid",
            fullCols to fullRows,
            vm.liveMeasuredDimensionsForTest(),
        )
        // Every refresh-client the VM emitted is at least as tall/wide as the live
        // viewport — the window is never left cut.
        val refreshDims = client.sentCommands
            .filter { it.startsWith("refresh-client -C ") }
            .map { it.removePrefix("refresh-client -C ") }
            .mapNotNull { spec ->
                val parts = spec.split('x')
                val c = parts.getOrNull(0)?.toIntOrNull()
                val r = parts.getOrNull(1)?.toIntOrNull()
                if (c != null && r != null) c to r else null
            }
        assertTrue(
            "expected at least one refresh-client size; sent=${client.sentCommands}",
            refreshDims.isNotEmpty(),
        )
        assertTrue(
            "no refresh-client may drop the window below the live viewport; dims=$refreshDims",
            refreshDims.all { (c, r) -> c >= fullCols && r >= fullRows },
        )
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
    fun existingPaneReconcileDoesNotRecaptureContent() = runTest(scheduler) {
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
        // Issue #693/#662: the attach-time seed must succeed in ONE shot
        // (non-empty capture) so the pane is marked seeded-this-attach. An empty
        // attach seed now legitimately RETRIES (the never-black guard), which is
        // a different code path than the "no RE-capture of EXISTING content on a
        // reconcile" invariant this test locks in. Queue real content so the
        // single attach seed lands and the LayoutChange reconcile below stays a
        // no-op (the #640 panesSeededThisAttach skip).
        client.capturePaneResponses.addLast(
            CommandResponse(number = 3L, output = listOf("work shell ready"), isError = false),
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

    private fun renderedTranscriptFrom(state: TerminalSurfaceState): String {
        val bridgeField = TerminalSurfaceState::class.java.getDeclaredField("bridge").apply {
            isAccessible = true
        }
        val bridge = bridgeField.get(state) as? SshTerminalBridge ?: return ""
        return bridge.emulator.screen.transcriptText
    }

    private companion object {
        const val SLOW_FEED_DRAIN_TIMEOUT_MS = 30_000L
    }
}
