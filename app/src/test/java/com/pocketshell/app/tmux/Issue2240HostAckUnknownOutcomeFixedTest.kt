package com.pocketshell.app.tmux

import com.pocketshell.app.composer.ComposerSendResult
import com.pocketshell.app.composer.InMemoryOutboundQueueStore
import com.pocketshell.app.composer.OutboundDeliveryOutcome
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.OUTBOUND_MAX_AUTO_ATTEMPTS
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.composer.decodeOutboundItems
import com.pocketshell.app.composer.encodeOutboundItems
import com.pocketshell.app.composer.firstComposerAutoFlushable
import com.pocketshell.core.ssh.ExecResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused acceptance coverage for the typed HostAck unknown/action lanes. */
class Issue2240HostAckUnknownOutcomeFixedTest {

    @Test
    fun hostUnknownIsDurableAndOrdinaryRetryCannotClaimItOrStarveTheTail() {
        val store = InMemoryOutboundQueueStore()
        val head = store.enqueue("sess-2240", "head", createdAtMs = 1L)
        val tail = store.enqueue("sess-2240", "tail", createdAtMs = 2L)
        assertNotNull(store.markHostAckUnknown(head.id))

        val unknown = requireNotNull(store.item(head.id))
        assertEquals(OutboundDeliveryOutcome.UnknownMayHaveLanded, unknown.hostAckOutcome)
        assertEquals(
            OutboundDeliveryOutcome.UnknownMayHaveLanded,
            decodeOutboundItems("sess-2240", encodeOutboundItems(listOf(unknown))).single().hostAckOutcome,
        )
        assertNull("ordinary Retry must not clear HostAck uncertainty", store.requeueForRetry(head.id))
        assertNull("ordinary drain must not claim an unknown token", store.claim(head.id))
        assertEquals(
            "FIFO may claim the independent tail, never the unknown head",
            tail.id,
            store.claimNext("sess-2240")?.id,
        )
        assertEquals(
            "the pure auto-flush planner must let an independent tail proceed",
            tail.id,
            listOf(unknown, tail).firstComposerAutoFlushable(
                sessionKey = "sess-2240",
                maxAutoAttempts = OUTBOUND_MAX_AUTO_ATTEMPTS,
            )?.id,
        )
    }

    @Test
    fun onlyExplicitResendRearmsUnknownAndOptInIsVisibleOnTheCommand() {
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sess-2240", "possible duplicate", createdAtMs = 1L)
        store.markHostAckUnknown(row.id)

        assertNull(store.requeueForRetry(row.id))
        val rearmed = requireNotNull(store.requeueForExplicitHostAckResend(row.id))
        assertEquals(OutboundDeliveryOutcome.None, rearmed.hostAckOutcome)
        assertNotNull(store.claim(row.id))

        val ordinary = buildHostAckSendCommand(
            paneId = "%0",
            token = row.id,
            payload = row.cleanText,
            withEnter = true,
        )
        val explicit = buildHostAckSendCommand(
            paneId = "%0",
            token = row.id,
            payload = row.cleanText,
            withEnter = true,
            resendInterrupted = true,
        )
        assertFalse(ordinary.contains("--resend-interrupted"))
        assertTrue(explicit.contains("--resend-interrupted"))
    }

    @Test
    fun explicitResendIntentSurvivesTheScreenRouteToTheHostAckLane() = runTest {
        var observedResendFlag = false
        val request = PromptComposerViewModel.SendRequest(
            text = "possible duplicate",
            withEnter = true,
            sendTarget = PromptComposerViewModel.SendTargetSnapshot(
                sessionKey = "sess-2240-route",
                paneId = "%0",
                route = OutboundRoute.RawBytes,
            ),
            outboundQueueItemId = "row-2240-route",
            resendInterrupted = true,
        )

        assertEquals(
            ComposerSendResult.Delivered,
            tmuxComposerSendResult(
                request = request,
                targetSessionId = "sess-2240-route",
                fallbackPaneId = "%fallback",
                sendAgentPayload = { _, _, _, _, _, _, resendInterrupted ->
                    observedResendFlag = resendInterrupted
                    ComposerSendResult.Delivered
                },
                sendToAgent = { _, _, _, _, resendInterrupted ->
                    observedResendFlag = resendInterrupted
                    ComposerSendResult.Delivered
                },
                sendRawBytes = { _, _, _, _, resendInterrupted ->
                    observedResendFlag = resendInterrupted
                    ComposerSendResult.Delivered
                },
                setTuiNotice = {},
            ),
        )
        assertTrue("only the confirmed explicit action may carry resend intent", observedResendFlag)
    }

    @Test
    fun liveOwnerGetsBoundedStatusReadsAndCanResolveWithoutASecondInjection() = runTest {
        var calls = 0
        val result = deliverViaHostAck(
            exec = HostAckSendExec { _, _ ->
                calls += 1
                if (calls < HOST_ACK_SEND_STATUS_RETRIES) {
                    ExecResult("send-in-progress\n", "owner is alive", HOST_ACK_EXIT_SEND_IN_PROGRESS)
                } else {
                    ExecResult("already-delivered\n", "", HOST_ACK_EXIT_OK)
                }
            },
            paneId = "%0",
            token = "row-2240-progress",
            payload = "status only",
            withEnter = true,
        )

        assertTrue(result.isSuccess)
        assertEquals(HOST_ACK_SEND_STATUS_RETRIES, calls)
    }
}
