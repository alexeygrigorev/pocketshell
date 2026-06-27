package com.pocketshell.app.tmux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Issue #1043 — regression guard for the post-#1041 [TmuxPaneInputQueue] rewrite
 * (`Channel<TmuxPaneInputSegment>` -> `signal: Channel<Unit>(capacity = 1)` +
 * an `ArrayDeque pending`).
 *
 * The bug this locks out: [TmuxPaneInputQueue.takeBatch] returned `null` when
 * the `signal` channel delivered a wakeup but `pending` was *transiently empty*
 * (a STALE / coalesced signal — produced when the drain loop greedily consumes
 * a segment whose triggering `signal` was still buffered, leaving an unconsumed
 * `Unit` that a later `takeBatch` receives on an empty deque). That `null` is
 * indistinguishable from the only LEGITIMATE `null` ("channel closed"), and the
 * pane input bridge loop in `TmuxSessionViewModel.inputSinkForPane` is
 *
 *     while (true) { val batch = queue.takeBatch() ?: break; ... }
 *
 * so a single spurious `null` BREAKS the loop and the pane's input pump dies
 * permanently. On device this is exactly the v0.4.x dogfood symptom the
 * MultiSessionSwitch journey caught: after attach the terminal still shows the
 * seeded marker but typed input never echoes — the bytes never reach the pane
 * because the bridge that drains this queue has exited.
 *
 * The stale-signal interleaving is a genuine producer/consumer race (it cannot
 * be forced single-threaded because the signal/pending accounting is
 * self-balancing without concurrency), so these tests drive a real concurrent
 * producer + consumer over many segments — the same shape the race needs — and
 * assert the contract that makes the input pump durable:
 *
 *   1. Every enqueued byte is delivered exactly once, IN ORDER (no loss, no
 *      reorder, no duplication).
 *   2. [takeBatch] NEVER returns `null` while the channel is open — `null` is
 *      reserved for [close]. A premature `null` is the input-pump-death bug.
 *
 * Pre-fix (the #1041 shape) the consumer hits a spurious `null` mid-stream,
 * breaks early, and delivers FEWER than the enqueued bytes -> RED. With the fix
 * ([takeBatch] re-waits on a transient-empty `pending` instead of returning
 * `null`) every byte is delivered -> GREEN.
 */
class TmuxPaneInputQueueStaleSignalTest {

    private fun newQueue() = TmuxPaneInputQueue(
        maxPendingBytes = TMUX_INPUT_MAX_PENDING_BYTES,
        maxBatchBytes = TMUX_INPUT_MAX_BATCH_BYTES,
    )

    /**
     * The load-bearing regression: a concurrent producer/consumer must deliver
     * every byte in order, and the consumer must NEVER observe a spurious
     * `null` (which would mean the input pump died on a stale signal). Repeated
     * across many rounds so the stale-signal race is exercised reliably.
     */
    @Test
    fun concurrentInputIsDeliveredInOrderAndPumpNeverDiesOnStaleSignal() {
        // A DEDICATED 2-thread dispatcher (NOT Dispatchers.IO) that is fully
        // shut down at the end, so this throughput probe leaves no lingering
        // threads/load that could perturb the timing-sensitive Robolectric
        // flood tests later in the same single-JVM suite.
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                // The stale-signal race is hit readily (the on-device journey and
                // CI hit it first-try; the dev-box RED hit it by round 2). A
                // modest round/segment budget keeps the RED reliable while staying
                // light enough not to disturb sibling tests.
                val rounds = 25
                val perRound = 256
                repeat(rounds) { round ->
                    val queue = newQueue()
                    val expected = StringBuilder(perRound)
                    val collected = StringBuilder(perRound)
                    var sawNullWhileOpen = false

                    // Consumer drains batches exactly like the production input
                    // bridge: `takeBatch() ?: break`. A `null` here BEFORE all
                    // bytes are collected (the channel is still open) is the
                    // spurious-null bug.
                    val consumer = launch(dispatcher) {
                        while (true) {
                            val batch = queue.takeBatch()
                            if (batch == null) {
                                if (collected.length < perRound) sawNullWhileOpen = true
                                break
                            }
                            collected.append(String(batch.bytes, Charsets.UTF_8))
                            queue.recordSent(batch)
                            if (collected.length >= perRound) break
                        }
                    }

                    val producer = launch(dispatcher) {
                        repeat(perRound) { i ->
                            val ch = ('a' + (i % 26))
                            expected.append(ch)
                            queue.write(byteArrayOf(ch.code.toByte()), 0, 1)
                            // Yields widen the window where the consumer's drain
                            // loop picks up a freshly-enqueued segment whose signal
                            // is still buffered — the stale-signal condition.
                            if (i % 5 == 0) Thread.yield()
                        }
                    }

                    producer.join()
                    // On the buggy queue the consumer breaks early via a spurious
                    // null; on the fixed queue it collects all bytes and breaks via
                    // the count. Bound the join so a (hypothetical) lost-wakeup hang
                    // fails loudly rather than blocking the suite.
                    withTimeout(20_000) { consumer.join() }
                    queue.close()

                    assertFalse(
                        "round $round: takeBatch returned a spurious null while the channel was OPEN " +
                            "(collected ${collected.length}/$perRound). A null on a non-closed queue is a " +
                            "STALE-signal wakeup on an empty deque — it kills the pane input pump " +
                            "(`takeBatch() ?: break`), so typed input stops reaching the pane (#1043).",
                        sawNullWhileOpen,
                    )
                    assertEquals(
                        "round $round: every enqueued input byte must be delivered exactly once, in order — " +
                            "the input pump must not lose bytes or die on a stale signal (#1043).",
                        expected.toString(),
                        collected.toString(),
                    )
                }
            }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    /**
     * The ONLY legitimate `null` from [takeBatch] is after [close]; a consumer
     * parked in `takeBatch` must observe `null` (not hang) when the queue is
     * closed, so the bridge loop's `?: break` still tears down cleanly. Guards
     * against the fix over-correcting into a never-terminating loop.
     */
    @Test
    fun takeBatchReturnsNullOnlyAfterClose() = runBlocking {
        val queue = newQueue()
        val done = kotlinx.coroutines.CompletableDeferred<TmuxPaneInputBatch?>()
        // A consumer parked on an empty-but-open queue: close() must release it.
        val parked = launch(Dispatchers.IO) { done.complete(queue.takeBatch()) }
        // Let it actually park on the signal receive.
        Thread.sleep(100)
        assertTrue("consumer should still be parked on an open empty queue", parked.isActive)
        assertFalse("takeBatch must NOT return while the queue is open and empty", done.isCompleted)
        queue.close()
        val batch = withTimeout(5_000) { done.await() }
        assertNull("takeBatch must return null after close so the bridge loop terminates", batch)
    }
}
