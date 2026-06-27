package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.ConnectionEvent
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.LivenessProbe
import com.pocketshell.core.connection.SessionId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * EPIC #792 Slice C — focused unit pins for [TransportEffects], the single RECONNECT-LADDER
 * IO dispatcher (the #822 wedge surface).
 *
 * These prove the single-entrypoint contract the hard-cut depends on: every reconnect
 * trigger (auto-ladder + manual/send) routes to EXACTLY its one [TransportEffects.ReconnectIo]
 * body, once, with no cross-talk — so the former inline `scheduleAutoReconnect` /
 * `startReconnectForSend` direct calls can all be deleted (no dual-write, D28(4) single
 * active path), and the future #823 affordance has ONE entrypoint to call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportEffectsTest {

    private class RecordingReconnectIo(
        private val manualJob: Job? = null,
    ) : TransportEffects.ReconnectIo {
        var auto = 0
        var manual = 0
        var lastAutoBodyRan = false

        override fun runAutoReconnectLadder(body: () -> Unit) {
            auto += 1
            // The owner must INVOKE the supplied body (the VM's scheduleAutoReconnectBody
            // closed over the caller's args) — otherwise the ladder never runs.
            body()
        }

        override fun runManualReconnect(): TransportEffects.ManualReconnectResult {
            manual += 1
            return TransportEffects.ManualReconnectResult(manualJob)
        }

        fun markAutoBodyRan() {
            lastAutoBodyRan = true
        }
    }

    @Test
    fun onAutoReconnect_dispatchesOnlyTheAutoLadder_andRunsTheBody() {
        val io = RecordingReconnectIo()
        TransportEffects(io).onAutoReconnect { io.markAutoBodyRan() }

        assertEquals("auto ladder fired exactly once", 1, io.auto)
        assertEquals("no manual reconnect", 0, io.manual)
        assertEquals("the supplied auto body must run", true, io.lastAutoBodyRan)
    }

    @Test
    fun onManualReconnect_dispatchesOnlyTheManualReconnect_andReturnsItsJob() {
        val job = Job()
        val io = RecordingReconnectIo(manualJob = job)
        val result = TransportEffects(io).onManualReconnect()

        assertEquals("no auto ladder", 0, io.auto)
        assertEquals("manual reconnect fired exactly once", 1, io.manual)
        assertSame("the connect job is passed back for the send path to join", job, result.job)
    }

    @Test
    fun onManualReconnect_nullJob_whenThereIsNoTargetToReconnectTo() {
        val io = RecordingReconnectIo(manualJob = null)
        val result = TransportEffects(io).onManualReconnect()

        assertEquals(1, io.manual)
        assertNull("a null job signals 'no target to reconnect to'", result.job)
    }

    @Test
    fun repeatedTriggers_eachDispatchExactlyOnceToItsOwnBody() {
        val io = RecordingReconnectIo()
        val effects = TransportEffects(io)

        // A representative passive-drop → manual-tap → network-handoff sequence: each
        // trigger maps to its own body, the inline twins are gone so there is exactly
        // one invocation per call (single reconnect entrypoint).
        effects.onAutoReconnect { }
        effects.onManualReconnect()
        effects.onAutoReconnect { }

        assertEquals(2, io.auto)
        assertEquals(1, io.manual)
    }

    @Test
    fun livenessProbeDeclaredDropWalksControllerAndDispatchesAutoReconnectOnly() {
        runTest(StandardTestDispatcher()) {
            val host = HostKey("alice@example.com:22/7")
            val target = SessionId("7/main")
            val manager = ConnectionManager(warmSnapshot = { true })
            val io = RecordingReconnectIo()
            val effects = TransportEffects(io)

            manager.enter(host, target)
            manager.observeSeedLanded(host, target, paneId = "%0")
            assertEquals(ConnectionState.Live(host, target), manager.state)

            val probe = LivenessProbe(
                io = object : LivenessProbe.ProbeIo {
                    override fun shouldProbe(): Boolean = manager.state is ConnectionState.Live
                    override suspend fun probe(): Boolean = false
                    override fun onProbeFailed(consecutiveFailures: Int) {
                        manager.submit(
                            ConnectionEvent.TransportDropped("liveness_probe_silent_drop"),
                        )
                        effects.onAutoReconnect { io.markAutoBodyRan() }
                    }
                },
                intervalMs = 100,
                perProbeTimeoutMs = 1_000,
                failureThreshold = 2,
            )

            probe.start(this)
            advanceTimeBy(250)
            runCurrent()
            probe.stop()

            assertEquals(
                "a probe-declared silent drop must move the controller onto the " +
                    "silent recovery path immediately",
                ConnectionState.Reattaching(host, target),
                manager.state,
            )
            assertEquals("probe recovery must dispatch the auto ladder exactly once", 1, io.auto)
            assertEquals("probe recovery must not use the manual reconnect entrypoint", 0, io.manual)
            assertEquals("the auto ladder body must run", true, io.lastAutoBodyRan)
        }
    }
}
