package com.pocketshell.app.tmux

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2205: the #1739 real-transport wedge keyed only on the collapsed
 * `[Pasted text #` chip, but the paste-ack gate also accepts the payload
 * needle. A visible frame that echoes the body (no chip) therefore acked,
 * the first send returned success, and Integration went red on
 * `unconfirmed first attempt must remain retryable`.
 *
 * This is the deterministic JVM reproduction of that harness hole. It is
 * not the HostAck-default vacuous case: [TmuxSessionViewModelTestBase]
 * pins [com.pocketshell.app.settings.OutboundDeliveryAuthority.TerminalInference],
 * and the send is asserted to stay on the legacy stack.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue2205AckWedgeNeedleGapTest : TmuxSessionViewModelTestBase() {

    @Test
    fun needleVisibleFrameWithoutChipMustKeepFirstSendRetryable() = runTest(scheduler) {
        HostAckSendProbe.reset()
        OutboundLegacyStackProbe.reset()
        val vm = newVm()
        val inner = FakeTmuxClient()
        inner.defaultCaptureResponse = EMPTY_PROMPT
        val client = Issue1739AckWedgeClient(inner, PAYLOAD)
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(80L)
        assertFalse(
            "this proof is the LEGACY inference lane, not HostAck",
            vm.hostAck.active,
        )

        inner.onCommandSent = { cmd ->
            if (cmd.startsWith("paste-buffer ")) {
                inner.defaultCaptureResponse = NEEDLE_ONLY_FRAME
            }
        }
        client.wedgeNextVisibleCapture = true

        val first = async {
            vm.sendAgentPayloadToPaneResult(
                "%0",
                PAYLOAD,
                AgentKind.ClaudeCode,
                "issue-2205-needle-gap",
            )
        }
        try {
            advanceTimeBy(80L)
            runCurrent()
            assertTrue(
                "unconfirmed first attempt must remain retryable " +
                    "(completed=${first.isCompleted} hostAckActive=${vm.hostAck.active} " +
                    "hostAckSends=${HostAckSendProbe.count()} " +
                    "legacy=${OutboundLegacyStackProbe.snapshot()} " +
                    "wedged=${client.didWedge} landed=${client.landedCapture})",
                first.isCompleted && first.await().isFailure,
            )
            assertTrue("the ack-positive needle frame must have armed the wedge", client.didWedge)
            assertEqualsZeroEnters(inner)
            assertTrue(
                "the send must have entered the legacy paste-ack gate",
                OutboundLegacyStackProbe.pasteAck.get() > 0L,
            )
            assertEquals(
                "HostAck must not own a TerminalInference send",
                0L,
                HostAckSendProbe.count(),
            )
        } finally {
            client.releaseCaptureCleanup()
            advanceUntilIdle()
        }
    }

    @Test
    fun pasteAckAcceptsNeedleVisibleFrameThatChipSubstringMisses() {
        assertFalse(
            "the Integration wedge used to key only on this substring",
            NEEDLE_ONLY_FRAME.output.any { it.contains("[Pasted text #") },
        )
        assertTrue(
            "the paste-ack gate accepts the same frame via the payload needle",
            agentSubmitVisibleFrameAcksPaste(NEEDLE_ONLY_FRAME.output, PAYLOAD),
        )
        assertTrue(
            "the collapsed chip remains an ack-positive frame",
            agentSubmitVisibleFrameAcksPaste(listOf("> [Pasted text #1 +1 lines]"), PAYLOAD),
        )
        assertFalse(
            "an empty prompt must not ack or wedge",
            agentSubmitVisibleFrameAcksPaste(EMPTY_PROMPT.output, PAYLOAD),
        )
    }

    private fun assertEqualsZeroEnters(client: FakeTmuxClient) {
        assertFalse(
            "an unconfirmed capture must never authorize Enter",
            client.sentCommands.any { it == "send-keys -t %0 Enter" || it.endsWith(" Enter") },
        )
    }

    private fun newClaudeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/tmp/issue2205.jsonl",
        sessionId = "issue2205",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    /**
     * Mirrors the #1739 Integration [AckCleanupWedgeClient]: real capture first,
     * then park in NonCancellable teardown when the visible frame would let
     * the paste-ack gate succeed.
     */
    private class Issue1739AckWedgeClient(
        private val delegate: FakeTmuxClient,
        private val payload: String,
    ) : TmuxClient by delegate {
        private val cleanupGate = CompletableDeferred<Unit>()

        @Volatile
        var wedgeNextVisibleCapture: Boolean = false

        @Volatile
        var didWedge: Boolean = false
            private set

        @Volatile
        var landedCapture: List<String> = emptyList()
            private set

        override suspend fun capturePaneTextViaExec(
            paneId: String,
            timeoutMs: Long?,
            scrollbackLines: Int,
        ): CommandResponse {
            val response = delegate.capturePaneTextViaExec(paneId, timeoutMs, scrollbackLines)
            // Issue #2205: wedge on every frame the paste-ack gate would accept
            // (needle OR collapsed chip). The pre-fix chip-only substring left a
            // needle-visible body unwedged, so the first send returned success.
            if (
                !wedgeNextVisibleCapture ||
                scrollbackLines != 0 ||
                !agentSubmitVisibleFrameAcksPaste(response.output, payload)
            ) {
                return response
            }
            wedgeNextVisibleCapture = false
            didWedge = true
            landedCapture = response.output
            try {
                CompletableDeferred<Unit>().await()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    cleanupGate.await()
                }
                throw cancelled
            }
            error("unreachable")
        }

        fun releaseCaptureCleanup() {
            cleanupGate.complete(Unit)
        }
    }

    companion object {
        private const val PAYLOAD =
            "echo issue1739_agent_ack_line1\necho issue1739_agent_ack_exactly_once"
        private val EMPTY_PROMPT = CommandResponse(
            number = 0L,
            output = listOf("> "),
            isError = false,
        )
        private val NEEDLE_ONLY_FRAME = CommandResponse(
            number = 0L,
            output = listOf(
                "> echo issue1739_agent_ack_line1",
                "echo issue1739_agent_ack_exactly_once",
            ),
            isError = false,
        )
    }
}
