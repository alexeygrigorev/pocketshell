package com.pocketshell.app.tmux

import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.ComposerSendResult
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1739: the screen-to-VM composer boundary must not discard the durable
 * queue row id. That id is the delivery guard's stable token across an ambiguous
 * first attempt and its auto-flush retry.
 */
class Issue1739DurableTokenRoutingTest {

    @Test
    fun durableRowTokenCrossesEveryComposerRoute() = runTest {
        val cases = listOf(
            RouteCase(
                name = "agent payload",
                request = request(
                    route = OutboundRoute.AgentPayload,
                    text = "agent payload",
                    agentKind = "claude",
                ),
                expectedLane = "agent-payload",
                expectedPayload = "agent payload",
                expectedDeliveryProof = AgentSubmitDeliveryProof.AgentTurnover,
            ),
            RouteCase(
                name = "conversation echo",
                request = request(
                    route = OutboundRoute.AgentConversation,
                    text = "conversation prompt",
                    agentKind = "claude",
                ),
                expectedLane = "agent-echo",
                expectedPayload = "conversation prompt",
            ),
            RouteCase(
                name = "conversation TUI command",
                request = request(
                    route = OutboundRoute.AgentConversation,
                    text = "/model",
                    agentKind = "claude",
                ),
                expectedLane = "agent-payload",
                expectedPayload = "/model",
                expectedDeliveryProof = AgentSubmitDeliveryProof.TmuxEnterAccepted,
            ),
            RouteCase(
                name = "raw bytes",
                request = request(
                    route = OutboundRoute.RawBytes,
                    text = "printf token",
                    withEnter = true,
                ),
                expectedLane = "raw-bytes",
                expectedPayload = "printf token\r",
            ),
        )

        cases.forEach { case ->
            val calls = mutableListOf<WireCall>()
            val sent = dispatch(case.request, calls)

            assertEquals("${case.name} should dispatch", ComposerSendResult.Delivered, sent)
            assertEquals("${case.name} must dispatch once", 1, calls.size)
            assertEquals(case.expectedLane, calls.single().lane)
            assertEquals(case.expectedPayload, calls.single().payload)
            assertEquals(
                "${case.name} must retain the exact durable row id as its guard token",
                DURABLE_ROW_ID,
                calls.single().sendToken,
            )
            assertEquals(
                DurableOutboundRowIdentity(SESSION_ID, DURABLE_ROW_ID),
                calls.single().durableRow,
            )
            case.expectedDeliveryProof?.let {
                assertEquals("${case.name} must use its route-specific delivery proof", it, calls.single().deliveryProof)
            }
        }
    }

    @Test
    fun nullRowIdGetsFreshGeneratedFallbackOnlyForNonDurableRequests() = runTest {
        val firstCalls = mutableListOf<WireCall>()
        val secondCalls = mutableListOf<WireCall>()
        val nonDurable = request(
            route = OutboundRoute.RawBytes,
            text = "legacy",
            outboundQueueItemId = null,
        )

        assertEquals(ComposerSendResult.Delivered, dispatch(nonDurable, firstCalls))
        assertEquals(ComposerSendResult.Delivered, dispatch(nonDurable, secondCalls))

        val firstToken = firstCalls.single().sendToken
        val secondToken = secondCalls.single().sendToken
        assertTrue(firstToken.isNotBlank())
        assertTrue(secondToken.isNotBlank())
        assertNotEquals(
            "distinct non-durable sends must not share delivery identity",
            firstToken,
            secondToken,
        )
        assertNotEquals(DURABLE_ROW_ID, firstToken)
        assertNotEquals(DURABLE_ROW_ID, secondToken)
        assertEquals(null, firstCalls.single().durableRow)
        assertEquals(null, secondCalls.single().durableRow)
    }

    @Test
    fun sameDurableRouteRetryUsesOneTokenAndSubmitsEnterWithoutDuplicatePaste() = runTest {
        val ledger = OutboundDeliveryLedger()
        val client = FakeTmuxClient()
        val calls = mutableListOf<WireCall>()
        var pasteCount = 0
        var enterCount = 0
        val durableRequest = request(
            route = OutboundRoute.AgentPayload,
            text = MULTILINE_PAYLOAD,
            agentKind = "claude",
        )

        val sendAgentPayload = guardedAgentSend(
            ledger = ledger,
            client = client,
            calls = calls,
            onPaste = { pasteCount += 1 },
            onEnter = { enterCount += 1 },
        )
        val first = dispatch(
            request = durableRequest,
            calls = calls,
            sendAgentPayload = sendAgentPayload,
        )
        assertEquals("the first ambiguous paste remains queued", ComposerSendResult.AuthoritativeAckPending, first)

        val landedChip = CommandResponse(
            number = 0L,
            output = listOf("> [Pasted text #1 +2 lines]"),
            isError = false,
        )
        client.scrollbackCaptureResponse = landedChip
        client.defaultCaptureResponse = landedChip
        val retry = dispatch(
            request = durableRequest,
            calls = calls,
            sendAgentPayload = sendAgentPayload,
        )

        assertEquals("the proven landed chip authorizes Enter-only completion", ComposerSendResult.Delivered, retry)
        assertEquals(listOf(DURABLE_ROW_ID, DURABLE_ROW_ID), calls.map { it.sendToken })
        assertEquals("the retry must not duplicate-paste", 1, pasteCount)
        assertEquals("the proven retry submits exactly one Enter", 1, enterCount)
    }

