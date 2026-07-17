package com.pocketshell.app.tmux

import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkChangeKind
import com.pocketshell.app.connectivity.TerminalNetworkSnapshot
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

/**
 * Issue #1654 — the give-up WAKE must reach an ACTUAL DIAL, not just a state transition.
 *
 * ## Why this test exists (and why the reducer test cannot replace it)
 *
 * The #1656 audit's central warning: a reducer-only `Unreachable` exit is **strictly worse than
 * doing nothing**. It would swap today's honest `Failed` band — which has a WORKING "Tap
 * Reconnect" — for a lying, untappable "Reconnecting…" with ZERO IO behind it. The reducer
 * transition is free; the dial is the whole point.
 *
 * Three independent mechanisms in production ABSORB a naive wake (each verified by reading the
 * code, and each of which a JVM reducer test is structurally blind to):
 *  1. `onReconnectLadderEntered` deliberately REFUSES to re-arm from `Unreachable`
 *     (`ConnectionController.kt`) — `enterReconnectLadder` submitted while still Unreachable is
 *     a no-op.
 *  2. The auto-reconnect ladder body reads `connectionManager.state as? Reconnecting ?: break`
 *     (`TmuxSessionViewModel.kt`) — from `Unreachable` it breaks INSTANTLY, dialling nothing.
 *  3. The driver's foreground effect fires only on the `Backgrounded -> Reconnecting` edge, and
 *     its body replays a `pendingReattach` / resumes a `pausedAutoReconnect` — after a give-up
 *     there is NEITHER, so it takes the "no-pending" arm and does nothing.
 *
 * The fix routes around all three by ORDERING: the reducer moves `Unreachable -> Reconnecting`
 * FIRST, and the driver fires the wake effect on that EDGE — so by the time the wake's
 * `scheduleAutoReconnect` runs, the controller already reads `Reconnecting` and the ladder body
 * does not break.
 *
 * That ordering argument is exactly the kind of reasoning that has shipped this class of bug
 * four times, so this test does NOT assert the transition. **It asserts the connector actually
 * opened a new SSH transport** (`connectCount`) — the one observable a dead-end wake cannot
 * fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1654GiveUpWakeDialsTest : TmuxSessionViewModelTestBase() {

    /** Every dial fails retryably, so the ladder genuinely walks to exhaustion and gives up. */
    private class AlwaysFailingLeaseConnector : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.failure(
                SshException(
                    "SSH connect to alex@alpha.example:22 failed: ConnectException: Connection refused",
                    ConnectException("Connection refused"),
                ),
            )
        }
    }

    private fun networkChange(kind: TerminalNetworkChangeKind) =
        TerminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.Validated("wifi"),
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = kind.name,
            sequence = 1L,
            kind = kind,
        )

    private class Fixture(
        val vm: TmuxSessionViewModel,
        val connector: AlwaysFailingLeaseConnector,
    )

    /**
     * Drives the VM to a REAL give-up through the PRODUCTION path: a passive EOF schedules the
     * auto-reconnect ladder, every rung's dial fails retryably, the ladder exhausts its budget
     * and the controller surrenders. Deliberately NOT a hand-submitted `escalateUnreachable()`
     * — the state must be one production actually manufactures, or the wake is proven against a
     * state the user never reaches.
     */
    private suspend fun TestScopeAlias.bringToGiveUp(): Fixture {
        val registry = ActiveTmuxClients()
        val connector = AlwaysFailingLeaseConnector()
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
        vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
        // Skip the passive-grace loop: this test's subject is the give-up EXIT, not the loop.
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        // A 2-rung instant ladder reaches exhaustion in two dials. The ladder's LENGTH is the
        // reducer test's subject; reaching a genuine `Unreachable` is this one's.
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))
        vm.setTmuxClientFactoryForTest { _, _, _ -> FakeTmuxClient() }

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
        vm.onAppForegrounded()
        // A passive EOF surfaces the honest manual-Reconnect affordance without dialling.
        deadClient.disconnectedSignal.value = true
        advanceUntilIdle()
        // The user taps Reconnect: THAT enters the numbered ladder. Every rung's dial fails
        // retryably, so the ladder walks its budget and the controller surrenders for real —
        // the give-up the maintainer is actually stranded in, manufactured by production code.
        vm.reconnect()
        advanceUntilIdle()

        // FIXTURE FIDELITY (G3): if we did not actually reach the maintainer's reported state,
        // every assertion below is vacuous. Hard-assert it; never assume it.
        assertTrue(
            "fixture must reach a genuine give-up through the production ladder — otherwise " +
                "this test proves nothing. status=${vm.connectionStatus.value} " +
                "dials=${connector.connectCount}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            "the give-up must follow REAL dial attempts, not an unattempted abort",
            connector.connectCount > 0,
        )
        return Fixture(vm, connector)
    }

    // ---- the load-bearing assertions: the wake DIALS ----

    @Test
    fun `a give-up with no wake signal dials nothing and stays honestly Failed`() = runTest(scheduler) {
        val f = bringToGiveUp()
        val dialsAtGiveUp = f.connector.connectCount

        // Drain all pending work: nothing may dial. A give-up that quietly keeps retrying is
        // the #1610 storm returning — the failure mode the wake must not reintroduce.
        advanceUntilIdle()
        runCurrent()

        assertEquals(
            "an unwoken give-up must dial NOTHING — retrying behind an honest Failed band is " +
                "the storm we just removed",
            dialsAtGiveUp,
            f.connector.connectCount,
        )
        assertTrue(
            "and it must still SAY it gave up, so the user keeps the working Tap Reconnect",
            f.vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
    }

    /**
     * THE STRAND QUESTION — the one that decides whether cutting the `Unreachable` auto-exit is
     * survivable (issue #1654 round two).
     *
     * #1656's argument for keeping an auto-wake, and the counter-argument for cutting it, BOTH
     * rest on the same unproven premise: *"an honest `Failed` band with a WORKING Tap Reconnect
     * beats a lying `Reconnecting…` that does no IO."* If Tap Reconnect does not dial from the
     * post-give-up `Unreachable`, that premise collapses and the user has **no recovery at
     * all** — strictly worse than either option.
     *
     * It is worth pinning precisely because round one measured **zero dials** routing an
     * automatic wake through `transportEffects.onManualReconnect()` — the SAME body this
     * button uses. That measurement is what cut Defect B, and it makes "the tap still works"
     * a claim that must be MEASURED, not assumed. (It turns out the wake's absorber was the
     * automatic path's entry conditions, not the manual body: a real user tap goes through
     * `reconnect()`, which `endEpisode()`s and re-Enters before dialling.)
     *
     * Asserts the CONNECTOR opened a new transport — the one observable a dead-end cannot fake.
     */
    @Test
    fun `Tap Reconnect actually dials from the post-give-up Unreachable`() = runTest(scheduler) {
        val f = bringToGiveUp()
        val dialsAtGiveUp = f.connector.connectCount

        // The controller must really be in the terminal give-up — the state whose recovery is
        // in question. If it is not, this test would prove nothing (G3).
        assertTrue(
            "fixture must be parked in a controller give-up; state=" +
                "${f.vm.connectionControllerStateForTest()}",
            f.vm.connectionControllerStateForTest() is
                com.pocketshell.core.connection.ConnectionState.Unreachable,
        )

        // The user taps the calm "Tap to reconnect" band (FailedConnectionRow -> onReconnect ->
        // viewModel.reconnect()) — the exact production body, not a test-only entrypoint.
        f.vm.reconnect()
        advanceUntilIdle()

        assertTrue(
            "THE STRAND: Tap Reconnect must open a NEW transport from the post-give-up " +
                "Unreachable. If it does not, the honest-Failed-band design leaves the user " +
                "with no recovery whatsoever and #1654 cannot ship with the Unreachable exit " +
                "cut. dialsAtGiveUp=$dialsAtGiveUp dialsAfterTap=${f.connector.connectCount}",
            f.connector.connectCount > dialsAtGiveUp,
        )
    }

    @Test
    fun `a network loss does not wake a give-up`() = runTest(scheduler) {
        // Only a RESTORE wakes. A loss is not evidence the host became reachable, and turning
        // every network event into a wake would rebuild the storm on the network callback
        // instead of the grace loop.
        val f = bringToGiveUp()
        val dialsAtGiveUp = f.connector.connectCount

        f.vm.onNetworkChanged(networkChange(TerminalNetworkChangeKind.NetworkLost))
        advanceUntilIdle()

        assertEquals(
            "a network LOSS must not wake a give-up",
            dialsAtGiveUp,
            f.connector.connectCount,
        )
        assertTrue(f.vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed)
    }
}

/** Alias so the fixture helper reads as an extension on the test scope. */
private typealias TestScopeAlias = kotlinx.coroutines.test.TestScope
