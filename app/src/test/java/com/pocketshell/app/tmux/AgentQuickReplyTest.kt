package com.pocketshell.app.tmux

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    // ---- Issue #1235 AC: hidden when not waiting; no flicker on rapid churn ----

    @Test
    fun notWaitingKeepsBandHidden() = runTest {
        // A plain, non-approval terminal frame must resolve to a HIDDEN band
        // (no replies) through the full flow, not just the parser.
        val emissions = agentQuickRepliesForVisibleTextFlow(
            visibleText = flowOf("running build...\n[ok] compiled 42 files\n$ "),
            enabled = true,
            hideDelayMs = 0L,
        ).toList()

        assertEquals(listOf(emptyList<AgentQuickReply>()), emissions)
    }

    /**
     * RED-ON-BASE documentation: WITHOUT the [holdEmptyReplies] hysteresis, a
     * prompt that vanishes for one redraw frame and reappears immediately
     * produces show → hide → show — three distinct emissions the band would
     * flicker through.
     */
    @Test
    fun withoutHysteresisRapidRedrawBlipChurnsVisibility() = runTest {
        val emissions = flickerSource()
            .map { agentQuickRepliesForVisibleText(it) }
            .distinctUntilChanged()
            .toList()

        assertEquals(
            listOf(YES_NO, emptyList(), YES_NO),
            emissions,
        )
    }

    /**
     * GREEN: the [holdEmptyReplies] hysteresis (the #1235 fix) swallows the
     * transient hide so the same rapid blip produces a SINGLE stable emission —
     * the band never flickers. This assertion FAILS on base (which emits the
     * three-way churn above); it passes only with the hysteresis in place.
     */
    @Test
    fun hysteresisSuppressesRapidRedrawFlicker() = runTest {
        val emissions = flickerSource()
            .map { agentQuickRepliesForVisibleText(it) }
            .distinctUntilChanged()
            .holdEmptyReplies(hideDelayMs = 600L)
            .distinctUntilChanged()
            .toList()

        assertEquals(listOf(YES_NO), emissions)
    }

    /**
     * A GENUINE hide (the prompt is gone and stays gone well past the window)
     * must still land — the hysteresis defers, it does not suppress forever.
     */
    @Test
    fun sustainedPromptDisappearanceStillHidesTheBand() = runTest {
        val source = flow {
            emit("Proceed with this command? (y/n)")
            delay(50)
            emit("done.\n$ ") // prompt gone for good
            delay(2_000) // far beyond the 600ms hide window
        }
        val emissions = source
            .map { agentQuickRepliesForVisibleText(it) }
            .distinctUntilChanged()
            .holdEmptyReplies(hideDelayMs = 600L)
            .distinctUntilChanged()
            .toList()

        assertEquals(listOf(YES_NO, emptyList()), emissions)
    }

    private fun flickerSource() = flow {
        emit("Proceed with this command? (y/n)") // show
        delay(50)
        emit("Proceed with this command?\n") // transient repaint: prompt cue gone
        delay(50)
        emit("Proceed with this command? (y/n)") // reappears within the hide window
        delay(1_000) // settle past the window with the prompt still present
    }

    private companion object {
        val YES_NO = listOf(
            AgentQuickReply("Yes", "y"),
            AgentQuickReply("No", "n"),
        )
    }
}
