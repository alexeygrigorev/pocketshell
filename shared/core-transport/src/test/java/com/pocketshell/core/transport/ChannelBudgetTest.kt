package com.pocketshell.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [ChannelBudget] — the #2120 concurrent-channel cap in
 * isolation from sshj and [RealHostConnection]. [RealHostConnectionChannelBudgetTest]
 * covers the same properties wired through the real call sites (exec, PTY,
 * SFTP, port-forward); this file covers the semaphore/timeout/exception
 * accounting itself, which every one of those call sites depends on.
 */
class ChannelBudgetTest {

    @Test
    fun `capacity permits proceed immediately with no wait`() = runBlocking {
        val budget = ChannelBudget(capacity = 3, waitTimeoutMs = 200)

        val permits = List(3) { budget.acquire("op") }

        assertEquals(0, budget.available)
        permits.forEach { it.release() }
        assertEquals(3, budget.available)
    }

    @Test
    fun `a request beyond capacity waits then succeeds once a permit frees`() = runBlocking {
        val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 2_000)
        val held = budget.acquire("first")

        val waiterStarted = CountDownLatch(1)
        val waiterAcquired = async(Dispatchers.Default) {
            waiterStarted.countDown()
            budget.acquire("second")
        }
        waiterStarted.await(2, TimeUnit.SECONDS)
        // Give the waiter a moment to actually be parked on the semaphore
        // before releasing, so this exercises the "waited, then got it" path
        // rather than a lucky race.
        Thread.sleep(50)
        assertFalse("the waiter must not have completed yet", waiterAcquired.isCompleted)

        held.release()

        val secondPermit = waiterAcquired.await()
        assertEquals(0, budget.available)
        secondPermit.release()
        assertEquals(1, budget.available)
    }

    @Test
    fun `exhaustion past the wait timeout throws the typed exception, not a raw failure`() = runBlocking {
        val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 150)
        val held = budget.acquire("holder")

        val failure = try {
            budget.acquire("late-comer")
            fail("expected ChannelBudgetExhaustedException")
            null
        } catch (e: ChannelBudgetExhaustedException) {
            e
        }

        assertEquals("late-comer", failure!!.operation)
        assertEquals(1, failure.capacity)
        assertTrue("waitedMs should reflect the configured timeout", failure.waitedMs >= 150)
        // Still zero available: the timed-out waiter must not have taken (and
        // leaked) a permit — kotlinx's Semaphore.acquire is cancellation-atomic.
        assertEquals(0, budget.available)

        held.release()
        assertEquals(1, budget.available)
    }

    @Test
    fun `release is idempotent so a double release cannot inflate the budget`() = runBlocking {
        val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 200)
        val permit = budget.acquire("op")

        permit.release()
        permit.release()
        permit.release()

        assertEquals("capacity must not grow past its configured size", 1, budget.available)
    }

    @Test
    fun `withPermit releases on normal completion`() = runBlocking {
        val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 200)

        val result = budget.withPermit("op") { "done" }

        assertEquals("done", result)
        assertEquals(1, budget.available)
    }

    @Test
    fun `withPermit releases even when the block throws`() = runBlocking {
        val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 200)

        val thrown = try {
            budget.withPermit("op") { throw IllegalStateException("boom") }
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertEquals("boom", thrown?.message)
        assertEquals("a failing block must still return its permit", 1, budget.available)
    }

    @Test
    fun `exactly capacity concurrent callers proceed and nobody oversubscribes the semaphore`() = runBlocking {
        val capacity = 4
        val budget = ChannelBudget(capacity = capacity, waitTimeoutMs = 3_000)
        val concurrentNow = AtomicInteger(0)
        val maxObservedConcurrent = AtomicInteger(0)
        val releaseGate = CountDownLatch(1)

        val jobs = List(capacity) {
            async(Dispatchers.Default) {
                budget.withPermit("op") {
                    val now = concurrentNow.incrementAndGet()
                    maxObservedConcurrent.updateAndGet { prev -> maxOf(prev, now) }
                    releaseGate.await(2, TimeUnit.SECONDS)
                    concurrentNow.decrementAndGet()
                    Unit
                }
            }
        }
        // Let every job reach the "inside the critical section" point before
        // releasing them all at once.
        awaitAllStartedThenRelease(jobs, releaseGate, capacity, concurrentNow)

        jobs.awaitAll()
        assertEquals(
            "the budget must let exactly `capacity` callers run at once, never fewer or more",
            capacity,
            maxObservedConcurrent.get(),
        )
        assertEquals(capacity, budget.available)
    }

    private suspend fun awaitAllStartedThenRelease(
        jobs: List<kotlinx.coroutines.Deferred<*>>,
        gate: CountDownLatch,
        expected: Int,
        counter: AtomicInteger,
    ) {
        val deadline = System.currentTimeMillis() + 2_000
        while (counter.get() < expected && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(5)
        }
        assertEquals("all $expected callers should have entered concurrently", expected, counter.get())
        gate.countDown()
    }
}
