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
 *
 * These idle-gate cases pass `absoluteCeilingMs = null` so they exercise the
 * idle gate IN ISOLATION (no #886 absolute ceiling interfering). The #886
 * absolute-ceiling behaviour — and crucially the streaming-channel case where
 * the idle gate is defeated and ONLY the ceiling can fire — is characterised in
 * [CommandTimeoutGateAbsoluteCeilingTest].
 */
class CommandTimeoutGateIdleDeadlineTest {

    @Test
    fun `body result wins when response arrives before idle deadline`() = runBlocking {
        val activity = AtomicLong(System.nanoTime())
        val gate = CommandTimeoutGate.realTime(readerActivityNanos = { activity.get() }, absoluteCeilingMs = null)

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
        val gate = CommandTimeoutGate.realTime(readerActivityNanos = { silentSince }, absoluteCeilingMs = null)

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
        val gate = CommandTimeoutGate.realTime(readerActivityNanos = { activity.get() }, absoluteCeilingMs = null)

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
    fun `pre-command silence does NOT shorten the command window (issue 794)`() = runBlocking {
        // #794: a steady foreground hold leaves the control channel genuinely
        // idle for far longer than the timeout window — the reader has parsed
        // no events because the shell is quiet, NOT because the link is dead.
        // The last-reader-activity timestamp is therefore deep in the past.
        // A command issued now must still get its FULL window before the
        // deadline can fire; the prior silence must not be charged against it.
        // The old gate fired instantly here, escalating a read-only poll to a
        // FATAL transport teardown every ~timeoutMs (the ~11s flap).
        val staleActivity = System.nanoTime() - 10_000_000_000L // 10s in the past
        val gate = CommandTimeoutGate.realTime(readerActivityNanos = { staleActivity }, absoluteCeilingMs = null)

        // The response lands quickly (50ms) — a healthy channel that simply had
        // not spoken for a while. It MUST win because the deadline window
        // (300ms) runs from the command start, not from the stale activity.
        val response = CompletableDeferred<String>()
        val responder = launch {
            delay(50)
            response.complete("ok")
        }

        val result = withTimeout(2_000) {
            gate.run<String>(timeoutMs = 300) { checkpoint ->
                checkpoint.writeCompleted()
                response.await()
            }
        }
        responder.join()

        assertEquals(
            "a quick reply on a long-idle-but-healthy channel must win — the " +
                "gate must not count pre-command silence against the command",
            "ok",
            result,
        )
    }

    @Test
    fun `silent command on a long-idle channel fires after a full window from dispatch (issue 794)`() = runBlocking {
        // Even when pre-command silence is not charged, a command that itself
        // never gets a reply must STILL fire — after a full window measured
        // from its own dispatch — so dead-peer / wedged-channel detection is
        // preserved and the gate never hangs forever.
        val staleActivity = System.nanoTime() - 10_000_000_000L // 10s in the past
        val gate = CommandTimeoutGate.realTime(readerActivityNanos = { staleActivity }, absoluteCeilingMs = null)

        val response = CompletableDeferred<String>() // never completes

        val started = System.nanoTime()
        val result = withTimeout(3_000) {
            gate.run<String>(timeoutMs = 200) { checkpoint ->
                checkpoint.writeCompleted()
                response.await()
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertNull("a never-answered command must still trip the idle deadline", result)
        assertTrue(
            "the deadline must fire only after ~a full window from dispatch " +
                "(fired at ${elapsedMs}ms), not instantly from stale pre-command silence",
            elapsedMs >= 180,
        )
    }

    @Test
    fun `does NOT re-arm on local write completion alone (dead peer still fires)`() = runBlocking {
        // Activity (reader-side) frozen in the past: simulates a blackholed link
        // that delivers zero bytes. The body completes its WRITE (checkpoint)
        // but the reader never advances activity — the deadline must still fire.
        val silentSince = System.nanoTime()
        val gate = CommandTimeoutGate.realTime(readerActivityNanos = { silentSince }, absoluteCeilingMs = null)

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
