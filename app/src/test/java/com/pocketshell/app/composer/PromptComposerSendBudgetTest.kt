package com.pocketshell.app.composer

import com.pocketshell.app.tmux.SEND_SESSION_WAIT_TIMEOUT_MS
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Timeout invariants kept separate from the outbound queue behavior fixture. */
class PromptComposerSendBudgetTest {
    @Test
    fun dispatcherSendBudgetIsNotShorterThanConnectWaitBudget() {
        assertTrue(
            PromptComposerViewModel.SEND_TIMEOUT_MS >= SEND_SESSION_WAIT_TIMEOUT_MS,
        )
        val worstCaseLegSum =
            PromptComposerViewModel.ATTACHMENT_UPLOAD_TIMEOUT_MS +
                PromptComposerViewModel.SEND_TIMEOUT_MS
        assertTrue(worstCaseLegSum < PromptComposerViewModel.OVERALL_SEND_TIMEOUT_MS)
        assertTrue(PromptComposerViewModel.OUTBOUND_IN_FLIGHT_STALE_MS >= worstCaseLegSum)
    }

    @Test
    fun sendDuringSlowReconnectIsNotCancelledBeforeConnectBudget() = runTest {
        var reachedDelivery = false
        val delivered = withTimeoutOrNull(PromptComposerViewModel.SEND_TIMEOUT_MS) {
            delay(15_000L)
            reachedDelivery = true
            true
        } == true
        assertTrue(delivered)
        assertTrue(reachedDelivery)
    }
}
