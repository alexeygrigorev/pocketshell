package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.TmuxConnectTrigger
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPIC #792 Slice B/E — focused unit pins for [TmuxAttachEffects], the single fast-switch IO
 * dispatcher with the Slice E typed contract.
 *
 * These prove the dispatch contract the hard-cut depends on: the warm fast-switch IO body flows
 * through THIS single owner with the typed `(target, attempt, trigger, startedAtMs)` args (so the
 * inline `runFastSessionSwitch` call could be deleted and the switch CONTRACT lives on the owner,
 * not in an opaque thunk in the VM), the body is invoked exactly once with the forwarded args, and
 * the suspending body actually runs (the connectJob critical-section ordering — `runFastSwitch` is
 * `suspend` and awaits the body, so the switch completes before the connectJob proceeds, preserving
 * the no-flash / switch-latency contract).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TmuxAttachEffectsTest {

    private val target = ConnectionTarget(
        hostId = 7L,
        hostName = "alice",
        host = "example.com",
        port = 22,
        user = "alice",
        keyPath = "/keys/id_ed25519",
        passphrase = null,
        sessionName = "main",
        startDirectory = null,
    )

    private class RecordingAttachIo : TmuxAttachEffects.TmuxAttachIo {
        var dispatchCount = 0
        var lastTarget: ConnectionTarget? = null
        var lastAttempt: Int? = null
        var lastTrigger: TmuxConnectTrigger? = null
        var lastStartedAtMs: Long? = null
        var switchCompleted = false

        override suspend fun attach(
            target: ConnectionTarget,
            attempt: Int,
            trigger: TmuxConnectTrigger,
            startedAtMs: Long,
        ) {
            dispatchCount += 1
            lastTarget = target
            lastAttempt = attempt
            lastTrigger = trigger
            lastStartedAtMs = startedAtMs
            // Mirror production: the multi-step attach IO completes before return.
            switchCompleted = false
            switchCompleted = true
        }
    }

    @Test
    fun runFastSwitch_dispatchesTheTypedBodyThroughTheSingleOwnerExactlyOnce() = runTest {
        val io = RecordingAttachIo()

        TmuxAttachEffects(io).runFastSwitch(
            target = target,
            attempt = 3,
            trigger = TmuxConnectTrigger.FastSwitch,
            startedAtMs = 1234L,
        )

        assertEquals("the switch IO was dispatched through the single owner once", 1, io.dispatchCount)
        assertEquals("the exact caller target was forwarded", target, io.lastTarget)
        assertEquals("the attempt was forwarded", 3, io.lastAttempt)
        assertEquals("the trigger was forwarded", TmuxConnectTrigger.FastSwitch, io.lastTrigger)
        assertEquals("the startedAtMs was forwarded", 1234L, io.lastStartedAtMs)
    }

    @Test
    fun runFastSwitch_awaitsTheSuspendBody_preservingConnectJobOrdering() = runTest {
        // The connectJob critical section requires the switch IO to COMPLETE before the job
        // proceeds (no-flash / switch-latency contract). Pin that `runFastSwitch` suspends until
        // the body finishes: a flag set at the END of the body is observed true after the call.
        val io = RecordingAttachIo()

        TmuxAttachEffects(io).runFastSwitch(
            target = target,
            attempt = 1,
            trigger = TmuxConnectTrigger.FastSwitch,
            startedAtMs = 0L,
        )

        assertTrue("runFastSwitch must await the body before returning", io.switchCompleted)
    }
}
