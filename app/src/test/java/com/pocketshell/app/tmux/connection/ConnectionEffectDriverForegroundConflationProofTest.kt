package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionEvent
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.TmuxPort
import com.pocketshell.core.connection.TransportPort
import com.pocketshell.core.connection.TransportUpDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #743 — DETERMINISTIC, environment-INDEPENDENT proof of the conflation race
 * the journey de-flake in [com.pocketshell.app.proof.BackgroundGraceReconnectE2eTest]
 * targets, and of why the de-flake's foreground-ordering gate is the fix.
 *
 * ## Why this JVM proof exists (the #780 model: inject the failure deterministically)
 *
 * `quickAppSwitch…` flaked on a CONTENDED swiftshader box: the post-grace branch
 * foregrounds the instant the remote tmux client count hits 0, while the local
 * detach + the [ConnectionEffectDriver]'s collector resuming on `Backgrounded` are
 * still in flight. On an IDLE box the collector resumes between the two submits, so
 * the flake cannot be reproduced on demand there; on the contended box the only
 * trigger is real CPU contention from sibling agents that crashes base AND fix
 * indiscriminately — so a clean on-device base-RED is not obtainable.
 *
 * This test reproduces the EXACT mechanism deterministically with a controllable
 * coroutine dispatcher (no emulator, no contention), and is gated for free in the
 * Unit job (G9).
 *
 * ## The mechanism (verified in [ConnectionEffectDriver.collectStateTransitions])
 *
 * The driver fires `foregroundReconnectEffect` (→ the VM's `foreground_reattach`)
 * ONLY on the controller edge `current is Reconnecting && previous is Backgrounded`,
 * by collecting `controller.state` — a CONFLATING `StateFlow`. So the edge survives
 * only if the driver's collector resumes on `Backgrounded` BEFORE `Foreground` is
 * submitted. With a [StandardTestDispatcher] the collector does NOT run between two
 * synchronous submits, exactly modelling the contended box where the collector
 * hasn't resumed yet:
 *
 *  - [conflatedBackgroundForeground_dropsForegroundReconnectEffect] (BASE-RED):
 *    submit `Background` then `Foreground` back-to-back with NO scheduler turn
 *    between them → the conflating StateFlow only ever surfaces `Reconnecting` to
 *    the collector → `previous` was never `Backgrounded` → `foregroundReconnectEffect`
 *    fires ZERO times. This is the journey's headline failure
 *    (`timed out waiting for diagnostic 'foreground_reattach'`).
 *  - [drainingBackgroundedBeforeForeground_firesForegroundReconnectEffect] (FIX-GREEN):
 *    let the scheduler drain so the collector OBSERVES `Backgrounded` before
 *    `Foreground` is submitted (the production-faithful ordering the de-flake's
 *    `waitForPostGraceLocalDetachSettled` restores by gating the foreground on the
 *    local `-CC` detach actually completing + an idle pump) → the
 *    `Backgrounded -> Reconnecting` edge IS observed → `foregroundReconnectEffect`
 *    fires exactly ONCE.
 *
 * Same controller, same submits, same effect wiring — only the ordering between the
 * two submits differs. That is the base-RED → fix-GREEN the de-flake rests on,
 * captured deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionEffectDriverForegroundConflationProofTest {

    private class TestClock(var now: Long = 0L) : Clock {
        override fun nowMs(): Long = now
    }

    private val host = HostKey("alice@example.com:22/7")
    private val sessionA = SessionId("7/main")

    private class InertTmuxPort : TmuxPort {
        val disconnectedFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)
        override val disconnected: Flow<Boolean> = disconnectedFlow
    }

    private class InertTransportPort(private val warm: Boolean) : TransportPort {
        val transportEventsFlow = MutableSharedFlow<TransportUpDown>(extraBufferCapacity = 16)
        override val transportEvents: Flow<TransportUpDown> = transportEventsFlow
        override fun isWarm(host: HostKey): Boolean = warm
    }

    /**
     * A harness whose driver collector runs on a [StandardTestDispatcher] — it
     * resumes ONLY when the scheduler is advanced, so we control exactly whether the
     * collector observes `Backgrounded` before `Foreground` is submitted.
     *
     * `warm = false` so the beyond-grace foreground resolves to `Reconnecting` (the
     * edge under test), matching the journey's post-grace branch (App-grace teardown
     * evicted the warm lease).
     */
    private class Harness(
        scope: CoroutineScope,
        val clock: TestClock,
        private val host: HostKey,
        private val sessionA: SessionId,
    ) {
        val tmuxPort = InertTmuxPort()
        val transportPort = InertTransportPort(warm = false)
        val controller = ConnectionController(clock = clock, transport = transportPort)
        var foregroundReconnectEffectCount = 0
        val driver = ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            foregroundReconnectEffect = { foregroundReconnectEffectCount += 1 },
        ).also { it.start() }

        /** Drive the controller to Live (cold path) so Background has a live source. */
        fun driveToLive() {
            controller.submit(ConnectionEvent.Enter(host, sessionA))
            controller.submit(ConnectionEvent.TransportLive)
            controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))
        }
    }

    /**
     * BASE-RED: `Background` then `Foreground` submitted back-to-back with NO
     * scheduler turn between them. The collector (StandardTestDispatcher) has not
     * resumed, so the conflating `controller.state` collapses `Backgrounded` —
     * the collector only ever sees `Reconnecting`, never the `Backgrounded ->
     * Reconnecting` edge, and `foregroundReconnectEffect` fires ZERO times. This is
     * the deterministic synthetic of the journey's
     * `timed out waiting for diagnostic 'foreground_reattach'`.
     */
    @Test
    fun conflatedBackgroundForeground_dropsForegroundReconnectEffect() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val h = Harness(scope, TestClock(), host, sessionA)

        h.driveToLive()
        h.controller.submit(ConnectionEvent.Background)
        // No advanceUntilIdle() here: the driver's collector has NOT resumed, so it
        // has not observed Backgrounded yet — exactly the contended-box race.
        h.clock.now = ConnectionController.DEFAULT_GRACE_MS + 1L
        h.controller.submit(ConnectionEvent.Foreground) // Backgrounded -> Reconnecting (conflated)

        // Now let the collector drain. Because the StateFlow conflated, it never
        // observed `Backgrounded`, so the edge effect never fired.
        advanceUntilIdle()

        assertEquals(
            "BASE-RED expected: when Foreground is submitted before the driver's " +
                "collector resumes on Backgrounded, the conflating StateFlow drops the " +
                "Backgrounded -> Reconnecting edge and foregroundReconnectEffect must NOT " +
                "fire (this is the journey's missing 'foreground_reattach')",
            0,
            h.foregroundReconnectEffectCount,
        )
        // Sanity: the controller's terminal state IS Reconnecting — the controller
        // reduced correctly; only the DRIVER's edge observation was lost.
        assertTrue(
            "controller must still have reduced to Reconnecting — the conflation is in " +
                "the driver's edge OBSERVATION, not the controller reducer",
            h.controller.state.value is com.pocketshell.core.connection.ConnectionState.Reconnecting,
        )
        scope.cancel()
    }

    /**
     * FIX-GREEN: let the collector OBSERVE `Backgrounded` before `Foreground` is
     * submitted (the production-faithful ordering the de-flake's
     * `waitForPostGraceLocalDetachSettled` restores by gating the foreground on the
     * real local-detach signal). The `Backgrounded -> Reconnecting` edge is then
     * observed and `foregroundReconnectEffect` fires exactly once.
     */
    @Test
    fun drainingBackgroundedBeforeForeground_firesForegroundReconnectEffect() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val h = Harness(scope, TestClock(), host, sessionA)

        h.driveToLive()
        h.controller.submit(ConnectionEvent.Background)
        // The fix's ordering: drain so the driver's collector OBSERVES Backgrounded
        // (sets previous = Backgrounded) BEFORE the foreground is submitted.
        advanceUntilIdle()

        h.clock.now = ConnectionController.DEFAULT_GRACE_MS + 1L
        h.controller.submit(ConnectionEvent.Foreground) // Backgrounded -> Reconnecting (observed)
        advanceUntilIdle()

        assertEquals(
            "FIX-GREEN expected: with the collector having observed Backgrounded first, " +
                "the Backgrounded -> Reconnecting edge fires foregroundReconnectEffect once " +
                "(the journey's post-grace 'foreground_reattach')",
            1,
            h.foregroundReconnectEffectCount,
        )
        scope.cancel()
    }
}
