package com.pocketshell.app.tmux

import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticRenderHealTrackerTest {

    @Test
    fun idleBeginCompleteReturnsToIdleButPermanentlyAdvancesActivityEpoch() {
        val tracker = AutomaticRenderHealTracker()
        val idleBefore = tracker.snapshot()

        val token = tracker.begin()
        tracker.complete(token)
        val idleAfter = tracker.snapshot()

        assertEquals(0, idleBefore.activeCount)
        assertEquals(0, idleAfter.activeCount)
        assertTrue(idleBefore.activityEpoch > 0L)
        assertNotEquals(idleBefore.activityEpoch, idleAfter.activityEpoch)
        assertEquals(token, idleAfter.activityEpoch)
    }

    @Test
    fun concurrentBeginsGetUniqueTokensAndEveryBeginAdvancesTheEpoch() {
        val tracker = AutomaticRenderHealTracker()
        val initialEpoch = tracker.snapshot().activityEpoch
        val operationCount = 8
        val allBegun = CountDownLatch(operationCount)
        val release = CountDownLatch(1)
        val tokens = Collections.synchronizedList(mutableListOf<Long>())
        val workers = List(operationCount) {
            thread {
                val token = tracker.begin()
                tokens += token
                allBegun.countDown()
                release.await()
                tracker.complete(token)
            }
        }

        assertTrue(allBegun.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val active = tracker.snapshot()
        assertEquals(operationCount, active.activeCount)
        assertEquals(initialEpoch + operationCount, active.activityEpoch)
        assertEquals(operationCount, tokens.toSet().size)

        release.countDown()
        workers.forEach(Thread::join)
        val completed = tracker.snapshot()
        assertEquals(0, completed.activeCount)
        assertEquals(active.activityEpoch, completed.activityEpoch)
    }

    @Test
    fun epochOverflowFailsClosedWithoutCreatingAnActiveOwnerOrResettingToZero() {
        val tracker = AutomaticRenderHealTracker(initialActivityEpoch = Long.MAX_VALUE)

        assertThrows(ArithmeticException::class.java) { tracker.begin() }

        val after = tracker.snapshot()
        assertEquals(0, after.activeCount)
        assertEquals(Long.MAX_VALUE, after.activityEpoch)
    }

    @Test
    fun duplicateOrUnknownCompletionHardFailsWithoutChangingHistory() {
        val tracker = AutomaticRenderHealTracker()
        val token = tracker.begin()
        tracker.complete(token)
        val completed = tracker.snapshot()

        assertThrows(IllegalStateException::class.java) { tracker.complete(token) }
        assertThrows(IllegalStateException::class.java) { tracker.complete(token + 1L) }
        assertEquals(completed, tracker.snapshot())
    }
}
