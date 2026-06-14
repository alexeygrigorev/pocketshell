package com.pocketshell.core.tmux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Issue #576 (P4): characterises the production idle-deadline
 * [CommandTimeoutGate.realTime] gate. The gate must:
 *   * fire only after [timeoutMs] of READER-side silence (busy ≠ dead);
 *   * re-arm whenever reader activity advances, so a command waiting behind a
 *     continuous `%output` backlog never self-inflicts a timeout;
 *   * NOT re-arm on local write completion — a blackholed link (zero reader
 *     bytes) must still fire so dead-peer detection is never masked.
 */
class CommandTimeoutGateIdleDeadlineTest {

    @Test
    fun `body result wins when response arrives before idle deadline`() = runBlocking {
        val activity = AtomicLong(System.nanoTime())
        val gate = CommandTimeoutGate.realTime { activity.get() }

        val result = withTimeout(2_000) {
            gate.run<String>(timeoutMs = 1_000) { checkpoint ->
                checkpoint.writeCompleted()
                "ok"
            }
        }

        assertEquals("ok", result)
    }

    @Test
    fun `fires when the channel is silent for the whole window`() = runBlocking {
        // Activity timestamp is fixed in the past, so the channel reads as
        // silent for longer than the deadline from the first tick.
        val silentSince = System.nanoTime()
        val gate = CommandTimeoutGate.realTime { silentSince }

        val response = CompletableDeferred<String>() // never completes

        val result = withTimeout(2_000) {
            gate.run<String>(timeoutMs = 100) { checkpoint ->
                checkpoint.writeCompleted()
                response.await()
            }
        }

        assertNull("a silent channel must trip the idle deadline", result)
    }

    @Test
    fun `re-arms while reader activity keeps advancing, then fires on silence`() = runBlocking {
        val activity = AtomicLong(System.nanoTime())
        val gate = CommandTimeoutGate.realTime { activity.get() }

        val response = CompletableDeferred<String>() // never completes

        // Keep "reader activity" advancing for well past the 200ms window, then
        // stop. The gate must NOT fire during the busy phase and MUST fire once
        // activity stalls for a full window.
        val pumpStop = CompletableDeferred<Unit>()
        val pump = launch {
            val busyUntil = System.nanoTime() + 600_000_000L // 600ms of "busy"
            while (System.nanoTime() < busyUntil) {
                activity.set(System.nanoTime())
                delay(20)
            }
            pumpStop.complete(Unit)
        }

        val firedNanos = AtomicLong(0)
        val started = System.nanoTime()
        val result = withTimeout(3_000) {
            gate.run<String>(timeoutMs = 200) { checkpoint ->
                checkpoint.writeCompleted()
                response.await()
            }
        }
        firedNanos.set(System.nanoTime())
        pump.join()

        assertNull("idle deadline must eventually fire once activity stalls", result)
        // It must have survived the entire ~600ms busy phase (would have fired
        // at ~200ms if it ignored reader activity).
        val elapsedMs = (firedNanos.get() - started) / 1_000_000L
        assertTrue(
            "gate fired at ${elapsedMs}ms — it must outlast the 600ms busy window",
            elapsedMs >= 600,
        )
        assertTrue("busy pump should have completed before the gate fired", pumpStop.isCompleted)
    }

    @Test
    fun `does NOT re-arm on local write completion alone (dead peer still fires)`() = runBlocking {
        // Activity (reader-side) frozen in the past: simulates a blackholed link
        // that delivers zero bytes. The body completes its WRITE (checkpoint)
        // but the reader never advances activity — the deadline must still fire.
        val silentSince = System.nanoTime()
        val gate = CommandTimeoutGate.realTime { silentSince }

        val response = CompletableDeferred<String>() // never completes
        var writeObserved = false

        val result = withTimeout(2_000) {
            gate.run<String>(timeoutMs = 150) { checkpoint ->
                checkpoint.writeCompleted()
                writeObserved = true
                response.await()
            }
        }

        assertTrue("write checkpoint should have run", writeObserved)
        assertNull("a completed write must NOT re-arm the idle deadline", result)
    }
}
