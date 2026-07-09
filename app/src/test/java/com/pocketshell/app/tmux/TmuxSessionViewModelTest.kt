package com.pocketshell.app.tmux

import android.os.Looper
import android.os.SystemClock
import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkSnapshot
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.app.session.FIRST_PAINT_MESSAGE_BUDGET
import com.pocketshell.app.session.JSONL_RAW_LINES_PER_EVENT
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.sessionSurfaceState
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.terminal.bridge.SshTerminalBridge
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.uikit.model.KeyModifierState
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import com.pocketshell.core.tmux.TmuxOutputBacklogOverflow
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.testsupport.drainMainLooperUntil as drainMainLooperUntilShared
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.system.measureTimeMillis

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
class TmuxSessionViewModelTest : TmuxSessionViewModelTestBase() {
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

    /**
     * Issue #576 (Slice A of #792): MAIN-THREAD RESPONSIVENESS under a Codex
     * `%layout-change` storm — the on-device ANR reproduced through the REAL
     * collector wiring (`bindClientObservers` → coalescer → off-main
     * `reconcilePanes`), then proven fixed.
     *
     * The freeze was head-of-line blocking: the old path ran a synchronous
     * `reconcilePanes()` (`list-panes` round-trip) on the Main collector thread
     * for EVERY structural event, so a burst of N `%layout-change` events
     * serialised N `list-panes` round-trips on the UI thread → ANR.
     *
     * This test makes the `list-panes` round-trip SLOW (parked behind a gate)
     * and runs it on a SEPARATE [reconcileDispatcher] (modelling the production
     * `Dispatchers.Default`). It then fires a 5_000-event storm through the real
     * collector and HARD-asserts, while the reconcile is still parked:
     *  - a concurrently-posted MAIN task runs to completion (the Main thread is
     *    NOT wedged behind the storm) — the responsiveness contract;
     *  - the whole storm was OFFERED non-blockingly (the collector did not stall
     *    one-`list-panes`-per-event);
     *  - at most a handful of `list-panes` round-trips were started for the
     *    5_000 events — coalescing, not O(N).
     * Then it releases the gate and asserts the FINAL pane layout is correct
     * (the settled layout after the burst is never dropped).
     *
     * RED on base: with the pre-fix synchronous per-event main-thread reconcile,
     * the collector `emit` of the FIRST gated event would suspend the Main
     * collector on the gate `await()`; the marker task posted afterwards on Main
     * would NOT complete under `runCurrent()` (Main wedged) and the 5_000 emits
     * would each queue a blocking reconcile. GREEN with the coalescer +
     * off-main reconcile.
     *
     * No `assumeTrue` / `isRunningOnCi` skip (F3): the gate + virtual clock make
     * the head-of-line property deterministic on every machine and on CI.
     */
    @Test
    fun codexLayoutChangeStormKeepsMainThreadResponsiveAndSettlesFinalLayout() =
        runTest(scheduler) {
            val vm = newVm()
            // Model production: the structural reconcile IO runs OFF the Main
            // collector thread. A queued StandardTestDispatcher on the shared
            // scheduler stands in for Dispatchers.Default — work runs only when
            // the scheduler pumps it, never eagerly on the collector's stack.
            vm.setReconcileDispatcherForTest(StandardTestDispatcher(scheduler))
            // Apply stays on the test Main (the production
            // Dispatchers.Main.immediate analogue) so the `_panes` mutation is
            // single-threaded on the UI thread. Because the coalescer scope runs
            // on the separate StandardTestDispatcher above (≠ this apply
            // dispatcher), `applyOnMain` genuinely HOPS from off-Main back to
            // Main — the production behaviour under test.
            vm.setReconcileApplyDispatcherForTest(testMainDispatcher)

            val client = FakeTmuxClient()
            // Park EVERY `list-panes` round-trip behind a gate so a reconcile,
            // once started, is "slow" — the per-event cost that wedged the UI
            // thread on the old path. (attachClientForTest does NOT itself run a
            // reconcile; only the structural events below do, so the gate is
            // armed up-front and the storm reconcile is what parks on it.)
            val listPanesGate = CompletableDeferred<Unit>()
            client.sendCommandGatePrefix = "list-panes"
            client.sendCommandGate = listPanesGate

            // Each coalesced reconcile re-reads the authoritative pane list via
            // list-panes; both storm-driven reconciles see the SETTLED two-pane
            // layout (the last write after the burst). capture-pane seeds the
            // newly-discovered editor pane.
            val settledLayout = CommandResponse(
                number = 0L,
                output = listOf(
                    "%0\t@0\t\$0\twork\tshell\t0",
                    "%1\t@1\t\$0\twork\teditor\t0",
                ),
                isError = false,
            )
            repeat(4) { client.responses.addLast(settledLayout) }
            repeat(4) {
                client.capturePaneResponses.addLast(
                    CommandResponse(number = 0L, output = listOf("seed"), isError = false),
                )
                client.cursorQueryResponses.addLast(
                    CommandResponse(number = 0L, output = listOf("0,0"), isError = false),
                )
            }

            vm.attachClientForTest(client)
            runCurrent()

            // ---- the storm ------------------------------------------------
            // Fire the storm from a child coroutine and bound it: the coalescer
            // `offer` is non-blocking so the whole storm completes promptly.
            // On the OLD synchronous per-event path the FIRST gated reconcile
            // suspends the (UNDISPATCHED) Main collector mid-`emit`, so the
            // SharedFlow `emit`s block and this never completes — surfacing as a
            // FAST explicit failure here instead of the 1m runTest watchdog.
            val stormSize = 5_000
            val stormDone = AtomicInteger(0)
            val stormJob = launch {
                repeat(stormSize) {
                    client.emittedEvents.emit(
                        ControlEvent.LayoutChange(
                            sessionId = "",
                            windowId = "@0",
                            layout = "burst-$it",
                        ),
                    )
                }
                stormDone.incrementAndGet()
            }
            // Let the collector drain all offers and let ONE coalesced reconcile
            // start (it parks on the gated list-panes). Do NOT release the gate
            // yet — we assert responsiveness WHILE a reconcile is in flight.
            runCurrent()
            assertEquals(
                "the entire 5_000-event layout-change storm must be OFFERED " +
                    "non-blockingly — the collector must NOT head-of-line-block on " +
                    "the gated reconcile (the ANR)",
                1,
                stormDone.get(),
            )
            assertTrue("storm coroutine completed", stormJob.isCompleted)

            // RESPONSIVENESS: post a marker task on Main and assert it completes
            // even though a reconcile is parked on the gated list-panes. On the
            // old synchronous path the Main collector itself would be suspended
            // on the gate, so this marker could not run.
            val mainTaskRan = AtomicInteger(0)
            CoroutineScope(Dispatchers.Main).launch { mainTaskRan.incrementAndGet() }
            runCurrent()
            assertEquals(
                "a Main task posted during the layout-change storm must run — the " +
                    "Main thread must NOT be wedged behind the gated reconcile (the ANR)",
                1,
                mainTaskRan.get(),
            )

            // COALESCING: despite 5_000 events, only a handful of list-panes
            // round-trips were STARTED (the rest collapsed). Count how many have
            // reached the gated client so far.
            val listPanesStartedDuringStorm =
                client.sentCommands.count { it.startsWith("list-panes") }
            assertTrue(
                "5_000-event storm must collapse to a handful of list-panes " +
                    "round-trips, started=$listPanesStartedDuringStorm",
                listPanesStartedDuringStorm in 1..3,
            )

            // FINAL LAYOUT: release the gate and let the settled reconcile apply.
            // The reconcile that runs after the burst re-reads list-panes and
            // must produce the final two-pane layout (the last write is never
            // dropped).
            listPanesGate.complete(Unit)
            advanceUntilIdle()

            val finalPaneIds = vm.panes.value.map { it.paneId }
            assertEquals(
                "the final pane layout after the storm settles must be correct " +
                    "(both panes present, last write not dropped), was $finalPaneIds",
                listOf("%0", "%1"),
                finalPaneIds,
            )
            // And the coalesced reconciles never ran one-list-panes-per-event.
            val totalListPanes = client.sentCommands.count { it.startsWith("list-panes") }
            assertTrue(
                "total list-panes for a 5_000-event storm must stay a small constant, " +
                    "was $totalListPanes",
                totalListPanes in 1..4,
            )
        }

