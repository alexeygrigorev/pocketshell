package com.pocketshell.app.tmux

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #938 (audit #935 S2-2, HIGH): [PortDetector]'s `handled` /
 * `pendingConfirm` sets are mutated from TWO dispatchers — `scan()` runs
 * off-Main on the view model's port-detection dispatcher (issue #877) while
 * `confirmed` / `dismissed` / `forwarded` / `confirmFailed` run on Main.
 *
 * A plain `java.util.HashSet` is NOT thread-safe under concurrent structural
 * modification: when several threads `add()` distinct elements that race the
 * backing `HashMap`'s table resize, updates are silently lost (a new element
 * lands in a bucket array that a concurrent resize then discards), and a torn
 * resize can also throw `NullPointerException` / corrupt the table. The
 * detector relies on a port that reached a terminal state (`confirmed` /
 * `dismissed` / `forwarded`) STAYING in `handled` so it is NEVER offered
 * again. A lost update means a handled port silently drops out of `handled`
 * and the detector re-offers it — the user gets a duplicate forward prompt for
 * a port they already resolved.
 *
 * This test makes that corruption OBSERVABLE and reliable through the REAL
 * public API:
 *
 *  - [THREADS] threads concurrently move a large, DISJOINT range of ports into
 *    the terminal `handled` set via `confirmed` / `dismissed` / `forwarded`,
 *    started together on a [CyclicBarrier] so their `add()` loops overlap and
 *    drive many concurrent table resizes.
 *  - A single-threaded `scan()` referencing EVERY one of those ports must then
 *    return ZERO candidates — every port is handled, so none can be offered
 *    again. (All ports stay within `PortDetector.VALID_PORT_RANGE` so `scan`
 *    actually considers them.)
 *  - On the UNFIXED code the concurrent adds lose ports from `handled`, so the
 *    final scan re-offers them → non-empty → FAIL (red). With the lock every
 *    add lands → empty → PASS (green). Any thrown exception (NPE / CME / table
 *    corruption) is also captured and fails the test.
 *
 * Reliability: with [THREADS] writers hammering one growing `HashSet` across
 * [batches] resize-heavy batches and [attempts] attempts, at least one batch
 * loses an element on the base virtually every run. Reproduce-first
 * (D33/G1/G10): revert the lock in PortDetector → this test FAILS; restore the
 * lock → it PASSES.
 */
class PortDetectorConcurrencyTest {

    private companion object {
        const val THREADS = 6
        // 6 * 10_000 = 60_000 distinct ports, all inside VALID_PORT_RANGE
        // (1..65535) so the union scan actually evaluates every one of them.
        const val PORTS_PER_THREAD = 10_000
    }

    @Test
    fun `terminal-state ports survive concurrent mutation and are never re-offered`() {
        val batches = 6
        val attempts = 8

        repeat(attempts) { attempt ->
            val thrown = AtomicReference<Throwable?>(null)
            val pool = Executors.newFixedThreadPool(THREADS)

            for (batch in 0 until batches) {
                // A FRESH detector per batch: ports terminalized in an earlier
                // batch would already be in `handled`, masking a later batch's
                // race. Each batch starts from empty so every add is a genuine
                // concurrent insertion into a freshly-growing HashSet.
                val detector = PortDetector()
                // In-range port block per thread (1000.. < 65535): every add is
                // a distinct new key, isolating the failure to concurrent
                // structural growth of the single shared `handled` HashSet.
                val batchBase = 1_000
                val barrier = CyclicBarrier(THREADS)
                val done = CountDownLatch(THREADS)

                fun terminalize(threadBase: Int) = Runnable {
                    try {
                        barrier.await() // release all writers together
                        for (i in 0 until PORTS_PER_THREAD) {
                            val port = threadBase + i
                            when (i % 3) {
                                0 -> detector.confirmed(port)
                                1 -> detector.dismissed(port)
                                else -> detector.forwarded(port)
                            }
                        }
                    } catch (t: Throwable) {
                        thrown.compareAndSet(null, t)
                    } finally {
                        done.countDown()
                    }
                }

                for (t in 0 until THREADS) {
                    pool.submit(terminalize(batchBase + t * PORTS_PER_THREAD))
                }
                assertTrue(
                    "attempt $attempt batch $batch: mutation threads timed out",
                    done.await(30, TimeUnit.SECONDS),
                )

                // Every port every thread terminalized this batch must now be
                // in `handled`, so a fresh scan referencing all of them offers
                // NOTHING. A re-offer == a port lost from `handled` under the
                // concurrent-add race on a plain HashSet.
                val sb = StringBuilder(THREADS * PORTS_PER_THREAD * 24)
                for (t in 0 until THREADS) {
                    val threadBase = batchBase + t * PORTS_PER_THREAD
                    for (i in 0 until PORTS_PER_THREAD) {
                        sb.append("http://127.0.0.1:").append(threadBase + i).append(' ')
                    }
                }
                sb.append('\n') // trailing token so no port is treated as truncated
                val reOffered = detector.scan(sb.toString()).map { it.port }

                assertTrue(
                    "attempt $attempt batch $batch: ${reOffered.size} terminal-state " +
                        "ports were re-offered ${reOffered.take(8)} — they were lost " +
                        "from `handled` by the concurrent-add race on a plain HashSet (#938)",
                    reOffered.isEmpty(),
                )
            }

            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
            assertNull(
                "attempt $attempt: concurrent mutation of PortDetector threw " +
                    "${thrown.get()} — handled/pendingConfirm raced across dispatchers (#938)",
                thrown.get(),
            )
        }
    }
}
