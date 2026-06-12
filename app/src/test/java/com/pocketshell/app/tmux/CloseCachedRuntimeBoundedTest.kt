package com.pocketshell.app.tmux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #710 regression: the VM-clear park path
 * ([TmuxSessionViewModel.closeCachedRuntimesBlocking]) calls
 * [CachedTmuxRuntime.closeCachedRuntime] inside `runBlocking` ON THE MAIN
 * THREAD. A pane producer/input/agent job wedged in a non-cooperative `-CC`
 * socket read never honours cancellation, so an unbounded
 * `cancelAndJoin()` (or `NonCancellable` `lease.release()`) would freeze the
 * main thread indefinitely — activity never DESTROYED, real-device freeze on
 * background-mid-rapid-switch.
 *
 * These tests deliberately wedge each job slot (producer / input / agent) in a
 * non-cancellable infinite suspension and assert `closeCachedRuntime` returns
 * within a few multiples of [SYNC_DETACH_TIMEOUT_MS] of wall-clock time rather
 * than hanging. They run on a REAL dispatcher (not virtual time) so the
 * wall-clock [withTimeoutOrNull] bound is actually exercised — a virtual-time
 * `TestScope` would fast-forward the timeout and prove nothing.
 *
 * Red→green: reverting the `withTimeoutOrNull` bound in `closeCachedRuntime`
 * makes these hang until the global JUnit timeout instead of completing in
 * ~budget.
 */
class CloseCachedRuntimeBoundedTest {

    // Generous wall-clock ceiling: the bound is SYNC_DETACH_TIMEOUT_MS (600ms)
    // applied per cancel/join sweep + per lease release, so even the worst case
    // (every slot wedged) is a small multiple of the budget. We assert the call
    // returns well under a hard hang. A bug (unbounded join) blows past this and
    // only stops at the JUnit method timeout.
    private val maxWallClockMs: Long = SYNC_DETACH_TIMEOUT_MS * 6

    @Test(timeout = 30_000)
    fun closeCachedRuntimeReturnsWithinBudgetWhenProducerJobIsWedged() {
        assertReturnsWithinBudget { stuckJob ->
            cachedRuntimeWithJobs(producerJobs = mapOf("%0" to stuckJob))
        }
    }

    @Test(timeout = 30_000)
    fun closeCachedRuntimeReturnsWithinBudgetWhenInputJobIsWedged() {
        assertReturnsWithinBudget { stuckJob ->
            cachedRuntimeWithJobs(inputJobs = mapOf("%0" to stuckJob))
        }
    }

    @Test(timeout = 30_000)
    fun closeCachedRuntimeReturnsWithinBudgetWhenAgentJobIsWedged() {
        assertReturnsWithinBudget { stuckJob ->
            cachedRuntimeWithJobs(agentJobs = mapOf("%0" to stuckJob))
        }
    }

    @Test(timeout = 30_000)
    fun closeCachedRuntimeStillCompletesQuicklyOnCleanCloseWithNoJobs() {
        // Belt-and-suspenders: the normal clean-close path (no wedged jobs) must
        // NOT pay the timeout budget — it should finish near-instantly.
        runBlocking {
            val runtime = cachedRuntimeWithJobs()
            val client = runtime.client as FakeTmuxClient
            val elapsed = measureRealMillis {
                runtime.closeCachedRuntime(detachTimeoutMs = SYNC_DETACH_TIMEOUT_MS)
            }
            assertTrue(
                "clean close should be fast (was ${elapsed}ms)",
                elapsed < SYNC_DETACH_TIMEOUT_MS,
            )
            assertTrue("client should be detached", client.detachCleanlyCalled)
            assertTrue("client should be closed", client.closed)
        }
    }

    private fun assertReturnsWithinBudget(
        buildRuntime: (stuckJob: Job) -> CachedTmuxRuntime,
    ) {
        runBlocking {
            val stuckJob = launchNonCancellableForeverJob()
            // Ensure the job has actually entered its non-cancellable park before
            // we tear it down; otherwise cancelAndJoin would cancel it before it
            // starts and it would complete instantly, defeating the test.
            startedGate.await()
            try {
                val runtime = buildRuntime(stuckJob)
                val elapsed = measureRealMillis {
                    runtime.closeCachedRuntime(detachTimeoutMs = SYNC_DETACH_TIMEOUT_MS)
                }
                assertTrue(
                    "closeCachedRuntime must return within ${maxWallClockMs}ms even with a " +
                        "wedged job (was ${elapsed}ms)",
                    elapsed < maxWallClockMs,
                )
                // The wedged job is abandoned (left to GC / grace TTL) — its join
                // was timed out, not completed — so it is NOT yet finished. The
                // point of the test is that the CALLER returned regardless.
                assertTrue(
                    "stuck job is intentionally abandoned, not completed",
                    !stuckJob.isCompleted,
                )
            } finally {
                // Force-terminate the deliberately-wedged job so it doesn't leak
                // past the test. Its body ignores cooperative cancellation, so we
                // resume its completion gate directly.
                releaseGate.complete(Unit)
            }
        }
    }

    private val releaseGate = CompletableDeferred<Unit>()
    private val startedGate = CompletableDeferred<Unit>()

    /**
     * A job that ignores cooperative cancellation: it runs its whole body inside
     * [NonCancellable] and parks on a gate that is only completed by the test
     * teardown. `cancelAndJoin()` on this job cancels it (no-op, already
     * NonCancellable) and then `join`s — which blocks until the body returns,
     * i.e. forever, exactly like a producer wedged in a blocking socket read.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun launchNonCancellableForeverJob(): Job =
        GlobalScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                startedGate.complete(Unit)
                releaseGate.await()
            }
        }

    private inline fun measureRealMillis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun cachedRuntimeWithJobs(
        producerJobs: Map<String, Job> = emptyMap(),
        inputJobs: Map<String, Job> = emptyMap(),
        agentJobs: Map<String, Job> = emptyMap(),
    ): CachedTmuxRuntime =
        CachedTmuxRuntime(
            key = TmuxRuntimeKey(
                hostId = 1L,
                hostname = "alpha.example",
                port = 22,
                username = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
            ),
            hostName = "alpha",
            startDirectory = null,
            session = null,
            client = FakeTmuxClient(),
            panes = emptyList(),
            paneRows = emptyMap(),
            paneProducerJobs = producerJobs,
            paneInputQueues = emptyMap(),
            paneInputJobs = inputJobs,
            paneAgentJobs = agentJobs,
            paneAgentInputs = emptyMap(),
            agentConversations = emptyMap(),
            remoteColumns = 0,
            remoteRows = 0,
            lease = null,
        )
}
