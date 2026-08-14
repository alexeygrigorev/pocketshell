package com.pocketshell.app.tmux

import com.pocketshell.app.tmux.connection.GraceClientId
import com.pocketshell.app.tmux.connection.GraceEffects
import com.pocketshell.app.tmux.connection.GraceRecoveryOwnership
import com.pocketshell.app.tmux.connection.WithinGraceRecoveryClaim
import com.pocketshell.core.connection.SessionId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #822 — the in-place Retry must be the SINGLE owner of the transport.
 *
 * ## The reported bug this pins
 *
 * "The current session does not reconnect in place; switching to a different session and
 * back finally restores it." The reason a SWITCH worked and a RETRY did not is ownership:
 * a switch supersedes the bounded within-grace recovery claim, a manual reconnect did not.
 * So the user's Retry acquired a healthy fresh lease while the still-running grace loop kept
 * re-dialling the corpse — observed on the emulator as
 * `proven-dead SSH lease was no longer current` every 250 ms, tearing the successor down
 * until the session settled on `Unreachable`. Two writers on one transport (D28).
 *
 * [GraceEffects.retireForSupersedingOwner] is the hand-over, and
 * `TmuxSessionViewModel.startReconnectForSendBody` calls it before re-entering `connect()`.
 * The VM-level wiring of that call on the exact reported path is pinned by
 * [Issue1538ForegroundHealWithinGraceRideThroughTest.manualReconnectRetiresTheWithinGraceOwner].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue822ManualReconnectRetiresGraceOwnerTest : TmuxSessionViewModelTestBase() {

    private val claim = WithinGraceRecoveryClaim(SessionId("7/work"), GraceClientId(1))

    private fun graceEffects(): GraceEffects =
        GraceEffects(
            object : GraceEffects.GraceIo {
                override fun launchBackgroundDetachTeardown() = Unit
                override fun launchForegroundReattachReseed() = Unit
                override fun launchForegroundHealWithinGrace() = Unit
            },
        )

    @Test
    fun retirementEndsTheClaimAndCancelsItsCoroutines() = runTest(scheduler) {
        val effects = graceEffects()
        effects.beginWithinGraceRecovery(claim)
        val tracked = Job()
        effects.trackRecoveryJob(tracked)

        assertTrue("precondition: the bounded owner holds the claim", effects.isWithinGraceRecoveryActive())
        assertFalse("precondition: the owner's coroutine is live", tracked.isCancelled)

        effects.retireForSupersedingOwner()

        assertEquals(
            "#822: the superseding manual reconnect must end the bounded claim",
            GraceRecoveryOwnership.Idle,
            effects.recoveryOwnership,
        )
        assertTrue(
            "#822: the retired owner's coroutines must be cancelled, not left racing the " +
                "manual reconnect's fresh transport",
            tracked.isCancelled,
        )
    }

    @Test
    fun retirementStopsTheInFlightRecoveryRetryLoop() = runTest(scheduler) {
        val effects = graceEffects()
        effects.beginWithinGraceRecovery(claim)
        var attempts = 0
        val loop = launch {
            effects.retryWithinGraceRecovery(
                claim = claim,
                timeoutMs = 60_000L,
                retryDelayMs = 250L,
            ) {
                attempts += 1
                false
            }
        }
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()
        val attemptsBeforeRetirement = attempts
        assertTrue("precondition: the grace loop is re-dialling", attemptsBeforeRetirement >= 2)

        effects.retireForSupersedingOwner()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(
            "#822: no further grace-loop re-dial may run after the manual reconnect takes " +
                "over — every extra attempt is what evicted the successor's fresh lease",
            attemptsBeforeRetirement,
            attempts,
        )
        assertTrue("the retired loop must complete", loop.isCompleted)
    }

}
