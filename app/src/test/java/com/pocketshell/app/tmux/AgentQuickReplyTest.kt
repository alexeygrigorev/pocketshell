package com.pocketshell.app.tmux

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentQuickReplyTest {
    @Test
    fun yNPromptOffersYesAndNoLiteralKeys() {
        val replies = agentQuickRepliesForVisibleText("Proceed with this command? (y/n)")

        assertEquals(
            listOf(
                AgentQuickReply("Yes", "y"),
                AgentQuickReply("No", "n"),
            ),
            replies,
        )
    }

    @Test
    fun numberedYesNoPromptSendsChoiceDigits() {
        val replies = agentQuickRepliesForVisibleText(
            """
            Approve tool call?
            1. Yes
            2. No
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                AgentQuickReply("Yes", "1"),
                AgentQuickReply("No", "2"),
            ),
            replies,
        )
    }

    @Test
    fun enterPromptOffersEnterKey() {
        val replies = agentQuickRepliesForVisibleText("Done. Press Enter to continue")

        assertEquals(listOf(AgentQuickReply("Enter", "\r")), replies)
    }

    @Test
    fun ordinaryNumberedOutputDoesNotOfferReplies() {
        val replies = agentQuickRepliesForVisibleText(
            """
            Recent tasks:
            1. Refactor parser
            2. Update docs
            3. Run tests
            """.trimIndent(),
        )

        assertTrue(replies.isEmpty())
    }

    @Test
    fun stalePromptOutsideVisibleTailIsIgnored() {
        val replies = agentQuickRepliesForVisibleText(
            """
            Proceed with command? (y/n)
            running step 1
            running step 2
            running step 3
            running step 4
            running step 5
            running step 6
            """.trimIndent(),
        )

        assertTrue(replies.isEmpty())
    }

    @Test
    fun yesNoProseWithoutApprovalCueIsIgnored() {
        val replies = agentQuickRepliesForVisibleText("Use yes/no examples in the docs.")

        assertTrue(replies.isEmpty())
    }

    @Test
    fun visibleTextFlowParsesRepliesAndSuppressesDuplicateRows() = runTest {
        val emissions = agentQuickRepliesForVisibleTextFlow(
            visibleText = flowOf(
                "Proceed with this command? (y/n)",
                "Proceed with this command? (y/n)",
                "Done. Press Enter to continue",
            ),
            enabled = true,
        ).toList()

        assertEquals(
            listOf(
                listOf(AgentQuickReply("Yes", "y"), AgentQuickReply("No", "n")),
                listOf(AgentQuickReply("Enter", "\r")),
            ),
            emissions,
        )
    }

    @Test
    fun disabledVisibleTextFlowNeverParsesReplies() = runTest {
        val emissions = agentQuickRepliesForVisibleTextFlow(
            visibleText = flowOf("Proceed with this command? (y/n)"),
            enabled = false,
        ).toList()

        assertEquals(listOf(emptyList<AgentQuickReply>()), emissions)
    }
}