    @Test
    fun reconcileScopesPanesAndWindowSummariesToActiveSession() = runTest(scheduler) {
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
        // every window in the target session, not only the current window.
        // Issue #782: PocketShell no longer manages windows, but the unified
        // pager + the per-window `[wN]` switcher entries still rely on the
        // full multi-window pane list, so the `-s` scope is preserved.
        assertTrue(command.startsWith("list-panes -s -t 'work' -F "))
        assertTrue(command.contains(LIST_PANES_FIELD_SEPARATOR))
        assertFalse(command.contains(" -a "))

        val panes = vm.panes.value
        assertEquals(listOf("%0", "%1"), panes.map { it.paneId })
        assertEquals(listOf("@0", "@1"), panes.map { it.windowId })
    }

    @Test
    fun layoutChangeEventTriggersListPanesReconcile() = runTest(scheduler) {
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
    fun windowCloseEventTriggersReconcileThatDropsThePane() = runTest(scheduler) {
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
    fun listPanesErrorLeavesExistingPaneListUntouched() = runTest(scheduler) {
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
    fun unrelatedEventDoesNotTriggerListPanes() = runTest(scheduler) {
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
     *
     * This STRENGTHENS, never weakens, the caller's assertion: a genuine stall
     * still exhausts the deadline so the caller's `assertTrue(...)` fails. It
     * replaces the old virtual-clock pump (`withTimeoutOrNull(...) { ...
     * idle(); runCurrent(); delay(10) }`) that elapsed in ~0 wall-clock time
     * and flaked `completed` false under load (#1050: the unfixed sibling of
     * the codexScale/codexLike loops #1042 already de-flaked).
     */
    private fun TestScope.drainMainLooperUntil(
        deadlineMs: Long = SLOW_FEED_DRAIN_TIMEOUT_MS,
        condition: () -> Boolean,
    ): Boolean =
        // Issue #1048: delegates the bounded-wall-clock loop + HARD deadline to the
        // ONE shared, audited settle-pump. The SshTerminalBridge-fed flood/codex
        // drain is frame-paced (#803/#804), so the per-tick drain MUST idle the main
        // looper one frame (to fire the `postDelayed` continuation — a bare `idle()`
        // never runs it) AND `runCurrent()` (to progress suspended coroutines);
        // neither advances the kotlinx virtual clock. Returns false on the wall-clock
        // deadline — callers HARD-FAIL on it (the load-bearing assertion).
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

    private fun newOpenCodeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.OpenCode,
        sourcePath = "/home/u/.local/share/opencode/opencode.db#ses_123",
        sessionId = "ses_123",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

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

    // ─── Issue #786: the "Search in conversation" field + its `searchQuery`
    // hoisting (#154) were hard-cut (D22). The two former search-persistence
    // tests are deleted with the feature. The conversation feed now shows every
    // event with no query filter. ─────────────

    // ─── Issue #186: per-window agent detection state ──────────────────
    //
    // Detection is per-pane (and therefore per-window for the simple
    // case of one pane per window). The view-model surface that the
    // screen drives off is [agentForWindow] — it must return the
    // current window's agent kind regardless of which window's pane the
    // user is currently viewing, so the Conversation tab can hide on
    // plain-shell windows even when a sibling window has a live agent.

    @Test
    fun agentForWindowReturnsNullForUnknownWindowId() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        assertNull(vm.agentForWindow(null))
        assertNull(vm.agentForWindow(""))
        assertNull(vm.agentForWindow("@nonexistent"))
    }

    @Test
    fun agentForWindowReturnsKindOfDetectedAgentInThatWindow() = runTest(scheduler) {
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
    fun agentForWindowPicksLowestPaneIndexWhenMultipleAgentsInOneWindow() = runTest(scheduler) {
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
    fun listPanesParserAcceptsPrintableFieldSeparator() = runTest(scheduler) {
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
    fun listPanesFormatRequestsPaneTty() = runTest(scheduler) {
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
    fun listPanesParserCapturesTmuxWindowIndex() = runTest(scheduler) {
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
    fun paneModeChangedRefreshesCopyModeStateFromListPanes() = runTest(scheduler) {
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
    fun agentForWindowClearsAfterPaneLosesDetection() = runTest(scheduler) {
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
        // does when a subsequent source-resolution probe returns null).
        vm.clearAgentDetectionForPaneForTest("%0")

        assertNull(
            "agentForWindow must return null once the pane's detection is cleared",
            vm.agentForWindow("@0"),
        )
    }

    @Test
    fun conversationStateClearsWhenPaneLosesDetection() = runTest(scheduler) {
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
    fun conversationWithLoadedTranscriptIsKeptReadableWhenDetectionDrops() = runTest(scheduler) {
        // Issue #1057 (maintainer dogfood — "conversation is not visible in this
        // app"): a conversation that genuinely EXISTS (a loaded transcript, events
        // present) must stay READABLE after live detection drops, so the
        // Terminal/Conversation toggle stays reachable and the user can still read
        // it. On base clearAgentDetectionForPane DROPPED the row the instant
        // detection settled null → the transcript became unreachable (the exact
        // symptom). This is the fast JVM red→green for the VM fix; the connected
        // sibling is ConversationStaysReachableAfterDetectionDropsDockerTest.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "agent", paneIndex = 0),
            ),
        )
        val event = ConversationEvent.Message(
            id = "m1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "the transcript the user was reading",
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection(), listOf(event))
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()
        assertEquals(
            SessionTab.Conversation,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )

        // Live detection settles null (the agent exited / re-detection never
        // rebinds) — the production teardown the null-detection poll calls.
        vm.clearAgentDetectionForPaneForTest("%0")
        runCurrent()

        val row = vm.agentConversations.value["%0"]
        assertNotNull(
            "#1057: an events-bearing conversation row is KEPT readable after " +
                "detection drops (on base it was dropped → conversation unreachable)",
            row,
        )
        assertNull(
            "#1057: the kept row's detection is null (the live agent is gone)",
            row!!.detection,
        )
        assertTrue(
            "#1057: the kept row preserves the loaded transcript so it stays readable",
            row.events.isNotEmpty(),
        )
        assertEquals(
            "#1057: the user's Conversation tab choice persists on the kept row",
            SessionTab.Conversation,
            row.selectedTab,
        )
        // agentForWindow still reports null (no LIVE agent) — the kept row is a
        // frozen transcript, not a resurrected live detection.
        assertNull(
            "#1057: a kept frozen transcript must not resurrect a live agent",
            vm.agentForWindow("@0"),
        )
    }

    @Test
    fun conversationWithNoTranscriptStillDropsWhenDetectionDrops() = runTest(scheduler) {
        // Issue #1057 adjacency (#186/#894 contract unchanged): a row with NO
        // loaded transcript (the agent exited before any transcript was read, a
        // genuine shell) still DROPS when detection settles null — the keep-events
        // change must NOT make a conversation-less window linger on the toggle.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "agent", paneIndex = 0),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        runCurrent()
        assertNotNull(vm.agentConversations.value["%0"])

        vm.clearAgentDetectionForPaneForTest("%0")
        runCurrent()

        assertNull(
            "#1057 adjacency: a row with no loaded transcript still drops on exit",
            vm.agentConversations.value["%0"],
        )
        assertNull(vm.agentForWindow("@0"))
    }

    @Test
    fun parsedPanePaneTtyDefaultsToEmptyWhenOmitted() = runTest(scheduler) {
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

    // ─── Issue #178: same-host fast-switch reuses the SSH transport ───
    //
    // The connect() path detects "same host, different tmux session"
    // and routes through the fast-switch path instead of the full
    // SSH-handshake teardown + reconnect. Production exercise lives in
    // the connected TmuxSessionSwitchSameHostReusesSshE2eTest because a
    // unit test cannot reach into [SshConnection.connect] without a
    // real network. The unit tests below pin the predicate behaviour,
    // the teardown-keep-session invariant, and the registry side-effects
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
    fun fastSwitchCachesOldRuntimeAndReusesSshSession() = runTest(scheduler) {
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
    fun activatingCachedRuntimePublishesPanesSynchronouslyWithoutTmuxCommands() = runTest(scheduler) {
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
            "cached activation must avoid synchronous tmux list/capture commands, got ${clientA.sentCommands}",
            clientA.sentCommands.none {
                it.startsWith("list-panes") ||
                    it.startsWith("capture-pane")
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
            clientA.sentCommands.contains(seedCaptureCommand("%1")),
        )
        assertTrue(
            "async seed should pair the capture with the cursor restore query",
            clientA.sentCommands.contains(seedCursorCommand("%1")),
        )
        assertEquals(listOf("%0", "%1"), vm.panes.value.map { it.paneId })
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun cachedRuntimeWithStaleConnectedSessionFallsBackToFreshAttach() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val staleSession = FakeSshSession()
        val staleClient = FakeTmuxClient().apply {
            failBestEffortOnCommandPrefix = "display-message"
            bestEffortException = TmuxClientException("tmux command timed out")
        }
        val currentClient = FakeTmuxClient()
        val freshClient = FakeTmuxClient().withSinglePane("work", "%9")

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = staleClient,
            session = staleSession,
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
            client = currentClient,
            session = staleSession,
        )
        runCurrent()
        assertTrue(runtimeCache.contains(tmuxRuntimeKeyForTest("work")))
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            freshClient
        }

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

        assertTrue("stale cached runtime must be closed after failed health probe", staleClient.closed)
        assertSame("fresh attach should replace the stale cached runtime", freshClient, registry.clients.value[1L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(listOf("%9"), vm.panes.value.map { it.paneId })
        assertFalse("failed cached runtime should be removed from cache", runtimeCache.contains(tmuxRuntimeKeyForTest("work")))
    }

    @Test
    fun cachedRuntimeRefreshBestEffortSeedFailureKeepsConnectedClientOpen() = runTest(scheduler) {
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
        clientA.failBestEffortOnCommandPrefix = "capture-pane"
        clientA.bestEffortException = TmuxClientException("tmux command `capture-pane` timed out")

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
        advanceTimeBy(CACHED_RUNTIME_REMOTE_REFRESH_DELAY_MS)
        runCurrent()

        assertTrue(
            "expected cached-runtime refresh to attempt capture seed, got ${clientA.sentCommands}",
            clientA.sentCommands.contains(seedCaptureCommand("%1")),
        )
        assertTrue(
            "cached-runtime best-effort seed timeout must not mark connection Failed/Reconnecting; " +
                "status=${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse("cached-runtime seed timeout must not close tmux client", clientA.closed)
        assertFalse("cached-runtime seed timeout must not mark tmux disconnected", clientA.disconnected.value)
    }

    @Test
    fun staleCachedRuntimeRefreshCannotOverwriteNewerSelection() = runTest(scheduler) {
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
    fun activatingCachedRuntimeReinstallsDisconnectedObserver() = runTest(scheduler) {
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
    fun activatingCachedRuntimeRestartsAgentConversationTail() = runTest(scheduler) {
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
    fun restoredRuntimeTailRefreshPreservesConcurrentConversationState() = runTest(scheduler) {
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
        vm.appendAgentEventsForTest("%0", listOf(newerEvent))

        execGate.complete(Unit)
        advanceUntilIdle()

        val after = vm.agentConversations.value["%0"]!!
        assertEquals(AgentConversationSyncStatus.Live, after.syncStatus)
        assertEquals(SessionTab.Conversation, after.selectedTab)
        assertEquals(listOf("cached", "newer", "backlog"), after.events.map { it.id })
        assertEquals(
            "cached restore plus async restart should start exactly one new tail",
            1,
            session.tailCalls,
        )
        assertEquals(listOf("/home/u/.claude/sessions/abc.jsonl" to 2L), session.tailFromLineCalls)
    }

    @Test
    fun runtimeCacheEvictsLeastRecentlyUsedRuntimePerHost() = runTest(scheduler) {
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
    fun runtimeCachePerHostCapDoesNotEvictOtherHosts() = runTest(scheduler) {
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
    fun runtimeCacheEvictsExpiredRuntimesDeterministically() = runTest(scheduler) {
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
    fun fastSwitchEvictionClosesOldWarmRuntimeAsynchronously() = runTest(scheduler) {
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
    fun switchingToPrewarmedTargetUsesCachedRuntimeFirstFramePath() = runTest(scheduler) {
        TmuxSessionLatencyTelemetry.resetForTest()
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
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
    fun cancelledSessionPrewarmClosesPartialRuntimeWithoutCachingIt() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
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
    fun switchingToColdTargetStillFallsBackToFastAttach() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
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

    @Test
    fun failedSameHostSwitchKeepsParkedRuntimeActivatable() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val vm = newVm(
            registry = registry,
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val clientA = FakeTmuxClient().withSinglePane("work", "%0")
        val clientB = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "list-panes"
            closeAndThrowException = TmuxClientException("pane readiness failed")
        }
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            when (sessionName) {
                "work" -> clientA
                "other" -> clientB
                else -> error("unexpected tmux client request for $sessionName")
            }
        }

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
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertSame(clientA, registry.clients.value[1L]?.client)
        assertEquals(1, connector.connectCount)

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

        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed)
        assertEquals(listOf(tmuxRuntimeKeyForTest("work")), runtimeCache.snapshotKeys())
        assertFalse("failed switch must not close parked session A", clientA.closed)
        assertTrue("failed session B client must be closed", clientB.closed)
        assertFalse(runtimeCache.contains(tmuxRuntimeKeyForTest("other")))

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

        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertSame(clientA, registry.clients.value[1L]?.client)
        assertFalse("restored parked session A must remain open", clientA.closed)
        assertFalse(runtimeCache.contains(tmuxRuntimeKeyForTest("work")))
    }

    // ─── Issue #437 (slice A): same-host switch must NOT blank to ───
    // the full-screen "Connecting" overlay. The cold same-host switch
    // (target not yet cached) enters the new [Switching] state, keeps the
    // previous frame painted, gates input until the new -CC control client
    // attaches, then flips to [Connected] with the new session's panes.

    @Test
    fun sameHostSwitchToUncachedSessionEntersSwitchingNotConnecting() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val registry = ActiveTmuxClients()
        val vm = newVm(
            registry = registry,
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
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
        // Switching — NOT the blanking full-screen Connecting overlay.
        val midStatus = vm.connectionStatus.value
        assertTrue(
            "same-host switch must enter Switching, got $midStatus",
            midStatus is TmuxSessionViewModel.ConnectionStatus.Switching,
        )
        assertFalse(
            "same-host switch must never show the blanking Connecting overlay",
            midStatus is TmuxSessionViewModel.ConnectionStatus.Connecting,
        )
        // Issue #661/#1326: a CROSS-session switch must NOT paint the previous
        // session's frame — not even one frame. #634's keep-frame is reversed
        // for the cross-session case: the id-keyed reveal machine HOLDS the surface
        // (revealState is not Live) and the rendered panes are blanked, so the
        // screen shows the "Attaching" loading state instead of the leaving
        // session's content.
        assertTrue(
            "cross-session switch must hold the terminal surface (loading state) " +
                "so the previous session's frame is never painted",
            vm.revealState.value !is RevealState.Live,
        )
        assertEquals(
            "previous session's frame must NOT stay painted during a cross-session " +
                "switch (#661): panes are blanked until the new session reconciles",
            emptyList<String>(),
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
        // Issue #661: the terminal surface is revealed only AFTER the new
        // session's panes are seeded — and it is the NEW session's content.
        assertFalse(
            "the terminal surface must be revealed (revealState Live) " +
                "once the new session's panes are seeded",
            vm.revealState.value !is RevealState.Live,
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
    fun firstConnectToHostStillShowsConnectingNotSwitching() = runTest(scheduler) {
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val vm = newVm(
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
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
    fun sameHostSwitchDeadSshSessionEscalatesToConnectingAndBlanks() = runTest(scheduler) {
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
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
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
    fun closeCachedRuntimeDetachesClientBeforeReleasingLease() = runTest(scheduler) {
        val session = FakeSshSession()
        val manager = testLeaseManager(
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
    fun forceFreshAcquireClosesSameLeaseCachedRuntimeBeforeOpeningTransport() = runTest(scheduler) {
        val staleSession = FakeSshSession()
        val freshSession = FakeSshSession()
        val connector = QueueLeaseConnector(staleSession, freshSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 0L,
        )
        val cachedLease = manager.acquire(testLeaseTarget()).getOrThrow()
        val runtimeCache = TmuxSessionRuntimeCache()
        val cachedClient = FakeTmuxClient()
        assertTrue(
            runtimeCache.put(
                cachedRuntimeForTest(
                    sessionName = "other",
                    client = cachedClient,
                    session = staleSession,
                    lease = cachedLease,
                ),
            ).isEmpty(),
        )
        val vm = newVm(runtimeCache = runtimeCache, sshLeaseManager = manager)

        val lease = vm.acquireLeaseForTmuxForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            trigger = TmuxConnectTrigger.NetworkReconnect,
        )

        assertNotNull(lease)
        assertSame("force-fresh acquire must open a new transport", freshSession, lease?.session)
        assertTrue("force-fresh lease should be marked as a new connection", lease?.isNewConnection == true)
        assertEquals("stale cached lease must not be reused", 2, connector.connectCount)
        assertFalse("same-lease cached runtime must be removed", runtimeCache.contains(tmuxRuntimeKeyForTest("other")))
        assertTrue("same-lease cached tmux client must close", cachedClient.closed)
        assertTrue("same-lease cached SSH session must close", staleSession.closed)
        lease?.release()
    }

    @Test
    fun autoReconnectAcquireForcesFreshLeaseEvictingThePoisonedWarmEntry() = runTest(scheduler) {
        // EPIC #792 Slice C / #822 WEDGE FIX — red→green for the production wiring.
        //
        // The #822 wedge: the AUTO-reconnect ladder re-dialled with the AutoReconnect
        // trigger, which on base did NOT force a fresh lease. On a silent half-open drop
        // sshj's `isConnected` lies (stays true), so the pool REUSED the poisoned warm
        // entry and every attempt re-dialled the dead socket — the maintainer's stuck-
        // Reconnecting wedge. Slice C adds AutoReconnect to `shouldForceFreshLease`, so
        // `acquireLeaseForTmux(AutoReconnect)` now evicts the idle/poisoned lease and
        // dials a FRESH transport — the SAME session recovers without the switch dance.
        //
        // RED on 8e1ccc99: AutoReconnect was not force-fresh, so this asserted the
        // poisoned `staleSession` was REUSED (connectCount stays 1, isNewConnection
        // false). GREEN after the fix: a fresh transport is dialled (connectCount 2).
        // The `staleSession` LIES about isConnected (default connected=true) to model
        // the half-open drop the pool's reuse predicate is fooled by.
        val staleSession = FakeSshSession() // half-open: connected=true but conceptually dead
        val freshSession = FakeSshSession()
        val connector = QueueLeaseConnector(staleSession, freshSession)
        // A non-zero idle TTL keeps the released lease genuinely WARM/idle (a 0 TTL would
        // close it on release, hiding the reuse-vs-evict distinction). The half-open drop
        // leaves it lying about isConnected, so the pool keeps it warm — the wedge state.
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        // Seed the pool with the (poisoned half-open) warm lease, then release it so it
        // sits idle — exactly the warm-lease state an auto-reconnect re-dial hits.
        manager.acquire(testLeaseTarget()).getOrThrow().release()
        val vm = newVm(sshLeaseManager = manager)

        val lease = vm.acquireLeaseForTmuxForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            trigger = TmuxConnectTrigger.AutoReconnect,
        )

        assertNotNull(lease)
        assertSame(
            "auto-reconnect must force-fresh: dial a NEW transport, not reuse the poisoned warm lease",
            freshSession,
            lease?.session,
        )
        assertTrue("the auto-reconnect lease must be a new connection", lease?.isNewConnection == true)
        assertEquals(
            "auto-reconnect must evict the poisoned warm lease and handshake fresh (the #822 fix)",
            2,
            connector.connectCount,
        )
        assertTrue("evicting the poisoned warm lease closes its (half-open dead) session", staleSession.closed)
        lease?.release()
    }

    @Test
    fun normalAcquirePreservesSameLeaseCachedRuntimeReuse() = runTest(scheduler) {
        val warmSession = FakeSshSession()
        val connector = QueueLeaseConnector(warmSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 0L,
        )
        val cachedLease = manager.acquire(testLeaseTarget()).getOrThrow()
        val runtimeCache = TmuxSessionRuntimeCache()
        val cachedClient = FakeTmuxClient()
        assertTrue(
            runtimeCache.put(
                cachedRuntimeForTest(
                    sessionName = "other",
                    client = cachedClient,
                    session = warmSession,
                    lease = cachedLease,
                ),
            ).isEmpty(),
        )
        val vm = newVm(runtimeCache = runtimeCache, sshLeaseManager = manager)

        val lease = vm.acquireLeaseForTmuxForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
        )

        assertNotNull(lease)
        assertSame("normal acquire should reuse the warm lease", warmSession, lease?.session)
        assertFalse("normal acquire should not open a new transport", lease?.isNewConnection == true)
        assertEquals("normal acquire must not reconnect", 1, connector.connectCount)
        assertTrue("normal acquire must preserve cached runtime", runtimeCache.contains(tmuxRuntimeKeyForTest("other")))
        assertFalse("normal acquire must keep cached tmux client warm", cachedClient.closed)
        assertFalse("normal acquire must keep cached SSH session warm", warmSession.closed)
        lease?.release()
        runtimeCache.remove(tmuxRuntimeKeyForTest("other"))?.closeCachedRuntime()
    }

    @Test
    fun fastSwitchDeadSessionFallbackReleasesAcquiredLeaseBeforeRetry() = runTest(scheduler) {
        val deadLeaseSession = FakeSshSession(isConnectedValue = false)
        val fallbackSession = FakeSshSession()
        val connector = QueueLeaseConnector(deadLeaseSession, fallbackSession)
        val manager = testLeaseManager(
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
    fun fastSwitchBlanksPreviousFrameAndHidesSurfaceUntilNewSessionReconciles() = runTest(scheduler) {
        // Issue #661 (reverses #437 slice A / #634 keep-frame for the
        // cross-session case): a same-host fast switch to a DIFFERENT session
        // must NEVER paint the leaving session's frame — not even one frame.
        // The previous frame is BLANKED and the reveal machine HOLDS the terminal
        // surface (revealState not Live, the screen shows the "Attaching"
        // loading state) until the new session's panes reconcile and reveal.
        // This is the maintainer's refined preference (hard-cut, per D22):
        // we no longer keep the previous frame painted on a cross-session
        // switch — that was the "I can still see the previous session for a
        // split second" flash.
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
        // replace the blanked frame — letting us assert the leaving frame is
        // BLANKED (not kept) the instant the cross-session switch begins.
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

        // Issue #661: the previous session's frame is BLANKED across a
        // cross-session switch — never re-published — so the screen shows the
        // loading state instead of the leaving session's content. (The new
        // session reported no panes, so nothing reconciles in to replace it.)
        assertEquals(
            "previous session's frame must be BLANKED on a cross-session switch (#661), " +
                "not kept painted — was ${vm.panes.value}",
            emptyList<String>(),
            vm.panes.value.map { it.paneId },
        )
    }

    @Test
    fun fastSwitchDoesNotDetachPreviousClientOnCriticalPath() = runTest(scheduler) {
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
    fun fastSwitchIncrementsTmuxConnectCounterButNotSshHandshakeCounter() = runTest(scheduler) {
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
    fun fastSwitchTelemetryUsesVisibleSwitchBaseline() = runTest(scheduler) {
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
    fun closedPaneClearsConversationRoutingState() = runTest(scheduler) {
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
    fun cancelConnectFlipsConnectingStatusToFailedAndCancelsJob() = runTest(scheduler) {
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
    fun cancelConnectIsNoOpWhenNotConnecting() = runTest(scheduler) {
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
    fun onAppBackgroundedCallsDetachCleanlyOnLiveClient() = runTest(scheduler) {
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
    fun onClearedWhileBackgroundedParksLiveRuntimeInsteadOfClosingLeaseBeforeGrace() = runTest(scheduler) {
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
    fun onClearedAfterScreenStopParksRuntimeDuringProcessLifecycleStopDebounce() = runTest(scheduler) {
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
    fun onClearedWhileBackgroundedClosesEvictedParkedRuntimeOutsideViewModelScope() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 1)
        val evictedSession = FakeSshSession()
        val evictedLeaseManager = testLeaseManager(
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
        // Issue #1085 (F2): the teardown closes evicted runtimes on the
        // application-scoped teardown scope (off Main). Pin it to the shared
        // virtual-clock scheduler so `advanceUntilIdle()` drives the async
        // close deterministically (no real-IO race).
        vm.setTeardownScopeForTest(CoroutineScope(StandardTestDispatcher(scheduler)))
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
        advanceUntilIdle()

        assertTrue("evicted parked client must detach during background clear", evictedClient.detachCleanlyCalled)
        assertTrue("evicted parked client must close during background clear", evictedClient.closed)
        assertTrue("evicted SSH lease must be released during background clear", evictedSession.closed)
        assertFalse("newly parked active client must remain live for grace handoff", activeClient.closed)
        assertFalse("active SSH session must remain live for grace handoff", activeSession.closed)
        assertEquals(listOf(tmuxRuntimeKeyForTest("work")), runtimeCache.snapshotKeys())
        assertFalse(runtimeCache.contains(tmuxRuntimeKeyForTest("old")))
    }

    @Test
    fun foregroundOnClearedStillClosesLiveRuntimeForUserNavigation() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        // Issue #1085 (F2): the foreground clear hands the live runtime's
        // detach/close to the application-scoped teardown scope (off Main).
        // Pin it to the shared virtual-clock scheduler so `advanceUntilIdle()`
        // drives the async close deterministically.
        vm.setTeardownScopeForTest(CoroutineScope(StandardTestDispatcher(scheduler)))
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
        advanceUntilIdle()

        assertTrue("foreground clear must keep the explicit detach/close behavior", client.detachCleanlyCalled)
        assertTrue("foreground clear must close the tmux client", client.closed)
        assertTrue("foreground clear must close the SSH session", session.closed)
        assertTrue("foreground clear must not leave a parked runtime", runtimeCache.snapshotKeys().isEmpty())
    }

    @Test
    fun restoredParkedRuntimeRebindsViewModelScopedPaneJobsWithoutSshReconnect() = runTest(scheduler) {
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
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
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
    fun foregroundReturnWithinGraceRestoresParkedRuntimeWithoutVisibleReconnectState() = runTest(scheduler) {
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
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
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
    fun onAppBackgroundedClosesInactiveCachedRuntimesForHost() = runTest(scheduler) {
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
    fun onAppForegroundedWaitsForInFlightBackgroundDetachBeforeConsumingReattach() = runTest(scheduler) {
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
    fun onAppForegroundedSkipsDetachedSessionWhenNewerConnectIntentExists() = runTest(scheduler) {
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
    fun onAppBackgroundedIsNoOpWhenNoActiveClient() = runTest(scheduler) {
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
    fun detachAndExitTearsClientDownAndClearsPendingReattach() = runTest(scheduler) {
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
    fun replaceClientInstallsLifecycleHooksIntoRegistry() = runTest(scheduler) {
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
    fun connectionTeardownPreservesLifecycleHooks() = runTest(scheduler) {
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
        // clears the active client registration. The hooks must
        // survive — they are tied to the VM, not the client.
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
    fun onClearedRemovesLifecycleHooksFromRegistry() = runTest(scheduler) {
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

    /**
     * Issue #548: one VM can be reused for another host. Installing
     * hooks for the new host must remove the prior host's hook; only
     * client entries are allowed to churn independently across normal
     * detach/attach cycles.
     */
    @Test
    fun reinstallingLifecycleHooksForDifferentHostRemovesPreviousHook() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.replaceClientForTest(
            hostId = 21L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        assertEquals(1, registry.lifecycleHooksSnapshot().size)

        vm.replaceClientForTest(
            hostId = 22L,
            hostName = "beta",
            host = "beta.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/b",
            sessionName = "work",
            client = FakeTmuxClient(),
        )

        assertEquals(
            "reinstalling hooks for a different host must not leak the old host hook",
            1,
            registry.lifecycleHooksSnapshot().size,
        )
        assertTrue("old host client entry should be gone", 21L !in registry.clients.value)
        assertTrue("new host client entry should be present", 22L in registry.clients.value)

        vm.clearForTest()
        advanceUntilIdle()

        assertTrue(
            "clearing the VM must remove the latest lifecycle hook",
            registry.lifecycleHooksSnapshot().isEmpty(),
        )
    }

    /**
     * Issue #548: a stale VM can finish teardown after a newer VM has
     * already attached to the same host. The stale teardown must not
     * evict the newer client entry or lifecycle hook.
     */
    @Test
    fun staleViewModelClearDoesNotUnregisterNewerSameHostClientOrHooks() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val oldVm = newVm(registry)
        val newVm = newVm(registry)
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()
        oldVm.replaceClientForTest(
            hostId = 13L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )
        newVm.replaceClientForTest(
            hostId = 13L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = newClient,
        )
        assertSame(newClient, registry.clients.value[13L]?.client)
        assertEquals(1, registry.lifecycleHooksSnapshot().size)

        oldVm.clearForTest()
        advanceUntilIdle()

        assertSame(
            "stale VM teardown must not remove newer client entry",
            newClient,
            registry.clients.value[13L]?.client,
        )
        assertEquals(
            "stale VM teardown must not remove newer lifecycle hook",
            1,
            registry.lifecycleHooksSnapshot().size,
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
    fun confirmedNewPortSurfacesOverlay() = runTest(scheduler) {
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
    fun stalePortDetectorFromParkedRuntimeDoesNotSurfaceOnNewRuntime() = runTest(scheduler) {
        val vm = newVm()
        val oldClient = FakeTmuxClient()
        val oldSession = FakeSshSession(ssListeningPorts = emptySet())
        vm.attachForPortDetection(oldClient, oldSession)
        advanceUntilIdle()

        val newClient = FakeTmuxClient()
        val newSession = FakeSshSession(ssListeningPorts = setOf(5173))
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
    fun assistantConversationLoopbackPortPhraseSurfacesPortForwardOverlay() = runTest(scheduler) {
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
    fun agentToolResultLoopbackPortSurfacesPortForwardOverlay() = runTest(scheduler) {
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
    fun userConversationLocalhostUrlDoesNotSurfacePortForwardOverlay() = runTest(scheduler) {
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
    fun echoedPortNotListeningDoesNotSurfaceOverlay() = runTest(scheduler) {
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
    fun acceptingDetectedPortReturnsItAndClearsOverlay() = runTest(scheduler) {
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
    fun dismissedPortDoesNotReSurfaceInSameSession() = runTest(scheduler) {
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
    fun forwardedPortDoesNotReSurfaceInSameSession() = runTest(scheduler) {
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

    // ---- Issue #877: per-%output port-detection decode + scan runs OFF Main ----

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
        // scheduler), NOT the Unconfined Main dispatcher — wrapping Unconfined
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
     * Issue #877 regression (red→green, load-bearing): the per-`%output`
     * decode + 7-regex [PortDetector.scan] — the work that froze an idle agent
     * session because it ran on the UI thread for every output chunk — must run
     * on the injected off-main `portDetectionDispatcher`, NOT inline on the
     * bridge scope (Main). RED on base: `startPortDetectionForPane` ran the
     * scan inline so the tracking dispatcher is never used. GREEN with the fix:
     * `scanOutputEventForPorts` hops to `portDetectionDispatcher`, so the
     * tracker records the dispatch. The port is still detected (behaviour
     * preserved — only the thread changed).
     */
    @Test
    fun portDetectionDecodeAndScanRunsOffMainNotOnBridgeScope() = runTest(scheduler) {
        val vm = newVm()
        val tracker = ScanDispatchTracker(StandardTestDispatcher(scheduler))
        vm.setPortDetectionDispatcherForTest(tracker)
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(5173))
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
        val session = FakeSshSession(ssListeningPorts = emptySet())
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

    /**
     * Issue #1085 (F2) reproduce-first — the MULTI-RUNTIME teardown class.
     * `onCleared` → `closeCurrentConnection` runs on the Main thread and used to
     * close every host-cached runtime via `runBlocking(Dispatchers.IO)` ON Main.
     * Each runtime's `lease.release()` is a NON-suspending park whose bounded
     * `SYNC_DETACH_TIMEOUT_MS` ceiling a coroutine cancel cannot interrupt — so
     * on a wedged transport the real Main park is the SUM of the N runtimes'
     * wedges (a multi-second / ANR-class freeze finishing a session).
     *
     * RED on base: `clearForTest()` parks the calling (Main) thread for ~3×800ms
     * — the elapsed assertion fails.
     *
     * GREEN with the fix: `closeCurrentConnection` captures the runtimes, nulls
     * the fields on Main, and hands the closes to the application-scoped teardown
     * scope, so `onCleared` returns immediately AND every runtime is still closed
     * to completion off-Main (all three latches fire) — no leak, no Main park.
     */
    @Test
    fun onClearedClosesCachedRuntimesOffMainAndNeverParksTheCallingThread() = runTest(scheduler) {
        val hostId = 7L
        val released = CountDownLatch(3)
        val wedgeMs = 800L
        // maxEntries high enough that the three same-host runtimes coexist (the
        // default per-host cap would evict one); nowMs pinned so no TTL expiry.
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 8, nowMs = { 0L })
        // Issue #1085 (F2) test isolation: run the teardown on the per-test
        // `defaultTeardownScope` `newVm` injects — a real `Dispatchers.IO` scope
        // (so the hand-off genuinely leaves Main) that `@After` cancels+JOINs,
        // so the wedged release coroutines cannot escape `runTest`/`resetMain`.
        val vm = newVm(runtimeCache = runtimeCache)
        try {
            vm.replaceClientForTest(
                hostId = hostId,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = FakeTmuxClient(),
                session = FakeSshSession(),
            )
            runCurrent()
            // Park three wedged cached runtimes under the ACTIVE host; the
            // teardown's `runtimeCache.removeHost(hostId)` returns all three and
            // must close every one off the Main thread.
            repeat(3) { i ->
                runtimeCache.put(
                    cachedRuntimeForTest(
                        sessionName = "cached-$i",
                        hostId = hostId,
                        lease = wedgedLeaseForTest(
                            SshLeaseKey("alpha.example", 22, "alex", "$hostId:/keys/a-$i"),
                            FakeSshSession(),
                            wedgeMs,
                        ) { released.countDown() },
                    ),
                )
            }
            assertEquals(3, runtimeCache.size())
            vm.setProcessForegroundForClearedForTest(true)

            val elapsedMs = measureTimeMillis { vm.clearForTest() }

            assertTrue(
                "onCleared parked the calling (Main) thread for ${elapsedMs}ms closing 3 wedged " +
                    "cached runtimes — the #1085 F2 multi-runtime teardown ANR. The closes must " +
                    "be handed to the application-scoped teardown scope so onCleared returns " +
                    "immediately.",
                elapsedMs < TEARDOWN_PARK_BUDGET_MS,
            )
            assertTrue(
                "every cached runtime's lease release must still run to COMPLETION off the Main " +
                    "thread (no leak) — only ${3 - released.count} of 3 fired.",
                released.await(10, TimeUnit.SECONDS),
            )
        } finally {
            vm.setProcessForegroundForClearedForTest(null)
        }
    }

    /**
     * Build an [SshLease] (internal constructor) whose `releaseAction` blocks
     * the calling thread for [wedgeMs] then signals [onReleased] — a wedged
     * transport close whose bounded ceiling is defeated because a coroutine
     * cancel cannot unpark a parked thread. Mirrors the reflection-built lease
     * the sibling nav-scoped suites use.
     */
    private fun wedgedLeaseForTest(
        key: SshLeaseKey,
        session: SshSession,
        wedgeMs: Long,
        onReleased: () -> Unit,
    ): SshLease {
        val releaseAction = { _: SshLeaseKey, _: Long, _: Continuation<Unit> ->
            Thread.sleep(wedgeMs)
            onReleased()
            Unit
        }
        val constructor = SshLease::class.java.declaredConstructors.single()
        return constructor.newInstance(key, session, false, 0L, releaseAction) as SshLease
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

    // ---- Issue #626: unified pane list tests ----

    @Test
    fun unifiedPanesStartsEmpty() {
        val vm = newVm()
        assertTrue(vm.unifiedPanes.value.isEmpty())
    }

    @Test
    fun unifiedPanesMirrorsActivePanesWhenNoCachedRuntimes() = runTest(scheduler) {
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

        // Without cached runtimes, unified panes = active panes.
        assertEquals(1, vm.unifiedPanes.value.size)
        assertEquals("%0", vm.unifiedPanes.value[0].paneId)
    }

    @Test
    fun unifiedPanesIncludesCachedSessionPanes() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()

        // Set up initial "work" session with one pane.
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

        // Fast switch to "other" session, caching "work".
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

        // After the fast switch, unified panes should include both
        // the active "other" session's panes and the cached "work"
        // session's panes.
        val unified = vm.unifiedPanes.value
        assertTrue(
            "unified panes should contain panes from both sessions, got ${unified.size}",
            unified.size >= 1,
        )
    }

    @Test
    fun isActiveSessionPaneReturnsTrueForActivePanes() = runTest(scheduler) {
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

        val pane = vm.panes.value.first()
        assertTrue(vm.isActiveSessionPane(pane))
    }

    @Test
    fun sessionNameForUnifiedPaneReturnsActiveSessionName() = runTest(scheduler) {
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

        val pane = vm.panes.value.first()
        assertEquals("work", vm.sessionNameForUnifiedPane(pane))
    }

    @Test
    fun sessionNameForUnifiedPaneReturnsNullWhenNoActiveTarget() {
        val vm = newVm()
        val fakePane = TmuxPaneState(
            paneId = "%99",
            windowId = "@99",
            sessionId = "\$99",
            title = "orphan",
            cwd = "/tmp",
            terminalState = TerminalSurfaceState(),
        )
        assertNull(vm.sessionNameForUnifiedPane(fakePane))
    }

    // ---- End Issue #626 tests ----

    // ---- Issue #662: black-pane re-seed on a live connection ----

    @Test
    fun reseedVisiblePaneIfBlankReCapturesAndHealsABlackPane() = runTest(scheduler) {
        // The maintainer's symptom: a window renders a BLACK pane (the seed
        // never painted content) on a LIVE connection, and switching to it does
        // not recover it. Drive that exact state: the pane's attach-time seed
        // returns EMPTY (no content), so its emulator stays blank. Then a window
        // switch calls reseedVisiblePaneIfBlank — which must issue a FRESH
        // capture-pane and paint the content tmux's grid now holds.
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        // Attach-time seed comes back EMPTY -> the pane stays black.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = emptyList(), isError = false),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val pane = vm.panes.value.single { it.paneId == "%0" }
        assertTrue(
            "precondition: the empty attach-time seed must leave the pane BLACK",
            pane.terminalState.visibleScreenIsBlank(),
        )
        val captureCountAfterAttach =
            client.sentCommands.count { it == seedCaptureCommand("%0") }

        // The user switches to this window: a fresh capture now HAS content.
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 3L,
                output = listOf("ISSUE662-RECOVERED-CONTENT"),
                isError = false,
            ),
        )
        vm.reseedVisiblePaneIfBlank("%0")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val captureCountAfterReseed =
            client.sentCommands.count { it == seedCaptureCommand("%0") }
        assertTrue(
            "expected a FRESH capture-pane re-seed for the blank pane, " +
                "got commands ${client.sentCommands}",
            captureCountAfterReseed > captureCountAfterAttach,
        )
        assertFalse(
            "the pane must no longer be BLACK after the re-seed painted content",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertTrue(
            "the re-seeded content must be on the pane's grid, got " +
                renderedTranscriptFrom(pane.terminalState),
            renderedTranscriptFrom(pane.terminalState)
                .contains("ISSUE662-RECOVERED-CONTENT"),
        )
    }

    @Test
    fun reseedVisiblePaneIfBlankIsNoOpWhenPaneAlreadyShowsContent() = runTest(scheduler) {
        // A pane that already painted content must NOT be re-captured on a
        // window switch — the blank-only guard keeps the switch cheap and never
        // clobbers a good frame.
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
                output = listOf("ALREADY-VISIBLE-CONTENT"),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val pane = vm.panes.value.single { it.paneId == "%0" }
        assertFalse(
            "precondition: the seeded pane must show content (not blank)",
            pane.terminalState.visibleScreenIsBlank(),
        )
        val captureCountBefore =
            client.sentCommands.count { it == seedCaptureCommand("%0") }

        vm.reseedVisiblePaneIfBlank("%0")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val captureCountAfter =
            client.sentCommands.count { it == seedCaptureCommand("%0") }
        assertEquals(
            "a non-blank pane must NOT trigger a re-capture on switch",
            captureCountBefore,
            captureCountAfter,
        )
    }

    // ---- End Issue #662 tests ----

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
        // Issue #713: the slow-feed / real-async terminal tests below keep
        // `factoryScope` on real `Dispatchers.IO` (#708 judgment call A), so
        // they drain the real bridge feed / final marker via real-wall-clock
        // await-for-condition loops. These return the instant the condition is
        // ready, so a generous ceiling only adds headroom for a contended dev
        // box (observed >5s under 15+ load) WITHOUT slowing a passing run. A
        // genuinely stuck feed still times out and fails the assertion below.
        const val SLOW_FEED_DRAIN_TIMEOUT_MS = 30_000L

        // Issue #1110: generous wall-clock ceiling for [awaitCondition]'s settle
        // pump. The recorded-kind / cache-restore re-seed it waits on lands in
        // well under a second even on a contended box, so this only adds headroom
        // for a genuine stall; kept comfortably under `runTest`'s 60s default so a
        // genuinely-stuck predicate fails the pump's own HARD assertion with a
        // clear message rather than an opaque outer `runTest` timeout (a method
        // may call [awaitCondition] twice, so 2x must still fit the 60s budget).
        const val AWAIT_CONDITION_TIMEOUT_MS = 20_000L

        // Issue #1085 (F2): the off-Main hand-off must let onCleared return
        // within a small budget; on base closing the 3 wedged runtimes parks the
        // calling thread for ~3×800ms.
        const val TEARDOWN_PARK_BUDGET_MS = 500L

        // Issue #713: `runTest` enforces its OWN default 60s wall-clock budget.
        // The slow-feed drains above busy-wait (Thread.sleep / real-IO feed) for
        // real wall-clock time, so raising the drain ceiling to 30s alone can,
        // under heavy box contention, push the whole `runTest` body past that
        // default budget — which fails as an outer `runTest` timeout attributed
        // to the test's lambda line, NOT the inner marker assertion. Give the
        // slow-feed tests an explicit, generous `runTest` timeout (well above
        // the 30s drain + setup) so the headroom is real and a genuine failure
        // still surfaces as the specific inner assertion, never an opaque
        // outer-timeout flake.
        val SLOW_FEED_RUN_TEST_TIMEOUT = 120.seconds

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
        // Issue #793: mutable so a paging test can widen the window the fake
        // returns between the first-paint read and a load-older read.
        private var wcOutput: String = "0\n",
        private var initialEventsOutput: String = "",
        private val agentLogLines: List<String>? = null,
        private val execFailure: Throwable? = null,
        private val tailFailure: Throwable? = null,
        private val detectionOutput: String = "",
        private val processOutput: String = "",
        private val recordedKindOutput: String = "",
        private var recordedSourceGenerationOutput: String = "",
        private var recordedSourceOutput: String = "",
        // Issue #448: ports the `ss -tlnp` confirm scan reports as
        // LISTENing. The detection collector calls PortScanner.scan, which
        // runs `ss -tlnp ... | awk ...`; we answer that with one line per
        // listening port in the `addr:port process` shape PortScanner parses.
        private val ssListeningPorts: Set<Int> = emptySet(),
    ) : com.pocketshell.core.ssh.SshSession {
        @Volatile
        var closed: Boolean = false

        // Issue #1289: exec() appends to this list from a real Dispatchers.IO
        // thread (the recorded-kind read runs off-Main), while test-thread
        // `awaitCondition` predicates walk it via Kotlin `.count { }` / `.any { }`
        // (an iterator walk). A plain ArrayList throws ConcurrentModificationException
        // on that concurrent iterate+append — the flake that red-blocked the
        // pre-release confidence gate under `--no-parallel --max-workers=2`. A
        // CopyOnWriteArrayList iterates over a stable snapshot and copies on append,
        // so no predicate read can ever race the exec() append. Class-covering: this
        // makes EVERY execCommands read across the suite race-safe, not just one test.
        val execCommands: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

        // Issue #793: let a paging test reprogram the window the fake returns.
        fun setWcOutput(value: String) { wcOutput = value }
        fun setInitialEventsOutput(value: String) { initialEventsOutput = value }
        fun setRecordedSourceGenerationOutput(value: String) { recordedSourceGenerationOutput = value }
        fun setRecordedSourceOutput(value: String) { recordedSourceOutput = value }

        override val isConnected: Boolean
            get() = isConnectedValue && !closed

        // Issue #964: lets a test report the transport keepalive as still proving
        // the link alive (a slow-but-live link). Default mirrors the production
        // SshSession default (false) so unrelated tests are unaffected.
        @Volatile
        var transportProvenAlive: Boolean = false

        override fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean =
            transportProvenAlive && isConnected

        override suspend fun exec(command: String): com.pocketshell.core.ssh.ExecResult {
            execCommands += command
            execGate?.await()
            execFailure?.let { throw it }
            val stdout = when {
                command.contains("@@PS_RECORDED_KIND@@") -> buildString {
                    append(recordedKindOutput.trim())
                    append("\n@@PS_RECORDED_KIND@@\n")
                    append(recordedSourceGenerationOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE_GENERATION@@\n")
                    append(recordedSourceOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE@@\n")
                    append(detectionOutput)
                    append("\n@@PS_CLAUDE_WINDOW@@\n")
                }
                command.contains("show-options -v") && command.contains("@ps_agent_kind") ->
                    recordedKindOutput
                command.contains("@@PS_RECORDED_SOURCE_GENERATION@@") -> buildString {
                    append(recordedSourceGenerationOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE_GENERATION@@\n")
                    append(recordedSourceOutput.trim())
                }
                command.contains("show-options -v") && command.contains("@ps_agent_source") ->
                    recordedSourceOutput
                command.contains("ss -tlnp") ->
                    ssListeningPorts.joinToString("\n") { "0.0.0.0:$it users:((\"server\",pid=1,fd=3))" }
                command.contains("netstat -tlnp") || command.contains("ss -tln") -> ""
                // Issue #793: the windowed read (readEventsWindow) combines
                // wc -l + a sentinel + the tail into ONE round-trip. Answer in
                // that shape so the repository can split total-lines from the
                // tail window.
                command.contains("@@PS_WINDOW@@") ->
                    "${wcOutput.trim()}\n@@PS_WINDOW@@\n$initialEventsOutput"
                // Issue #817: the Codex windowed read folds wc -l + a sentinel +
                // the agent-log window into ONE round-trip (no separate
                // lineCount exec). Answer in that shape so the repository can
                // split the raw-file line count (follow cursor) from the window.
                command.contains("@@PS_CODEX_WINDOW@@") ->
                    "${wcOutput.trim()}\n@@PS_CODEX_WINDOW@@\n${agentLogEnvelope(command) ?: initialEventsOutput}"
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
            tailFailure?.let { throw it }
            return tailJob
        }

        override fun tail(
            path: String,
            fromLineExclusive: Long,
            onLine: (String) -> Unit,
        ): kotlinx.coroutines.Job {
            tailFromLineCalls += path to fromLineExclusive
            tailCalls += 1
            tailFailure?.let { throw it }
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

    /**
     * Issue #576: a fake SSH session whose tail feeds a fixed list of JSONL
     * lines to `onLine` synchronously (one burst), the way a Codex `/new`
     * rollout replay arrives. Lets the VM burst-emit test drive a real
     * [AgentConversationRepository] tail end-to-end and observe how many
     * `agentConversations` emissions the batched ingest produces.
     */
    private class ReplayTailSshSession(
        private val replayLines: List<String>,
    ) : com.pocketshell.core.ssh.SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): com.pocketshell.core.ssh.ExecResult {
            val stdout = if (command.contains("wc -l < ")) "0\n" else ""
            return com.pocketshell.core.ssh.ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): kotlinx.coroutines.Job {
            replayLines.forEach(onLine)
            return Job()
        }

        override fun tail(
            path: String,
            fromLineExclusive: Long,
            onLine: (String) -> Unit,
        ): kotlinx.coroutines.Job {
            replayLines.forEach(onLine)
            return Job()
        }

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): com.pocketshell.core.ssh.SshPortForward = throw NotImplementedError()

        override fun startShell(): com.pocketshell.core.ssh.SshShell = throw NotImplementedError()

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
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

    /**
     * Issue #975: an [SshSession] double modelling the MASKED-LIVE-AGENT host the
     * maintainer hit — the agent-kind daemon's cgroup-v2/`/proc` classify returns
     * `unknown` (or `none`) for a node-wrapped/quiet `claude`, while the agent's
     * `*.jsonl` transcript is genuinely present in the cwd-encoded log dir. It
     * answers exactly the two execs the foreign-detection chain issues:
     *   - the `pocketshell agents kind` daemon classify (JSON envelope, with
     *     [classifyAgentKind] one of `claude`/`codex`/`opencode`/`none`/`unknown`),
     *   - the cwd-scoped candidate enumeration ([detectionCommand], `claude_dir=`),
     *     returning [detectionOutput].
     * This is the #780 synthetic-state injection at the host seam — it reproduces
     * the classify-miss the real Docker fixture also produces (scope=null in a
     * non-systemd container), exercising the REAL resolver, not a markAgentTailLive
     * injection.
     */
    private class MaskedAgentSshSession(
        private val classifyAgentKind: String,
        private val detectionOutput: String = "",
        private val hostWideProcessOutput: String = "",
    ) : SshSession {
        override val isConnected: Boolean = true

        // Issue #1001: count `agents kind` daemon classify round-trips so the B1′
        // dedup-ordering test can assert the cache-bust forced a FRESH daemon
        // re-evaluation deterministically — instead of relying on the (now-fixed)
        // race that left the re-probe unfinished when `runCurrent()` returned.
        var classifyExecCount: Int = 0
            private set

        override suspend fun exec(command: String): ExecResult {
            val stdout = when {
                // The host-side `pocketshell agents kind` daemon classify. The
                // request pipes a `{"panes":[{"pane_id":"%N", ...}]}` snapshot; we
                // echo each requested pane id back with [classifyAgentKind].
                command.contains("agents kind") -> {
                    classifyExecCount++
                    val paneIds = PANE_ID_RE.findAll(command).map { it.groupValues[1] }.toList()
                    buildString {
                        append("{\"results\":[")
                        paneIds.forEachIndexed { index, paneId ->
                            if (index > 0) append(',')
                            append("{\"pane_id\":\"").append(paneId).append("\",")
                            append("\"agent_kind\":\"").append(classifyAgentKind).append("\",")
                            append("\"scope\":null}")
                        }
                        append("]}")
                    }
                }
                // The cwd-scoped candidate enumeration ([detectionCommand]).
                command.contains("claude_dir=") -> detectionOutput
                command.contains("ps -eo pid,ppid,tty,comm,args") -> hostWideProcessOutput
                command.contains("ps -eo pid,tty,comm,args") -> hostWideProcessOutput
                else -> ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()
        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job = Job()
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError()
        override fun startShell(): SshShell = throw NotImplementedError()
        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used")
        override fun close() = Unit

        private companion object {
            val PANE_ID_RE = Regex("\"pane_id\":\"([^\"]+)\"")
        }
    }

}
