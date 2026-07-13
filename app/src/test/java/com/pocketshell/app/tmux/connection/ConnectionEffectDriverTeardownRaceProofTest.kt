package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionEvent
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.TmuxPort
import com.pocketshell.core.connection.TransportPort
import com.pocketshell.core.connection.TransportUpDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1545 (Fable race/timing audit finding R1, extends #904) — DETERMINISTIC,
 * environment-INDEPENDENT proof that a `Foreground` arriving while a POST-GRACE
 * TEARDOWN CLOSE is still in flight must fire EXACTLY ONE recovery writer, not two.
 *
 * ## The race (#780 model: inject the failing state synthetically, no `assumeTrue`)
 *
 * The App-level grace window (#450) elapses and starts the clean detach — the full
 * teardown runs under `NonCancellable`, which STASHES a `pendingReattach` (the
 * beyond-grace replay arm `replayPendingReattach` will dial fresh). While that close
 * is still running:
 *  - the transport lease is NOT yet evicted, so `transport.isWarm(host)` snapshots
 *    TRUE, and
 *  - the controller's own 90 s grace deadline (`DEFAULT_GRACE_MS`, stamped at
 *    Background) has NOT elapsed.
 * So a `Foreground` submitted here resolves in [ConnectionController.onForeground] to
 * `withinGrace = true` -> `Backgrounded -> Reattaching`. The [ConnectionEffectDriver]
 * observes that edge and — before #1545 — ALWAYS fired `foregroundReattachEffect`,
 * reseeding over the DYING `-CC` client. Meanwhile the beyond-grace lifecycle path
 * (`dispatchPostGraceForegroundArmIfPending`) sees `pendingReattach != null` and
 * `replayPendingReattach` dials a FRESH transport in parallel — TWO writers racing on
 * the one host.
 *
 * ## The fix (mirror of #904's Reconnecting-edge guard)
 *
 * #904 closed the SIBLING edge (Backgrounded -> Reconnecting): there the driver's
 * `foregroundReconnectEffect` and the lifecycle replay both call the same
 * `ForegroundReturnEffects`, and the duplicate is a no-op because the first consumed
 * `pendingReattach`. The Reattaching edge had NO such guard. #1545 adds the
 * [ConnectionEffectDriver.hasPendingReattach] predicate: when a `pendingReattach` is
 * outstanding the driver SUPPRESSES the reattach-edge reseed and lets the single
 * pending path own recovery. `pendingReattach == null` (every normal within-grace
 * reattach) is unchanged — the reseed still fires once.
 *
 * The four cases below cover the whole class (G2): the teardown-in-flight race WITHOUT
 * the gate (base-red, two writers), WITH the gate (fix-green, one writer), the normal
 * teardown-done within-grace reattach (single reseed, gate is inert), and the #904
 * Reconnecting edge (unaffected by the reattach gate — still fires its replay once).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionEffectDriverTeardownRaceProofTest {

    private class TestClock(var now: Long = 0L) : Clock {
        override fun nowMs(): Long = now
    }

    private val host = HostKey("alice@example.com:22/7")
    private val sessionA = SessionId("7/main")

    private class InertTmuxPort : TmuxPort {
        val disconnectedFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)
        override val disconnected: Flow<Boolean> = disconnectedFlow
    }

    /**
     * `warm` models the transport-lease snapshot the controller reads in
     * `onForeground`. During a post-grace teardown-in-flight the `NonCancellable`
     * close has NOT yet evicted the lease, so it is still `true` — the exact state
     * that makes the Foreground resolve to Reattaching (the racing edge).
     */
    private class InertTransportPort(var warm: Boolean) : TransportPort {
        val transportEventsFlow = MutableSharedFlow<TransportUpDown>(extraBufferCapacity = 16)
        override val transportEvents: Flow<TransportUpDown> = transportEventsFlow
        override fun isWarm(host: HostKey): Boolean = warm
    }

    /**
     * The collector runs on an [UnconfinedTestDispatcher] so each controller submit is
     * observed by the driver's edge collector SYNCHRONOUSLY inside the submit — the
     * production-faithful ordering where the driver's reattach effect fires as the
     * Foreground reduces (before the lifecycle replay dispatch runs).
     *
     * @param gatePending when true, the driver consults [pendingReplayStashed] via
     *   [ConnectionEffectDriver.hasPendingReattach] — the #1545 fix wiring. When false,
     *   the driver NEVER consults the pending state (the pre-#1545 driver that always
     *   reseeded) — the base-red characterization.
     */
    private inner class Harness(
        scope: CoroutineScope,
        val clock: TestClock,
        val transportPort: InertTransportPort,
        gatePending: Boolean,
    ) {
        val tmuxPort = InertTmuxPort()
        val controller = ConnectionController(clock = clock, transport = transportPort)

        /** Whether the App-grace teardown stashed a `pendingReattach` (writer #2's arm). */
        var pendingReplayStashed = false

        /** Writer #1 — the driver's reattach-edge reseed over the (dying) `-CC` client. */
        var reattachReseedCount = 0

        /** Writer #2 — the beyond-grace lifecycle replay (`replayPendingReattach`). */
        var pendingReplayCount = 0

        /** The #904 sibling edge effect (Backgrounded -> Reconnecting); NOT gated by #1545. */
        var reconnectEffectCount = 0

        val driver = ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            foregroundReattachEffect = { reattachReseedCount += 1 },
            hasPendingReattach = { if (gatePending) pendingReplayStashed else false },
            foregroundReconnectEffect = {
                reconnectEffectCount += 1
                // Mirror production: the controller's foreground edge dispatches
                // ForegroundReturnEffects, which replays (and CONSUMES) a stashed
                // pendingReattach. The #904 guard: the lifecycle duplicate is then a no-op.
                consumePendingReplay()
            },
        ).also { it.start() }

        /** Drive the controller to Live (cold path) so Background has a live source. */
        fun driveToLive() {
            controller.submit(ConnectionEvent.Enter(host, sessionA))
            controller.submit(ConnectionEvent.TransportLive)
            controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))
        }

        /**
         * Model the beyond-grace `onAppForegrounded` ordering: submit Foreground to the
         * controller (the driver observes the edge SYNCHRONOUSLY here and fires its
         * reattach/reconnect effect), THEN run the lifecycle replay-arm dispatch
         * (`dispatchPostGraceForegroundArmIfPending` -> `replayPendingReattach`) if a
         * pending arm is still stashed.
         */
        fun foreground() {
            controller.submit(ConnectionEvent.Foreground)
            // dispatchPostGraceForegroundArmIfPending(): replays iff still stashed.
            consumePendingReplay()
        }

        private fun consumePendingReplay() {
            if (pendingReplayStashed) {
                pendingReplayStashed = false
                pendingReplayCount += 1
            }
        }

        /** Total recovery writers that touched the host on the foreground return. */
        fun totalRecoveryWriters(): Int = reattachReseedCount + pendingReplayCount
    }

    private fun scope(): CoroutineScope = CoroutineScope(Job() + UnconfinedTestDispatcher())

    /**
     * BASE-RED (characterization): a `Foreground` during a post-grace teardown-in-flight
     * when the driver does NOT consult the outstanding `pendingReattach` (the pre-#1545
     * driver) fires TWO recovery writers — the reattach-edge reseed over the dying client
     * AND the pending replay dialing fresh. This is the race R1 reports.
     */
    @Test
    fun foregroundDuringTeardownInFlight_withoutPendingGate_firesTwoRecoveryWriters() = runTest {
        val scope = scope()
        val transportPort = InertTransportPort(warm = true)
        val h = Harness(scope, TestClock(), transportPort, gatePending = false)

        h.driveToLive()
        h.controller.submit(ConnectionEvent.Background) // -> Backgrounded (stamps 90 s deadline)

        // Post-grace teardown-in-flight: the App-grace teardown stashed a pendingReattach,
        // the NonCancellable close has NOT yet evicted the lease (warm stays true), and the
        // controller's 90 s deadline has NOT elapsed (now well under DEFAULT_GRACE_MS).
        h.pendingReplayStashed = true
        transportPort.warm = true
        h.clock.now = 1_000L
        h.foreground() // Backgrounded -> Reattaching (warm + within grace)

        assertTrue(
            "sanity: warm-lease snapshot + within-grace resolves Foreground to Reattaching",
            h.controller.state.value is ConnectionState.Reattaching,
        )
        assertEquals(
            "BASE-RED: without the pending gate the driver reseeds over the dying client",
            1,
            h.reattachReseedCount,
        )
        assertEquals(
            "BASE-RED: the pending path ALSO replays -> a second racing writer",
            1,
            h.pendingReplayCount,
        )
        assertEquals(
            "BASE-RED: TWO recovery writers race on the one host (the R1 bug)",
            2,
            h.totalRecoveryWriters(),
        )
        scope.cancel()
    }

    /**
     * FIX-GREEN: the SAME post-grace teardown-in-flight foreground, with the driver
     * consulting the outstanding `pendingReattach` (the #1545 gate). The reattach-edge
     * reseed is SUPPRESSED, the single pending path owns recovery, and EXACTLY ONE
     * recovery writer touches the host.
     */
    @Test
    fun foregroundDuringTeardownInFlight_withPendingGate_firesExactlyOneRecoveryWriter() = runTest {
        val scope = scope()
        val transportPort = InertTransportPort(warm = true)
        val h = Harness(scope, TestClock(), transportPort, gatePending = true)

        h.driveToLive()
        h.controller.submit(ConnectionEvent.Background)

        h.pendingReplayStashed = true
        transportPort.warm = true
        h.clock.now = 1_000L
        h.foreground() // Backgrounded -> Reattaching, but gated on pendingReattach

        assertTrue(
            "sanity: still resolves to Reattaching — the fix is in the driver edge, not the reducer",
            h.controller.state.value is ConnectionState.Reattaching,
        )
        assertEquals(
            "FIX-GREEN: the reattach-edge reseed is SUPPRESSED while a pendingReattach is outstanding",
            0,
            h.reattachReseedCount,
        )
        assertEquals(
            "FIX-GREEN: the single pending path replays exactly once",
            1,
            h.pendingReplayCount,
        )
        assertEquals(
            "FIX-GREEN: EXACTLY ONE recovery writer touches the host",
            1,
            h.totalRecoveryWriters(),
        )
        assertTrue(
            "the suppression is recorded as an observation for diagnostics",
            h.driver.observations.value.any {
                it is ConnectionEffectDriver.Observation.ReattachReseedSuppressedPendingReplay
            },
        )
        scope.cancel()
    }

    /**
     * CLASS COVERAGE — the NORMAL within-grace reattach (foreground AFTER any teardown is
     * done / never ran): NOTHING is pending, so the gate is inert and the driver reseeds
     * exactly once. This pins that #1545 does NOT break the healthy single-reseed path.
     */
    @Test
    fun foregroundNormalWithinGrace_noPending_firesSingleReseed() = runTest {
        val scope = scope()
        val transportPort = InertTransportPort(warm = true)
        val h = Harness(scope, TestClock(), transportPort, gatePending = true)

        h.driveToLive()
        h.controller.submit(ConnectionEvent.Background)

        // Nothing pending — the within-grace foreground return keeps the warm lease.
        h.pendingReplayStashed = false
        transportPort.warm = true
        h.clock.now = 1_000L
        h.foreground() // Backgrounded -> Reattaching

        assertTrue(h.controller.state.value is ConnectionState.Reattaching)
        assertEquals(
            "the normal within-grace reattach still fires its single reseed (gate inert)",
            1,
            h.reattachReseedCount,
        )
        assertEquals("no pending replay when nothing was stashed", 0, h.pendingReplayCount)
        assertEquals("exactly one recovery writer on the normal reattach", 1, h.totalRecoveryWriters())
        scope.cancel()
    }

    /**
     * CLASS COVERAGE — the #904 sibling edge is PRESERVED. A beyond-grace foreground
     * (lease evicted -> warm false) resolves to Backgrounded -> Reconnecting, NOT
     * Reattaching. The reattach gate does not touch this edge: `foregroundReconnectEffect`
     * still fires once (replaying the pending arm), and the lifecycle duplicate is a no-op
     * because the first consumed the stash — the #904 guard still holds non-vacuously.
     */
    @Test
    fun beyondGraceForeground_reconnectEdge_stillReplaysOnce_904Preserved() = runTest {
        val scope = scope()
        val transportPort = InertTransportPort(warm = true)
        val h = Harness(scope, TestClock(), transportPort, gatePending = true)

        h.driveToLive()
        h.controller.submit(ConnectionEvent.Background)

        // Beyond grace: the teardown ran and EVICTED the lease -> warm false. A stashed
        // pendingReattach is present (the replay arm).
        h.pendingReplayStashed = true
        transportPort.warm = false
        h.clock.now = ConnectionController.DEFAULT_GRACE_MS + 1L
        h.foreground() // Backgrounded -> Reconnecting

        assertTrue(
            "beyond grace resolves to Reconnecting, not Reattaching",
            h.controller.state.value is ConnectionState.Reconnecting,
        )
        assertEquals(
            "the reattach-edge reseed never fires on the Reconnecting edge",
            0,
            h.reattachReseedCount,
        )
        assertEquals(
            "the #904 Reconnecting edge still fires its foreground reconnect effect once",
            1,
            h.reconnectEffectCount,
        )
        assertEquals(
            "#904 guard holds: the driver edge consumed the pending arm; the lifecycle " +
                "duplicate is a no-op -> exactly one replay",
            1,
            h.pendingReplayCount,
        )
        assertEquals("exactly one recovery writer on the beyond-grace edge", 1, h.totalRecoveryWriters())
        scope.cancel()
    }
}