    @Test
    fun sameDurableRouteRetryWithoutPaneProofNeverBlindlyPressesEnter() = runTest {
        val ledger = OutboundDeliveryLedger()
        val client = FakeTmuxClient().apply {
            defaultCaptureResponse = CommandResponse(
                number = 0L,
                output = listOf("> "),
                isError = false,
            )
        }
        val calls = mutableListOf<WireCall>()
        var pasteCount = 0
        var enterCount = 0
        val durableRequest = request(
            route = OutboundRoute.AgentPayload,
            text = MULTILINE_PAYLOAD,
            agentKind = "claude",
        )
        val sendAgentPayload = guardedAgentSend(
            ledger = ledger,
            client = client,
            calls = calls,
            onPaste = { pasteCount += 1 },
            onEnter = { enterCount += 1 },
        )

        assertEquals(ComposerSendResult.AuthoritativeAckPending, dispatch(durableRequest, calls, sendAgentPayload))
        assertEquals(ComposerSendResult.AuthoritativeAckPending, dispatch(durableRequest, calls, sendAgentPayload))

        assertEquals(listOf(DURABLE_ROW_ID, DURABLE_ROW_ID), calls.map { it.sendToken })
        assertEquals("unknown delivery must not duplicate-paste", 1, pasteCount)
        assertEquals("unknown delivery must never authorize a blind Enter", 0, enterCount)
    }

    @Test
    fun nullLiteralBaselineWithDurableCollapsedMarkerSubmitsEnterOnly() = runTest {
        val ledger = OutboundDeliveryLedger()
        val durableRow = DurableOutboundRowIdentity(SESSION_ID, DURABLE_ROW_ID)
        ledger.recordWireAttempt(
            paneId = PANE_ID,
            sendToken = DURABLE_ROW_ID,
            payload = MULTILINE_PAYLOAD,
            baselineCount = null,
            collapsedMarkerBaselineCount = 0,
            durableRow = durableRow,
        )
        val client = FakeTmuxClient().apply {
            defaultCaptureResponse = CommandResponse(
                number = 0L,
                output = listOf("> [Pasted text #1 +2 lines]"),
                isError = false,
            )
        }
        val calls = mutableListOf<WireCall>()
        var pasteCount = 0
        var enterCount = 0

        val result = dispatch(
            request = request(
                route = OutboundRoute.AgentPayload,
                text = MULTILINE_PAYLOAD,
                agentKind = "claude",
            ),
            calls = calls,
            sendAgentPayload = guardedAgentSend(
                ledger,
                client,
                calls,
                onPaste = { pasteCount += 1 },
                onEnter = { enterCount += 1 },
            ),
        )

        assertEquals("independent durable marker evidence authorizes completion", ComposerSendResult.Delivered, result)
        assertEquals("the already-landed multiline payload must not be pasted again", 0, pasteCount)
        assertEquals("the proven collapsed marker authorizes exactly one Enter", 1, enterCount)
    }

    @Test
    fun nullLiteralAndMarkerBaselinesAuthorizeNeitherRepasteNorEnter() = runTest {
        val ledger = OutboundDeliveryLedger()
        val durableRow = DurableOutboundRowIdentity(SESSION_ID, DURABLE_ROW_ID)
        ledger.recordWireAttempt(
            paneId = PANE_ID,
            sendToken = DURABLE_ROW_ID,
            payload = MULTILINE_PAYLOAD,
            baselineCount = null,
            collapsedMarkerBaselineCount = null,
            durableRow = durableRow,
        )
        val client = FakeTmuxClient().apply {
            defaultCaptureResponse = CommandResponse(
                number = 0L,
                output = listOf("> [Pasted text #1 +2 lines]"),
                isError = false,
            )
        }
        val calls = mutableListOf<WireCall>()
        var pasteCount = 0
        var enterCount = 0

        val result = dispatch(
            request = request(
                route = OutboundRoute.AgentPayload,
                text = MULTILINE_PAYLOAD,
                agentKind = "claude",
            ),
            calls = calls,
            sendAgentPayload = guardedAgentSend(
                ledger,
                client,
                calls,
                onPaste = { pasteCount += 1 },
                onEnter = { enterCount += 1 },
            ),
        )

        assertEquals("untrusted legacy evidence must remain retryable", ComposerSendResult.AuthoritativeAckPending, result)
        assertEquals("unknown evidence must not duplicate-paste", 0, pasteCount)
        assertEquals("unknown evidence must never authorize Enter", 0, enterCount)
    }

