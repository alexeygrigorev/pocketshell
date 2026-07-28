package com.pocketshell.app.tmux

import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1778 (rework round) — the confirmed scroll owner must reach the
 * ViewModel as IMMUTABLE IDENTITY, never as a numeric page index.
 *
 * The unified pager has TWO independently-published page lists:
 *
 *  * the Compose snapshot the coordinator measured and confirmed
 *    ([UnifiedPagerPage] with its composite [UnifiedPagerPageKey]), and
 *  * [TmuxSessionViewModel.unifiedPanes], republished on every reconcile,
 *    pane add/remove, cache park/evict and stale-heal.
 *
 * A numeric index is only meaningful in the FIRST list. Handing that index to
 * the ViewModel makes it re-resolve the page against the SECOND list, so any
 * republish between measure and settle silently retargets the user's gesture.
 * Both directions of that defect are user-visible and both are reproduced
 * here:
 *
 *  * [confirmedCachedPageStillSwitchesAfterActiveSessionGainsAWindow] — the
 *    swipe is LOST (the drifted index lands back on an active-session pane, so
 *    the ViewModel returns without emitting); and
 *  * [confirmedCachedPageNeverRoutesToADifferentCachedSession] — the swipe is
 *    MIS-ROUTED to a different cached session (the maintainer's recurring
 *    "wrong session after switching" symptom).
 *
 * [settleOnConfirmedPage] is the only line that changes between the red and
 * green shapes: it mirrors exactly what
 * [com.pocketshell.app.tmux.TmuxUnifiedPagerSettleEffects] hands the ViewModel
 * for a confirmed settle. The load-bearing assertions below are identical in
 * both, so this is a real red -> green, not a rewritten expectation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1778SettleIdentityReachesViewModelTest : TmuxSessionViewModelTestBase() {

    @Test
    fun confirmedCachedPageStillSwitchesAfterActiveSessionGainsAWindow() =
        runTest(scheduler) {
            val runtimeCache = TmuxSessionRuntimeCache()
            val vm = newVm(runtimeCache = runtimeCache)
            runtimeCache.put(cachedRuntime("deploy", paneIds = listOf("%5")))
            attachActiveWork(vm, paneIds = listOf("%0"))
            advanceUntilIdle()

            // What Compose measured and what the coordinator confirmed.
            val measured = composeSnapshot(vm)
            assertEquals(listOf("%0", "%5"), measured.map { it.pane.paneId })
            val confirmed = measured.single { it.owner.sessionName == "deploy" }

            val firstSwitch = async { vm.sessionSwitchRequest.first() }
            runCurrent()

            // The active tmux session gains a SECOND window before the settle is
            // dispatched. `unifiedPanes` republishes as [%0, %1, %5]; the index
            // the coordinator measured for deploy (1) now points at the active
            // session's new pane, so an index-carrying settle is swallowed and
            // the user's completed swipe never switches.
            applyWorkPanes(vm, paneIds = listOf("%0", "%1"))
            advanceUntilIdle()
            assertEquals(listOf("%0", "%1", "%5"), vm.unifiedPanes.value.map { it.paneId })

            vm.settleOnConfirmedPage(measured, confirmed)
            advanceUntilIdle()

            assertTrue(
                "a confirmed settle on the cached deploy page must still emit a " +
                    "switch after unifiedPanes republished under the measured index",
                firstSwitch.isCompleted,
            )
            assertEquals("deploy", firstSwitch.await())
        }

    @Test
    fun confirmedCachedPageNeverRoutesToADifferentCachedSession() =
        runTest(scheduler) {
            val runtimeCache = TmuxSessionRuntimeCache()
            val vm = newVm(runtimeCache = runtimeCache)
            runtimeCache.put(cachedRuntime("deploy", paneIds = listOf("%5")))
            runtimeCache.put(cachedRuntime("release", paneIds = listOf("%9")))
            attachActiveWork(vm, paneIds = listOf("%0"))
            advanceUntilIdle()

            val measured = composeSnapshot(vm)
            assertEquals(listOf("%0", "%5", "%9"), measured.map { it.pane.paneId })
            val confirmed = measured.single { it.owner.sessionName == "release" }

            val firstSwitch = async { vm.sessionSwitchRequest.first() }
            runCurrent()

            // Same republish, one page earlier: the measured index for release
            // (2) now resolves to the OTHER cached session, deploy.
            applyWorkPanes(vm, paneIds = listOf("%0", "%1"))
            advanceUntilIdle()
            assertEquals(
                listOf("%0", "%1", "%5", "%9"),
                vm.unifiedPanes.value.map { it.paneId },
            )

            vm.settleOnConfirmedPage(measured, confirmed)
            advanceUntilIdle()

            val switched = firstSwitch.await()
            assertFalse(
                "the confirmed release page must never route to the sibling " +
                    "cached session that drifted into its old index",
                switched == "deploy",
            )
            assertEquals("release", switched)
        }

    @Test
    fun confirmedActiveSessionPageNeverSwitchesEvenAfterRepublish() =
        runTest(scheduler) {
            val runtimeCache = TmuxSessionRuntimeCache()
            val vm = newVm(runtimeCache = runtimeCache)
            runtimeCache.put(cachedRuntime("deploy", paneIds = listOf("%5")))
            attachActiveWork(vm, paneIds = listOf("%0"))
            advanceUntilIdle()

            val measured = composeSnapshot(vm)
            val confirmedActive = measured.single { it.pane.paneId == "%0" }

            val firstSwitch = async { vm.sessionSwitchRequest.first() }
            runCurrent()

            // The active pane list shrinks so the measured index 0 would still
            // exist but the identity must still be honoured as "no switch".
            applyWorkPanes(vm, paneIds = listOf("%0", "%1"))
            advanceUntilIdle()

            vm.settleOnConfirmedPage(measured, confirmedActive)
            advanceUntilIdle()

            assertFalse(
                "a settle confirmed on the active session's own page must never " +
                    "emit a session switch",
                firstSwitch.isCompleted,
            )
            firstSwitch.cancel()
        }

    /**
     * Exactly what the production confirmed-settle callback hands the
     * ViewModel. Before #1778's rework this was a numeric page index resolved
     * against a different, later list; it is now the immutable owner the
     * coordinator confirmed.
     */
    private fun TmuxSessionViewModel.settleOnConfirmedPage(
        measured: List<UnifiedPagerPage>,
        confirmed: UnifiedPagerPage,
    ) {
        // `measured` is retained deliberately: it is the list the numeric index
        // came from, and asserting membership keeps the fixture honest about
        // which snapshot the coordinator confirmed.
        check(measured.contains(confirmed)) { "confirmed page must be measured" }
        onUnifiedPageSettled(confirmed.settled())
    }

    private fun composeSnapshot(vm: TmuxSessionViewModel): List<UnifiedPagerPage> =
        unifiedPagerPages(
            panes = vm.unifiedPanes.value,
            targetEpoch = UnifiedPagerTargetEpoch(SessionId("issue1778/work"), 1L),
            targetSessionName = "work",
            sessionNameForPane = vm::sessionNameForUnifiedPane,
        )

    private fun attachActiveWork(vm: TmuxSessionViewModel, paneIds: List<String>) {
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        applyWorkPanes(vm, paneIds)
    }

    private fun applyWorkPanes(vm: TmuxSessionViewModel, paneIds: List<String>) {
        vm.applyParsedPanesForTest(
            paneIds.mapIndexed { index, paneId ->
                TmuxSessionViewModel.ParsedPane(
                    paneId = paneId,
                    windowId = "@$index",
                    sessionId = "\$0",
                    title = "work-w$index",
                    paneIndex = 0,
                    sessionName = "work",
                )
            },
        )
    }

    private fun cachedRuntime(
        sessionName: String,
        paneIds: List<String>,
    ): CachedTmuxRuntime = CachedTmuxRuntime(
        key = TmuxRuntimeKey(
            hostId = 1L,
            hostname = "alpha.example",
            port = 22,
            username = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
        ),
        hostName = "alpha",
        startDirectory = null,
        session = null,
        client = FakeTmuxClient(),
        panes = paneIds.map { paneId ->
            TmuxPaneState(
                paneId = paneId,
                windowId = "@$paneId",
                sessionId = "\$$sessionName",
                title = sessionName,
                cwd = "/tmp",
                terminalState = TerminalSurfaceState(),
            )
        },
        paneRows = emptyMap(),
        paneProducerJobs = emptyMap(),
        paneInputQueues = emptyMap(),
        paneInputJobs = emptyMap(),
        paneAgentJobs = emptyMap(),
        paneAgentInputs = emptyMap(),
        agentConversations = emptyMap(),
        remoteColumns = 0,
        remoteRows = 0,
        lease = null,
    )
}
