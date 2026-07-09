package com.pocketshell.app.tmux

import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionViewModelStructuralReconcileTest : TmuxSessionViewModelTestBase() {
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
}
