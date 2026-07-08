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
        val src = locate("TmuxSessionScreen.kt")
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
        assertTrue(src.contains("private fun AgentQuickReplyBand("))
    }

    @Test
    fun quickReplyTapRoutesThroughSurfacePaneId() {
        val src = locate("TmuxSessionScreen.kt")
        val quickReplyMount = src.substringBetween(
            start = "AgentQuickReplyBand(\n                    terminalState = pane.terminalState,",
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
