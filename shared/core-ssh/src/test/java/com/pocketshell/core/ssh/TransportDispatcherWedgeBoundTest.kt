package com.pocketshell.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reproduce-first (issue #937 / D33 / G10) for the transport-freeze root
 * (#935 S4-1): WITHOUT a per-op ceiling, ONE wedged sshj write holds the
 * dispatcher's mutex forever, so every other write on the connection freezes
 * and the single dispatch thread is parked unreclaimably.
 *
 * Each test injects a synthetic wedged op (a body that blocks on a latch — the
 * half-open-socket-write stand-in) and asserts the SECOND op still makes
 * progress and the wedged thread is reclaimed. On the UNFIXED dispatcher these
 * hang (the second op never returns, the thread is parked) — the whole suite
 * SIGTERMs. With the per-op timeout + `runInterruptible` they pass.
 *
 * Every launched wedge job is JOINED before the test returns and its latch is
 * counted down first, so no daemon dispatch thread is left parked to throw an
 * uncaught exception into a sibling test's fork.
 *
 * Pure JVM, Docker-free — runs in the per-push Unit gate via `./gradlew test`.
 */
class TransportDispatcherWedgeBoundTest {

    /**
     * A wedged op FAILS THAT op (TransportOpTimeoutException) without freezing
     * the whole connection — a second op submitted concurrently still runs.
     */
    @Test
    fun `wedged op fails that op and does not freeze the connection`() = runBlocking<Unit> {
        // Short per-op ceiling so the test is fast; the production default is
        // 8s. Same code path either way.
        val dispatcher = TransportDispatcher(perOpTimeoutMs = 300L)
        val neverReleased = CountDownLatch(1)

        // Op 1: wedged (simulates a half-open-link blocking socket write). The
        // per-op ceiling interrupts it; `await()` responds to Thread.interrupt.
        val wedged = async(Dispatchers.IO) {
            runCatching { dispatcher.run { neverReleased.await() } }
        }
        Thread.sleep(50) // let op 1 acquire the mutex first

        // Op 2: a healthy write. On a FROZEN connection it never runs.
        val secondRan = AtomicBoolean(false)
        val secondResult = withTimeoutOrNull(3_000L) {
            dispatcher.run { secondRan.set(true) }
            true
        }

        assertTrue(
            "the second op must still run — a single wedged op must NOT freeze " +
                "the whole connection's writes (this HANGS on the unbounded base)",
            secondRan.get(),
        )
        assertNotNull("the second op should have completed within budget", secondResult)

        // The wedged op itself must have FAILED (bounded) rather than hung.
        val wedgedResult = wedged.await()
        assertTrue(
            "the wedged op must surface a TransportOpTimeoutException",
            wedgedResult.exceptionOrNull() is TransportOpTimeoutException,
        )

        neverReleased.countDown()
        dispatcher.closeAndAwaitDrain { }
    }

    /**
     * The dispatch thread is RECLAIMED after a wedge — `runInterruptible`
     * interrupts the blocking body, so the next op runs on a live thread.
     */
    @Test
    fun `dispatch thread is reclaimed after a wedged op so later ops run`() = runBlocking<Unit> {
        val dispatcher = TransportDispatcher(perOpTimeoutMs = 300L)
        val wasInterrupted = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        val wedged = async(Dispatchers.IO) {
            runCatching {
                dispatcher.run {
                    try {
                        latch.await()
                    } catch (e: InterruptedException) {
                        wasInterrupted.set(true)
                        throw e
                    }
                }
            }
        }
        Thread.sleep(50)

        // A later op runs only if the wedged thread was reclaimed.
        val laterRan = withTimeoutOrNull(3_000L) {
            dispatcher.run { 42 }
        }

        assertTrue(
            "the wedged blocking body must be INTERRUPTED by the per-op ceiling " +
                "(runInterruptible) so its thread is reclaimed — HANGS on base",
            wasInterrupted.get(),
        )
        assertNotNull(
            "a later op must run on the reclaimed dispatch thread (HANGS on base)",
            laterRan,
        )

        latch.countDown()
        wedged.await() // drain the wedge job before the test ends
        dispatcher.closeAndAwaitDrain { }
    }

    /**
     * A wedged DISCONNECT during teardown does not freeze `closeAndAwaitDrain`
     * — the final op is bounded too, so teardown always completes and the
     * caller's own timeout (TmuxSessionViewModel/lease release) makes progress.
     */
    @Test
    fun `wedged disconnect does not freeze teardown`() = runBlocking<Unit> {
        val dispatcher = TransportDispatcher(perOpTimeoutMs = 300L)
        val latch = CountDownLatch(1)

        val drained = withTimeoutOrNull(3_000L) {
            dispatcher.closeAndAwaitDrain {
                // A disconnect socket write that wedges on a half-open link.
                latch.await()
            }
            true
        }

        assertNotNull(
            "closeAndAwaitDrain must complete even when disconnect() wedges " +
                "(the final op is bounded + interruptible) — HANGS on base",
            drained,
        )
        assertTrue("dispatcher should be marked closed", dispatcher.isClosed)

        // After a bounded teardown, no new op is accepted (transport gone).
        val rejected = AtomicBoolean(false)
        try {
            dispatcher.run { }
        } catch (e: SshException) {
            rejected.set(true)
        }
        assertTrue("ops after a wedged-but-bounded teardown are rejected", rejected.get())

        latch.countDown()
    }

    /**
     * Class coverage: a HEALTHY op well under the ceiling is unaffected — the
     * bound only trips the pathological wedge, never a normal slow write.
     */
    @Test
    fun `healthy op under the ceiling completes normally`() = runBlocking<Unit> {
        val dispatcher = TransportDispatcher(perOpTimeoutMs = 1_000L)
        val ran = AtomicBoolean(false)
        dispatcher.run {
            Thread.sleep(100) // well under the 1s ceiling
            ran.set(true)
        }
        assertTrue("a normal slow op must complete, not be timed out", ran.get())
        // And it did NOT throw a timeout.
        var threw = false
        try {
            dispatcher.run { Thread.sleep(50) }
        } catch (e: TransportOpTimeoutException) {
            threw = true
        }
        assertFalse("a healthy op must not surface a per-op timeout", threw)
        dispatcher.closeAndAwaitDrain { }
    }

    /**
     * Issue #940 regression — the v0.4.16 integration break.
     *
     * The per-op ceiling MUST be driven by REAL wall-clock time, NOT by the
     * caller's coroutine delay source. The combined #927+#930+#937 stack failed
     * 13/21 core-ssh integration tests because every integration test runs under
     * `runTest`, whose virtual clock AUTO-ADVANCES past `withTimeout(8s)`: the
     * old ceiling fired INSTANTLY in virtual time while the real sshj op was
     * still legitimately in progress on the executor thread, interrupting every
     * healthy connect/exec/open (surfacing as `ConnectionException` ->
     * `InterruptedException`, or a spurious `TransportOpTimeoutException`).
     *
     * This reproduces that exact mechanism in pure JVM: a HEALTHY op that takes
     * real time (a blocking sleep WELL under the wall-clock ceiling) is run from
     * inside `runTest`. On the old `withTimeout`-based dispatcher the virtual
     * clock fires the 8s ceiling immediately and the op is aborted with a
     * timeout; with the wall-clock watchdog the op completes normally because
     * the watchdog measures real elapsed time, independent of the test scheduler.
     */
    @Test
    fun `healthy op under runTest virtual clock is not aborted by the per-op ceiling`() = runTest {
        // Generous wall-clock ceiling; the real op below takes ~150ms, so the
        // watchdog must NEVER fire. Under the buggy withTimeout the runTest
        // virtual clock would auto-advance past this instantly and abort the op.
        val dispatcher = TransportDispatcher(perOpTimeoutMs = 5_000L)
        val ran = AtomicBoolean(false)
        var timedOut = false
        try {
            dispatcher.run {
                // A real blocking call — the kind sshj's startSession()/exec()
                // make while waiting on their AQS reply latch. It must run to
                // completion; the watchdog only fires after 5s of REAL time.
                Thread.sleep(150)
                ran.set(true)
            }
        } catch (e: TransportOpTimeoutException) {
            timedOut = true
        }
        assertFalse(
            "a healthy real-time op run under runTest's virtual clock must NOT be " +
                "aborted by the per-op ceiling (#940 regression — fails on the " +
                "withTimeout-based dispatcher because runTest auto-advances virtual time)",
            timedOut,
        )
        assertTrue("the healthy op must have completed", ran.get())
        dispatcher.closeAndAwaitDrain { }
    }

    /**
     * Guards against interrupt-flag leakage: after the dispatcher interrupts a
     * wedged op, the NEXT op on the same thread must NOT inherit the interrupt
     * status (runInterruptible clears it). A leaked interrupt would make the
     * next healthy blocking write spuriously fail.
     */
    @Test
    fun `interrupt flag does not leak to the next op after a wedge`() = runBlocking<Unit> {
        val dispatcher = TransportDispatcher(perOpTimeoutMs = 200L)
        val latch = CountDownLatch(1)
        val wedged = async(Dispatchers.IO) {
            runCatching { dispatcher.run { latch.await() } }
        }
        Thread.sleep(50)
        // Wait for the wedge to time out + be interrupted.
        wedged.await()

        // Next op does a blocking sleep — it would throw InterruptedException
        // if the interrupt flag had leaked onto the reused dispatch thread.
        var leaked = false
        val ok = withTimeoutOrNull(3_000L) {
            dispatcher.run {
                try {
                    Thread.sleep(80)
                } catch (e: InterruptedException) {
                    leaked = true
                }
            }
            true
        }
        assertNotNull("the next op must run", ok)
        assertFalse("interrupt status must not leak to the reused dispatch thread", leaked)

        latch.countDown()
        dispatcher.closeAndAwaitDrain { }
    }
}
