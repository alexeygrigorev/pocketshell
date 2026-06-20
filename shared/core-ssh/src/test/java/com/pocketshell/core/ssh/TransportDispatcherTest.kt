package com.pocketshell.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Always-runnable (Docker-free) JVM contract for [TransportDispatcher] — the
 * single-writer invariant that closes the #847 / #766-slice-1 transport
 * corruption.
 *
 * The Docker `heldSessionWithConcurrentLoadDoesNotCorruptTheTransportOver90s`
 * test proves no corruption against a real sshd; this pins the structural
 * property (no two transport ops ever overlap; no op runs after teardown) in
 * per-push CI without a live server, so the protection survives even when the
 * Docker journey is environment-gated.
 */
class TransportDispatcherTest {

    @Test
    fun `concurrent ops never overlap - serialised one at a time`() = runBlocking {
        val dispatcher = TransportDispatcher()
        val inFlight = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val completed = AtomicInteger(0)

        // Fire many ops concurrently from different coroutines, each
        // simulating a transport op that takes non-zero time. The dispatcher
        // MUST run them one-at-a-time: maxConcurrent stays 1.
        val jobs = (0 until 50).map {
            async(Dispatchers.Default) {
                dispatcher.run {
                    val now = inFlight.incrementAndGet()
                    maxConcurrent.updateAndGet { prev -> maxOf(prev, now) }
                    // A tiny busy window so an overlap would be observed.
                    Thread.sleep(2)
                    inFlight.decrementAndGet()
                    completed.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertEquals("every op should have completed", 50, completed.get())
        assertEquals(
            "no two transport ops may run concurrently (single-writer invariant)",
            1,
            maxConcurrent.get(),
        )
        dispatcher.closeAndAwaitDrain { }
    }

    @Test
    fun `ops run in submission order`() = runBlocking {
        val dispatcher = TransportDispatcher()
        val order = ConcurrentLinkedQueue<Int>()
        // Submit sequentially (each suspends until the prior acquires) so the
        // FIFO order is well-defined, then assert it is preserved.
        for (i in 0 until 20) {
            dispatcher.run { order.add(i) }
        }
        assertEquals((0 until 20).toList(), order.toList())
        dispatcher.closeAndAwaitDrain { }
    }

    @Test
    fun `op submitted after close is rejected before touching the transport`() = runBlocking {
        val dispatcher = TransportDispatcher()
        dispatcher.run { /* one live op */ }
        dispatcher.closeAndAwaitDrain { }

        assertTrue("dispatcher should report closed", dispatcher.isClosed)
        var ran = false
        try {
            dispatcher.run { ran = true }
            fail("expected TransportClosedException after close")
        } catch (e: TransportClosedException) {
            // expected
        }
        assertFalse("the rejected op must NOT have run (no write after teardown)", ran)
    }

    @Test
    fun `disconnect runs as the final op after in-flight ops drain`() = runBlocking {
        val dispatcher = TransportDispatcher()
        val trail = ConcurrentLinkedQueue<String>()

        // Start a slow in-flight op, THEN request close while it is running.
        // The disconnect must queue behind it (drain-then-die), so the trail
        // ends with "disconnect", never interleaved.
        val slow = launch(Dispatchers.Default) {
            dispatcher.run {
                trail.add("op-start")
                Thread.sleep(150)
                trail.add("op-end")
            }
        }
        // Give the slow op time to acquire the lock.
        delay(30)
        dispatcher.closeAndAwaitDrain {
            trail.add("disconnect")
        }
        slow.join()

        val list = trail.toList()
        assertEquals(
            "disconnect must be the LAST op, after the in-flight op fully drained " +
                "(no disconnect underneath an in-flight write): $list",
            listOf("op-start", "op-end", "disconnect"),
            list,
        )
    }

    @Test
    fun `closeAndAwaitDrain is idempotent`() = runBlocking {
        val dispatcher = TransportDispatcher()
        val disconnects = AtomicInteger(0)
        dispatcher.closeAndAwaitDrain { disconnects.incrementAndGet() }
        dispatcher.closeAndAwaitDrain { disconnects.incrementAndGet() }
        assertEquals(
            "disconnect should run exactly once across repeated close calls",
            1,
            disconnects.get(),
        )
    }

    @Test
    fun `blocking dispatch is serialised with suspending run`() = runBlocking {
        val dispatcher = TransportDispatcher()
        val inFlight = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        fun record(body: () -> Unit) {
            val now = inFlight.incrementAndGet()
            maxConcurrent.updateAndGet { prev -> maxOf(prev, now) }
            Thread.sleep(2)
            inFlight.decrementAndGet()
            body()
        }

        // Mix suspending run() (exec path) and runBlockingDispatch() (the -CC
        // stdin write / resize / shell close path) concurrently. They share
        // ONE mutex, so they still never overlap.
        val suspendingJobs = (0 until 15).map {
            async(Dispatchers.Default) { dispatcher.run { record { } } }
        }
        val blockingJobs = (0 until 15).map {
            async(Dispatchers.IO) {
                withContext(Dispatchers.IO) { dispatcher.runBlockingDispatch { record { } } }
            }
        }
        (suspendingJobs + blockingJobs).awaitAll()

        assertEquals(
            "suspending and blocking dispatch must not overlap (one mutex)",
            1,
            maxConcurrent.get(),
        )
        dispatcher.closeAndAwaitDrain { }
    }
}
