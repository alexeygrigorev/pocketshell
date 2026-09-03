package com.pocketshell.core.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtual-clock contract test for the D21 delayed close (rewrite task T-5).
 *
 * Every timing assertion here runs on `runTest`'s [kotlinx.coroutines.test.TestScheduler]:
 * the scheduler IS the clock, both for the parked `delay` (via the injected
 * [StandardTestDispatcher]) and for the deadline arithmetic (via the injected
 * `nowMs` reading `testScheduler.currentTime`). That is what makes
 * "no timer runs after cancel()" provable rather than merely likely — the test
 * advances virtual time arbitrarily far past the deadline and asserts the close
 * never happened, which a real-time sleep could never establish.
 *
 * [GraceCloseScheduler] is exercised directly rather than through
 * [RealHostConnection] because the latter needs a live sshj client; the
 * connection wires exactly one line (`GraceCloseScheduler(ioDispatcher) { close() }`)
 * and the real-transport half of the contract is proven end-to-end in
 * `RealHostConnectionGraceIntegrationTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraceCloseSchedulerTest {

    /** Records every close the scheduler drives, with the virtual time it happened at. */
    private class CloseRecorder(private val clock: () -> Long) {
        val firedAtMs = mutableListOf<Long>()
        val count: Int get() = firedAtMs.size

        suspend fun onDeadline() {
            firedAtMs += clock()
        }
    }

    private fun TestScope.newScheduler(recorder: CloseRecorder) = GraceCloseScheduler(
        dispatcher = StandardTestDispatcher(testScheduler),
        nowMs = { testScheduler.currentTime },
        onDeadline = { recorder.onDeadline() },
    )

    @Test
    fun `an un-cancelled close fires exactly at the deadline`() = runTest {
        val recorder = CloseRecorder { testScheduler.currentTime }
        val scheduler = newScheduler(recorder)

        val handle = scheduler.schedule(90_000)
        assertEquals("deadline is now + graceMs on the injected clock", 90_000L, handle.deadlineMs)

        // One millisecond short of the deadline: still nothing.
        advanceTimeBy(89_999)
        assertEquals("close must not fire early", 0, recorder.count)
        assertTrue((handle as GraceHandleImpl).isLive)

        // Crossing the deadline fires it, once.
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(90_000L), recorder.firedAtMs)
        assertFalse(handle.isLive)
        assertEquals(GraceHandleImpl.Phase.FIRED, handle.currentPhase)
        assertNull("a fired close is no longer pending", scheduler.pendingHandle)

        // And nothing further ever happens.
        advanceUntilIdle()
        assertEquals(1, recorder.count)
    }

    @Test
    fun `cancel before the deadline means the close never fires, ever`() = runTest {
        val recorder = CloseRecorder { testScheduler.currentTime }
        val scheduler = newScheduler(recorder)

        val handle = scheduler.schedule(90_000)
        advanceTimeBy(45_000)
        assertEquals(0, recorder.count)

        handle.cancel()

        // The D21 no-background-work proof: run the virtual clock hours past the
        // deadline and drain every queued task. If ANY timer survived the cancel
        // the scheduler would have run it by now.
        advanceTimeBy(6 * 60 * 60 * 1_000L)
        advanceUntilIdle()

        assertEquals("a cancelled grace close must never fire", 0, recorder.count)
        assertEquals(GraceHandleImpl.Phase.CANCELLED, (handle as GraceHandleImpl).currentPhase)
        assertFalse(handle.isLive)
        assertTrue(handle.isCancelled)
    }

    @Test
    fun `cancel is idempotent and still fires nothing`() = runTest {
        val recorder = CloseRecorder { testScheduler.currentTime }
        val scheduler = newScheduler(recorder)

        val handle = scheduler.schedule(1_000)
        handle.cancel()
        handle.cancel()
        handle.cancel()

        advanceUntilIdle()

        assertEquals(0, recorder.count)
        assertEquals(GraceHandleImpl.Phase.CANCELLED, (handle as GraceHandleImpl).currentPhase)
    }

    @Test
    fun `cancelling a zero-length grace still beats the timer`() = runTest {
        val recorder = CloseRecorder { testScheduler.currentTime }
        val scheduler = newScheduler(recorder)

        // A 0 ms grace is armed but not yet dispatched (StandardTestDispatcher
        // queues it), so a cancel on the very next line must still win — this is
        // the race the LAZY start + phase flag exist to close.
        val handle = scheduler.schedule(0)
        handle.cancel()

        advanceUntilIdle()

        assertEquals(0, recorder.count)
    }

    @Test
    fun `a second schedule replaces the first so only the new deadline fires`() = runTest {
        val recorder = CloseRecorder { testScheduler.currentTime }
        val scheduler = newScheduler(recorder)

        val first = scheduler.schedule(90_000) as GraceHandleImpl
        advanceTimeBy(10_000)
        val second = scheduler.schedule(30_000) as GraceHandleImpl

        assertEquals("second deadline is measured from the re-arm instant", 40_000L, second.deadlineMs)
        assertEquals(GraceHandleImpl.Phase.SUPERSEDED, first.currentPhase)
        assertTrue(first.isSuperseded)
        assertFalse(first.isLive)
        assertSame(second, scheduler.pendingHandle)

        // The replacement fires at ITS deadline...
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf(40_000L), recorder.firedAtMs)

        // ...and the superseded one never does, not even at its own original
        // 90_000 deadline or long after it.
        advanceTimeBy(10 * 60 * 1_000L)
        advanceUntilIdle()
        assertEquals("the superseded close must not fire too", 1, recorder.count)
    }

    @Test
    fun `re-arming further out postpones the close instead of firing at the old deadline`() = runTest {
        val recorder = CloseRecorder { testScheduler.currentTime }
        val scheduler = newScheduler(recorder)

        scheduler.schedule(1_000)
        advanceTimeBy(500)
        val extended = scheduler.schedule(10_000)

        // Past the FIRST deadline: nothing, because it was superseded.
        advanceTimeBy(1_000)
        assertEquals("the replaced deadline must not fire", 0, recorder.count)

        advanceTimeBy(9_500)
        runCurrent()
        assertEquals(listOf(10_500L), recorder.firedAtMs)
        assertEquals(10_500L, extended.deadlineMs)
    }

    @Test
    fun `cancelling the replacement leaves nothing armed at all`() = runTest {
        val recorder = CloseRecorder { testScheduler.currentTime }
        val scheduler = newScheduler(recorder)

        val first = scheduler.schedule(5_000)
        val second = scheduler.schedule(5_000)
        second.cancel()

        advanceTimeBy(60_000)
        advanceUntilIdle()

        assertEquals("neither the superseded nor the cancelled close may fire", 0, recorder.count)
        assertFalse((first as GraceHandleImpl).isLive)
        assertFalse((second as GraceHandleImpl).isLive)
    }

    @Test
    fun `cancelling a superseded handle does not disturb the live one`() = runTest {
        val recorder = CloseRecorder { testScheduler.currentTime }
        val scheduler = newScheduler(recorder)

        val first = scheduler.schedule(5_000)
        val second = scheduler.schedule(8_000)
        // A caller holding the stale handle cancels it late; the armed close
        // must survive, otherwise a background flip would silently disarm grace.
        first.cancel()

        advanceTimeBy(8_000)
        runCurrent()

        assertEquals(listOf(8_000L), recorder.firedAtMs)
        assertEquals(GraceHandleImpl.Phase.SUPERSEDED, (first as GraceHandleImpl).currentPhase)
        assertEquals(GraceHandleImpl.Phase.FIRED, (second as GraceHandleImpl).currentPhase)
    }

    @Test
    fun `a negative grace is treated as immediate, not as never`() = runTest {
        val recorder = CloseRecorder { testScheduler.currentTime }
        val scheduler = newScheduler(recorder)

        val handle = scheduler.schedule(-5_000)
        advanceUntilIdle()

        assertEquals(1, recorder.count)
        assertEquals(0L, recorder.firedAtMs.single())
        assertEquals(GraceHandleImpl.Phase.FIRED, (handle as GraceHandleImpl).currentPhase)
    }

    @Test
    fun `a close that throws neither escapes nor blocks the next grace window`() = runTest {
        val fired = mutableListOf<Long>()
        val scheduler = GraceCloseScheduler(
            dispatcher = StandardTestDispatcher(testScheduler),
            nowMs = { testScheduler.currentTime },
            onDeadline = {
                fired += testScheduler.currentTime
                throw IllegalStateException("close failed")
            },
        )

        scheduler.schedule(1_000)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(1_000L), fired)

        // The SupervisorJob + runCatching keep the scope usable: a later grace
        // window still arms and still fires.
        scheduler.schedule(1_000)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(1_000L, 2_000L), fired)
    }
}
