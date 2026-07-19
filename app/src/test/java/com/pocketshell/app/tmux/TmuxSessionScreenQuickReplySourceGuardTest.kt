package com.pocketshell.app.tmux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Quick-reply detection follows terminal output during busy agent sessions. The
 * large session root must not collect visible terminal text directly; that work
 * belongs in the small quick-reply leaf with off-main parsing.
 */
class TmuxSessionScreenQuickReplySourceGuardTest {

    @Test
    fun rootSessionBodyDoesNotCollectVisibleScreenTextForQuickReplies() {
        // Issue #1685: the quick-reply eligibility derivation moved out of the
        // TmuxSessionScreen god-method into the rememberTmuxSessionAgentSignals
        // state holder (TmuxSessionScreenRuntime.kt) as part of the ART
        // VerifyError decomposition. The invariant is unchanged: the eligibility
        // derivation must NOT collect visible terminal text — that work belongs in
        // the AgentQuickReplyBand leaf (checked below).
        val src = locate("TmuxSessionScreenRuntime.kt")
        val quickReplyEligibility = src.substringBetween(
            start = "val quickReplyInputEligible =",
            end = "// Issue #770",
        )

        assertFalse(
            "TmuxSessionScreen root must not collect terminal visible text; " +
                "keep quick-reply collection in AgentQuickReplyBand.",
            quickReplyEligibility.contains("collectAsState") ||
                quickReplyEligibility.contains("flowOfVisibleScreenText") ||
                quickReplyEligibility.contains("visibleScreenTextSnapshot"),
        )
        val overlaySrc = locate("TmuxSessionOverlays.kt")
        val quickReplyBand = overlaySrc.substringBetween(
            start = "internal fun AgentQuickReplyBand(",
            end = "@Composable\ninternal fun AgentQuickReplyRow(",
        )
        assertTrue(quickReplyBand.contains("agentQuickRepliesForVisibleTextFlow("))
        assertTrue(quickReplyBand.contains("flowOfVisibleScreenText"))
        assertTrue(quickReplyBand.contains("collectAsState(initial = emptyList())"))
    }

    @Test
    fun quickReplyTapRoutesThroughSurfacePaneId() {
        val src = locate("TmuxSessionScreen.kt")
        // Issue #1685: AgentQuickReplyBand moved into the extracted
        // TmuxSessionSurfaceRegion (still in TmuxSessionScreen.kt) at a shallower
        // indentation as part of the ART VerifyError decomposition. The routing
        // invariant (route to pane.paneId, never currentPane/currentPaneId) is
        // unchanged.
        val quickReplyMount = src.substringBetween(
            start = "AgentQuickReplyBand(\n            terminalState = pane.terminalState,",
            end = "// Issue #810",
        )

        assertTrue(
            "Quick-reply taps must route to the surface pane, so cached/session-switch " +
                "surfaces do not send replies to a stale active pane.",
            quickReplyMount.contains("AgentQuickReplyBand(") &&
                quickReplyMount.contains("viewModel.writeInputToPane(") &&
                quickReplyMount.contains("pane.paneId,"),
        )
        assertFalse(
            "The quick-reply callback must not route through currentPane/currentPaneId.",
            quickReplyMount.contains("currentPane") || quickReplyMount.contains("currentPaneId"),
        )
    }

    private fun String.substringBetween(start: String, end: String): String {
        val startIndex = indexOf(start)
        check(startIndex >= 0) { "$start not found" }
        val endIndex = indexOf(end, startIndex)
        check(endIndex >= 0) { "$end not found after $start" }
        return substring(startIndex, endIndex)
    }

    private fun locate(relative: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/pocketshell/app/tmux/$relative"),
            File("src/main/java/com/pocketshell/app/tmux/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
