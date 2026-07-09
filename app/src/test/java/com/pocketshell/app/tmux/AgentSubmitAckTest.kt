package com.pocketshell.app.tmux

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
}
