package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.core.agents.AgentKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSubmitAckTest {
    @Test
    fun agentSubmitAckNeedleUsesLastNonBlankLineTailWithWhitespaceStripped() {
        assertEquals(
            "newsessiontokenformathere".takeLast(AGENT_SUBMIT_ACK_NEEDLE_TAIL_CHARS),
            agentSubmitAckNeedle(
                """
                please ignore this earlier line

                new session token format here
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun agentSubmitAckNeedleReturnsNullForBlankPayload() {
        assertNull(agentSubmitAckNeedle(" \n\t\n "))
    }

    @Test
    fun agentSubmitVisibleTextContainsNeedleSurvivesWrappedMidWordRows() {
        val needle = agentSubmitAckNeedle(
            "validate against the new session token format before handler",
        )!!

        assertTrue(
            agentSubmitVisibleTextContainsNeedle(
                listOf(
                    "against t",
                    "he new session token",
                    " format before handler",
                ),
                needle,
            ),
        )
    }

    @Test
    fun agentSubmitVisibleTextContainsNeedleRejectsUnrelatedVisibleText() {
        val needle = agentSubmitAckNeedle("deploy the staging build")!!

        assertFalse(
            agentSubmitVisibleTextContainsNeedle(
                listOf("nothing related is visible here"),
                needle,
            ),
        )
    }

    // Issue #1687: Claude Code collapses a multi-line paste into a `[Pasted text
    // #N +M lines]` chip and never echoes the body, so the payload-tail needle
    // can never match it. The collapsed-chip recogniser is what confirms the paste.

    @Test
    fun payloadIsMultiLineDistinguishesSingleFromMultiLinePastes() {
        assertFalse(agentSubmitPayloadIsMultiLine("single line prompt"))
        assertTrue(agentSubmitPayloadIsMultiLine("first line\nsecond line"))
    }

    @Test
    fun collapsedPasteMarkerCountRecognisesTheClaudeCodeChip() {
        assertEquals(
            1,
            agentSubmitCollapsedPasteMarkerCount(listOf("> [Pasted text #1 +5 lines]")),
        )
        // Any counter/line-count value, singular or plural "line(s)".
        assertEquals(
            1,
            agentSubmitCollapsedPasteMarkerCount(listOf("[Pasted text #42 +1 line]")),
        )
    }

    @Test
    fun collapsedPasteMarkerCountSurvivesWrappedReflowedChipRows() {
        // capture-pane can wrap the chip across rows mid-token; the whitespace-
        // stripped join must still recognise it.
        assertEquals(
            1,
            agentSubmitCollapsedPasteMarkerCount(
                listOf("> [Pasted te", "xt #1 +12 li", "nes]"),
            ),
        )
    }

    @Test
    fun collapsedPasteMarkerCountReturnsZeroWithoutTheChip() {
        assertEquals(0, agentSubmitCollapsedPasteMarkerCount(listOf("> deploy the build")))
        assertEquals(0, agentSubmitCollapsedPasteMarkerCount(emptyList()))
    }

    @Test
    fun collapsedPasteMarkerCountCountsMultipleSessionChips() {
        // Claude increments #N across the session, so a scrolled-back pane may show
        // several chips; the caller uses the count increase over baseline (#1577b).
        assertEquals(
            2,
            agentSubmitCollapsedPasteMarkerCount(
                listOf("[Pasted text #1 +3 lines]", "[Pasted text #2 +9 lines]"),
            ),
        )
    }

    @Test
    fun outerAckTimeoutKeepsAttemptIdentityAndReportsChangedCurrentIdentity() = runTest {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val attemptClient = FakeTmuxClient()
            val replacementClient = FakeTmuxClient()
            val attemptGeneration = 7L
            var currentClient = attemptClient
            var currentGeneration = attemptGeneration
            val identity = AgentSendRuntimeIdentity(
                client = attemptClient,
                generation = attemptGeneration,
                target = TmuxSessionViewModel.ConnectionTarget(
                    hostId = 1L,
                    hostName = "docker",
                    host = "127.0.0.1",
                    port = 2222,
                    user = "testuser",
                    keyPath = "/tmp/test-key",
                    passphrase = null,
                    sessionName = "issue1739-outer-timeout",
                    startDirectory = null,
                ),
            )

            val failure = runCatching {
                awaitAgentPasteIngested(
                    identity = identity,
                    paneId = "%0",
                    payload = "issue1739 outer timeout",
                    agent = AgentKind.ClaudeCode,
                    configuredFloorMs = 0L,
                    ackTimeoutMs = 100L,
                    baselineNeedleCount = 0,
                    collapsedMarkerBaseline = 0,
                    capture = { _, _ ->
                        currentClient = replacementClient
                        currentGeneration = 8L
                        delay(1_000L)
                        AgentPaneCaptureResult(AgentPaneCaptureStatus.Failed)
                    },
                    currentClientHash = { System.identityHashCode(currentClient) },
                    currentGeneration = { currentGeneration },
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            val event = diagnostics.eventsNamed("agent_submit_ack").single()
            assertEquals("ack_timeout", event.fields["result"])
            assertEquals(System.identityHashCode(attemptClient), event.fields["clientHash"])
            assertEquals(attemptGeneration, event.fields["generation"])
            assertEquals("issue1739-outer-timeout", event.fields["session"])
            assertEquals(System.identityHashCode(replacementClient), event.fields["currentClientHash"])
            assertEquals(8L, event.fields["currentGeneration"])
        } finally {
            diagnostics.close()
        }
    }
}
