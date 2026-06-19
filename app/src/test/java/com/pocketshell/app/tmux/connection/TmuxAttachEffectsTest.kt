package com.pocketshell.app.tmux.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPIC #792 Slice B — focused unit pins for [TmuxAttachEffects], the single fast-switch
 * IO dispatcher.
 *
 * These prove the dispatch contract the hard-cut depends on: the warm fast-switch IO body
 * flows through THIS single owner (so the inline `runFastSessionSwitch` call could be
 * deleted), the body is invoked exactly once, and the suspending body actually runs (the
 * connectJob critical-section ordering — `runFastSwitch` is `suspend` and awaits the body,
 * so the switch completes before the connectJob proceeds, preserving the no-flash /
 * switch-latency contract).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TmuxAttachEffectsTest {

    private class RecordingAttachIo : TmuxAttachEffects.TmuxAttachIo {
        var dispatchCount = 0
        var lastBody: (suspend () -> Unit)? = null

        override suspend fun runFastSwitch(body: suspend () -> Unit) {
            dispatchCount += 1
            lastBody = body
            // The real VM impl is `body()` — run it so the suspend body actually executes
            // within the dispatcher (mirroring production: the IO completes before return).
            body()
        }
    }

    @Test
    fun runFastSwitch_dispatchesTheBodyThroughTheSingleOwnerExactlyOnce() = runTest {
        val io = RecordingAttachIo()
        var bodyRan = 0
        val body: suspend () -> Unit = { bodyRan += 1 }

        TmuxAttachEffects(io).runFastSwitch(body)

        assertEquals("the switch IO was dispatched through the single owner once", 1, io.dispatchCount)
        assertSame("the exact caller body was forwarded", body, io.lastBody)
        assertEquals("the suspend body ran exactly once", 1, bodyRan)
    }

    @Test
    fun runFastSwitch_awaitsTheSuspendBody_preservingConnectJobOrdering() = runTest {
        // The connectJob critical section requires the switch IO to COMPLETE before the
        // job proceeds (no-flash / switch-latency contract). Pin that `runFastSwitch`
        // suspends until the body finishes: a flag set at the END of the body is observed
        // true immediately after the call returns.
        val io = RecordingAttachIo()
        var switchCompleted = false
        val body: suspend () -> Unit = {
            // simulate the multi-step attach IO
            switchCompleted = false
            switchCompleted = true
        }

        TmuxAttachEffects(io).runFastSwitch(body)

        assertTrue("runFastSwitch must await the body before returning", switchCompleted)
    }
}
