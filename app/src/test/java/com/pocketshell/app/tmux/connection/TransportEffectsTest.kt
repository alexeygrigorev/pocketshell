package com.pocketshell.app.tmux.connection

import kotlinx.coroutines.Job
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
}