    private fun request(
        route: OutboundRoute,
        text: String,
        withEnter: Boolean = true,
        agentKind: String? = null,
        outboundQueueItemId: String? = DURABLE_ROW_ID,
    ): PromptComposerViewModel.SendRequest =
        PromptComposerViewModel.SendRequest(
            text = text,
            withEnter = withEnter,
            sendTarget = PromptComposerViewModel.SendTargetSnapshot(
                sessionKey = SESSION_ID,
                paneId = PANE_ID,
                route = route,
                agentKind = agentKind,
            ),
            outboundQueueItemId = outboundQueueItemId,
        )

    private suspend fun dispatch(
        request: PromptComposerViewModel.SendRequest,
        calls: MutableList<WireCall>,
            sendAgentPayload: suspend (
                paneId: String,
                text: String,
                agent: AgentKind,
                sendToken: String,
                durableRow: DurableOutboundRowIdentity?,
                deliveryProof: AgentSubmitDeliveryProof,
                resendInterrupted: Boolean,
            ) -> ComposerSendResult = { paneId, text, _, sendToken, durableRow, deliveryProof, _ ->
                calls += WireCall("agent-payload", paneId, text, sendToken, durableRow, deliveryProof)
                ComposerSendResult.Delivered
            },
    ): ComposerSendResult =
        tmuxComposerSendResult(
            request = request,
            targetSessionId = SESSION_ID,
            fallbackPaneId = "%fallback",
            sendAgentPayload = sendAgentPayload,
            sendToAgent = { paneId, text, sendToken, durableRow, _ ->
                calls += WireCall("agent-echo", paneId, text, sendToken, durableRow)
                ComposerSendResult.Delivered
            },
            sendRawBytes = { paneId, bytes, sendToken, durableRow, _ ->
                calls += WireCall(
                    lane = "raw-bytes",
                    paneId = paneId,
                    payload = String(bytes, Charsets.UTF_8),
                    sendToken = sendToken,
                    durableRow = durableRow,
                )
                ComposerSendResult.Delivered
            },
            setTuiNotice = {},
        )

    private fun guardedAgentSend(
        ledger: OutboundDeliveryLedger,
        client: FakeTmuxClient,
        calls: MutableList<WireCall>,
        onPaste: () -> Unit,
        onEnter: () -> Unit,
    ): suspend (
        String,
        String,
        AgentKind,
        String,
        DurableOutboundRowIdentity?,
        AgentSubmitDeliveryProof,
        Boolean,
    ) -> ComposerSendResult =
        { paneId, text, _, sendToken, durableRow, _, _ ->
            calls += WireCall("agent-payload", paneId, text, sendToken, durableRow)
            when (
                verifyBeforeAgentResend(
                    ledger = ledger,
                    client = client,
                    paneId = paneId,
                    sendToken = sendToken,
                    payload = text,
                    durableRow = durableRow,
                )
            ) {
                DeliveryProbeOutcome.AlreadyLanded -> {
                    onEnter()
                    ledger.clear(paneId, sendToken)
                    ComposerSendResult.Delivered
                }
                DeliveryProbeOutcome.Unknown -> ComposerSendResult.AuthoritativeAckPending
                DeliveryProbeOutcome.NotLanded, null -> {
                    ledger.recordWireAttempt(
                        paneId = paneId,
                        sendToken = sendToken,
                        payload = text,
                        baselineCount = 0,
                        collapsedMarkerBaselineCount = 0,
                        durableRow = durableRow,
                    )
                    onPaste()
                    ComposerSendResult.AuthoritativeAckPending
                }
            }
        }

    private data class RouteCase(
        val name: String,
        val request: PromptComposerViewModel.SendRequest,
        val expectedLane: String,
        val expectedPayload: String,
        val expectedDeliveryProof: AgentSubmitDeliveryProof? = null,
    )

    private data class WireCall(
        val lane: String,
        val paneId: String,
        val payload: String,
        val sendToken: String,
        val durableRow: DurableOutboundRowIdentity?,
        val deliveryProof: AgentSubmitDeliveryProof? = null,
    )

    private companion object {
        const val SESSION_ID = "issue1739-session"
        const val PANE_ID = "%0"
        const val DURABLE_ROW_ID = "issue1739-durable-row"
        const val MULTILINE_PAYLOAD = "first line\nsecond line\nthird line"
    }
}
