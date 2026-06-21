package com.pocketshell.core.tmux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Issue #886: the ABSOLUTE wall-clock ceiling on a single control-mode command,
 * applied ALONGSIDE the per-command idle gate.
 *
 * The reported defect: a connected agent/Codex (z.ai) session is stuck on
 * "Attaching…" forever because the attach `capture-pane` seed never lands while
 * the agent pane streams `%output` continuously. The idle gate
 * ([CommandTimeoutGate.realTime]'s reader-activity re-arm) is, by design,
 * DEFEATED by that continuous stream — `readerActivityNanos` advances on every
 * tick, so the idle deadline re-arms forever and never fires even though THIS
 * command's own reply is wedged behind the redraw backlog and will never land.
 * Same class as the #470/#835 enumeration stall.
 *
 * The fix is the second, independent absolute ceiling that fires after a fixed
 * wall-clock window from the command's own dispatch, regardless of reader
 * activity. The load-bearing case is [`streaming channel defeats the idle gate
 * but the absolute ceiling still fires`]: with `absoluteCeilingMs = null` (idle
 * gate alone, the pre-#886 behaviour) it would hang forever — proving the idle
 * gate is insufficient — and with the ceiling set it is bounded.
 */
class CommandTimeoutGateAbsoluteCeilingTest {

    @Test
    fun `streaming channel defeats the idle gate but the absolute ceiling still fires`() =
        runBlocking {
            // Reader activity advances continuously — the agent pane streaming
            // %output. This is exactly what re-arms (and so defeats) the idle gate.
            val activity = AtomicLong(System.nanoTime())
            val streamStop = CompletableDeferred<Unit>()
            val streamer = launch {
                // Keep "reader activity" advancing for well past the ceiling so
                // the idle deadline can never fire on its own.
                val busyUntil = System.nanoTime() + 1_500_000_000L // 1.5s of stream
                while (System.nanoTime() < busyUntil) {
                    activity.set(System.nanoTime())
                    delay(10)
                }
                streamStop.complete(Unit)
            }

            // Short idle window (100ms) that the streaming activity re-arms
            // forever; a generous-but-bounded absolute ceiling (400ms).
            val gate = CommandTimeoutGate.realTime(
                readerActivityNanos = { activity.get() },
                absoluteCeilingMs = 400L,
            )

            val response = CompletableDeferred<String>() // never completes (wedged seed)

            val started = System.nanoTime()
            // The outer withTimeout is the test harness backstop: if the ceiling
            // were absent (the pre-#886 idle-gate-only behaviour) the command
            // would hang past 1.5s and this would itself throw — i.e. RED.
            val result = withTimeout(1_200) {
                gate.run<String>(timeoutMs = 100) { checkpoint ->
                    checkpoint.writeCompleted()
                    response.await()
                }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            // Snapshot whether the stream was STILL busy at the instant the
            // ceiling fired — captured BEFORE the join (the stream runs to 1.5s,
            // far past the 400ms ceiling, so it must still be busy here).
            val streamStillBusyWhenCeilingFired = !streamStop.isCompleted
            streamer.join()

            assertNull(
                "a wedged command on a continuously-streaming channel must be " +
                    "bounded by the absolute ceiling even though the idle gate " +
                    "never fires",
                result,
            )
            // It must have survived the 100ms idle window (the idle gate would
            // have fired there if the stream did not re-arm it) and fired around
            // the 400ms ceiling, NOT at the idle window.
            assertTrue(
                "the absolute ceiling fired at ${elapsedMs}ms — it must outlast " +
                    "the 100ms idle window (proving the idle gate was defeated) " +
                    "and fire near the 400ms ceiling",
                elapsedMs >= 350,
            )
            assertTrue(
                "the stream should still have been busy when the ceiling fired " +
                    "(proving the idle gate could not have fired)",
                streamStillBusyWhenCeilingFired,
            )
        }

    @Test
    fun `idle gate alone (no ceiling) hangs forever on a streaming channel - the bug`() =
        runBlocking {
            // The RED counterpart: with the absolute ceiling DISABLED (the
            // pre-#886 production behaviour), the same wedged command on a
            // streaming channel never returns — the harness withTimeout has to
            // cancel it. This is the #886 infinite "Attaching…".
            val activity = AtomicLong(System.nanoTime())
            val streamer = launch {
                while (true) {
                    activity.set(System.nanoTime())
                    delay(10)
                }
            }
            val gate = CommandTimeoutGate.realTime(
                readerActivityNanos = { activity.get() },
                absoluteCeilingMs = null, // idle gate only — the old behaviour
            )
            val response = CompletableDeferred<String>() // never completes

            var timedOut = false
            try {
                withTimeout(600) {
                    gate.run<String>(timeoutMs = 100) { checkpoint ->
                        checkpoint.writeCompleted()
                        response.await()
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                timedOut = true
            }
            streamer.cancel()

            assertTrue(
                "with the idle gate alone, a wedged command on a streaming " +
                    "channel must hang (the harness has to cancel it) — this is " +
                    "the unbounded #886 behaviour the absolute ceiling fixes",
                timedOut,
            )
        }

    @Test
    fun `a healthy quick reply wins well before the ceiling`() = runBlocking {
        // The ceiling must never preempt a healthy command — it answers in ms,
        // far inside the generous ceiling.
        val activity = AtomicLong(System.nanoTime())
        val gate = CommandTimeoutGate.realTime(
            readerActivityNanos = { activity.get() },
            absoluteCeilingMs = 30_000L, // production-sized ceiling
        )
        val response = CompletableDeferred<String>()
        val responder = launch {
            delay(20)
            response.complete("ok")
        }

        val result = withTimeout(2_000) {
            gate.run<String>(timeoutMs = 10_000) { checkpoint ->
                checkpoint.writeCompleted()
                response.await()
            }
        }
        responder.join()

        assertEquals("a healthy quick reply must win, the ceiling never preempts it", "ok", result)
    }

    @Test
    fun `a busy-but-alive reply within the ceiling still wins`() = runBlocking {
        // #576/#794 regression guard: a busy-but-alive redraw backlog that
        // delays the reply but answers WITHIN the ceiling must still win — the
        // ceiling is generous precisely so it never tears down a healthy busy
        // session. Reader activity keeps advancing (busy), reply lands at 200ms,
        // ceiling is 800ms.
        val activity = AtomicLong(System.nanoTime())
        val streamer = launch {
            while (true) {
                activity.set(System.nanoTime())
                delay(10)
            }
        }
        val gate = CommandTimeoutGate.realTime(
            readerActivityNanos = { activity.get() },
            absoluteCeilingMs = 800L,
        )
        val response = CompletableDeferred<String>()
        val responder = launch {
            delay(200)
            response.complete("late-but-alive")
        }

        val result = withTimeout(2_000) {
            gate.run<String>(timeoutMs = 50) { checkpoint ->
                checkpoint.writeCompleted()
                response.await()
            }
        }
        responder.join()
        streamer.cancel()

        assertNotNull("a busy-but-alive reply within the ceiling must win", result)
        assertEquals("late-but-alive", result)
    }
}
